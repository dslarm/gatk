package org.broadinstitute.hellbender.engine;

import htsjdk.samtools.SAMFileHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.engine.spark.AssemblyRegionArgumentCollection;
import org.broadinstitute.hellbender.utils.IntervalUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.activityprofile.ActivityProfile;
import org.broadinstitute.hellbender.utils.activityprofile.ActivityProfileState;
import org.broadinstitute.hellbender.utils.activityprofile.BandPassActivityProfile;
import org.broadinstitute.hellbender.utils.downsampling.DownsamplingMethod;
import org.broadinstitute.hellbender.utils.iterators.IntervalLocusIterator;
import org.broadinstitute.hellbender.utils.iterators.ReadCachingIterator;
import org.broadinstitute.hellbender.utils.locusiterator.IntervalAlignmentContextIterator;
import org.broadinstitute.hellbender.utils.locusiterator.LocusIteratorByState;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.ReadUtils;

import java.util.*;

/**
 * Given a {@link MultiIntervalShard} of {@link GATKRead}, iterates over each {@link AssemblyRegion} within
 * that shard, using the provided {@link AssemblyRegionEvaluator} to determine the boundaries between assembly
 * regions.
 *
 * Loads the reads from the shard as lazily as possible to minimize memory usage.
 *
 * This iterator represents the core of the {@link AssemblyRegionWalker} traversal.
 *
 * NOTE: the provided shard must have appropriate read filters set on it for this traversal type (ie., unmapped
 * and malformed reads must be filtered out).
 */
public class AssemblyRegionIterator implements Iterator<AssemblyRegion> {
    private static final Logger logger = LogManager.getLogger(AssemblyRegionIterator.class);

    private final MultiIntervalShard<GATKRead> readShard;
    private final SAMFileHeader readHeader;
    private final ReferenceDataSource reference;
    private final FeatureManager features;
    private final AssemblyRegionEvaluator evaluator;
    final AssemblyRegionArgumentCollection assemblyRegionArgs;
    
    private AssemblyRegion readyRegion;
    private Queue<AssemblyRegion> pendingRegions;
    private List<GATKRead> previousRegionReads;
    private final ReadCachingIterator readCachingIterator;
    private Queue<GATKRead> readCache;
    private final Iterator<AlignmentContext> locusIterator;
    private final LocusIteratorByState libs;
    private final ActivityProfile activityProfile;
    private Queue<AlignmentAndReferenceContext> pendingAlignmentData;

    /**
     * Constructs an AssemblyRegionIterator over a provided read shard
     *  @param readShard MultiIntervalShard containing the reads that will go into the assembly regions.
     *                  Must have a MAPPED filter set on it.
     * @param readHeader header for the reads
     * @param reference source of reference bases (may be null)
     * @param features source of arbitrary features (may be null)
     * @param evaluator evaluator used to determine whether a locus is active
     */
    public AssemblyRegionIterator(final MultiIntervalShard<GATKRead> readShard,
                                  final SAMFileHeader readHeader,
                                  final ReferenceDataSource reference,
                                  final FeatureManager features,
                                  final AssemblyRegionEvaluator evaluator,
                                  final AssemblyRegionArgumentCollection assemblyRegionArgs,
                                  final boolean trackPileups ) {

        Utils.nonNull(readShard);
        Utils.nonNull(readHeader);
        Utils.nonNull(evaluator);
        assemblyRegionArgs.validate();


        this.readShard = readShard;
        this.readHeader = readHeader;
        this.reference = reference;
        this.features = features;
        this.evaluator = evaluator;
        this.assemblyRegionArgs = assemblyRegionArgs;

        this.readyRegion = null;
        this.previousRegionReads = null;
        this.pendingRegions = new ArrayDeque<>();
        this.readCachingIterator = new ReadCachingIterator(readShard.iterator());
        this.readCache = new ArrayDeque<>();
        this.activityProfile = new BandPassActivityProfile(assemblyRegionArgs.maxProbPropagationDistance, assemblyRegionArgs.activeProbThreshold, BandPassActivityProfile.MAX_FILTER_SIZE, BandPassActivityProfile.DEFAULT_SIGMA, readHeader);
        this.pendingAlignmentData = trackPileups ? new ArrayDeque<>() : null;

        // We wrap our LocusIteratorByState inside an IntervalAlignmentContextIterator so that we get empty loci
        // for uncovered locations. This is critical for reproducing GATK 3.x behavior!
        this.libs = new LocusIteratorByState(readCachingIterator, DownsamplingMethod.NONE, ReadUtils.getSamplesFromHeader(readHeader), readHeader, true);
        final IntervalLocusIterator intervalLocusIterator = new IntervalLocusIterator(readShard.getIntervals().iterator());
        this.locusIterator = new IntervalAlignmentContextIterator(libs, intervalLocusIterator, readHeader.getSequenceDictionary());

        readyRegion = loadNextAssemblyRegion();
    }

    @Override
    public boolean hasNext() {
        return readyRegion != null;
    }

    @Override
    public AssemblyRegion next() {
        if (!hasNext()) {
            throw new NoSuchElementException("next() called when there were no more elements");
        }

        final AssemblyRegion toReturn = readyRegion;
        previousRegionReads = toReturn.getReads();
        readyRegion = loadNextAssemblyRegion();
        return toReturn;
    }

    private AssemblyRegion loadNextAssemblyRegion() {
        AssemblyRegion nextRegion = null;

        while ( locusIterator.hasNext() && nextRegion == null ) {
            final AlignmentContext pileup = locusIterator.next();

            logger.debug(()->"AssemblyRegionIterator pileup: " + pileup);

            // Pop any new pending regions off of the activity profile. These pending regions will not become ready
            // until we've traversed all the reads that belong in them.
            //
            // Ordering matters here: need to check for forceConversion before adding current pileup to the activity profile
            if ( ! activityProfile.isEmpty() ) {
                final boolean forceConversion = pileup.getLocation().getStart() != activityProfile.getEnd() + 1;
                pendingRegions.addAll(activityProfile.popReadyAssemblyRegions(assemblyRegionArgs.assemblyRegionPadding, assemblyRegionArgs.minAssemblyRegionSize, assemblyRegionArgs.maxAssemblyRegionSize, forceConversion));
            }

            // Add the current pileup to the activity profile
            final SimpleInterval pileupInterval = new SimpleInterval(pileup);
            final ReferenceContext pileupRefContext = new ReferenceContext(reference, pileupInterval);
            final FeatureContext pileupFeatureContext = new FeatureContext(features, pileupInterval);
            if (pendingAlignmentData!=null) {
                pendingAlignmentData.add(new AlignmentAndReferenceContext(pileup, pileupRefContext));
            }

            final ActivityProfileState profile = evaluator.isActive(pileup, pileupRefContext, pileupFeatureContext);
            logger.debug(() -> profile.toString());
            activityProfile.add(profile);

            // A pending region only becomes ready once our locus iterator has advanced beyond the end of its extended span
            // (this ensures that we've loaded all reads that belong in the new region)
            if ( ! pendingRegions.isEmpty() && IntervalUtils.isAfter(pileup.getLocation(), pendingRegions.peek().getPaddedSpan(), readHeader.getSequenceDictionary()) ) {
                nextRegion = pendingRegions.poll();
            }
        }

        // When we run out of loci, close out the activity profile, and close out any remaining pending regions one at a time
        // It may require multiple invocations before the pendingRegions queue is cleared out.
        if ( ! locusIterator.hasNext() ) {
            
            // Pull on the encapsulated LocusIteratorByState until it's exhausted, as the enclosing
            // IntervalLocusIterator is not guaranteed to exhaust it. This guarantees that the reads
            // in the final padded region end up in our read cache.
            while ( libs.hasNext() ) {
                libs.next();
            }

            if ( ! activityProfile.isEmpty() ) {
                // Pop the activity profile a final time with forceConversion == true
                pendingRegions.addAll(activityProfile.popReadyAssemblyRegions(assemblyRegionArgs.assemblyRegionPadding, assemblyRegionArgs.minAssemblyRegionSize, assemblyRegionArgs.maxAssemblyRegionSize, true));
            }

            // Grab the next pending region if there is one, unless we already have a region ready to go
            // (could happen if we finalize a region on the same iteration that we run out of loci in the locus iterator)
            if ( ! pendingRegions.isEmpty() && nextRegion == null ) {
                nextRegion = pendingRegions.poll();
            }
        }

        // If there's a region ready, fill it with reads before returning
        if ( nextRegion != null ) {
            fillNextAssemblyRegionWithReads(nextRegion);
            // fillnextessemblyregion; check you are on correct chr; if alignment data is not in the assembly region then pop it
            fillNextAssemblyRegionWithPileupData(nextRegion);
        }

        return nextRegion;
    }

    private void fillNextAssemblyRegionWithReads( final AssemblyRegion region ) {
        // First we need to check the previous region for reads that also belong in this region
        if ( previousRegionReads != null ) {
            for ( final GATKRead previousRegionRead : previousRegionReads ) {
                if ( region.getPaddedSpan().overlaps(previousRegionRead) ) {
                    region.add(previousRegionRead);
                }
            }
        }

        // Update our read cache with the reads newly-read by our locus iterator.
        // Note that the cache is (crucially) maintained in coordinate order.
        readCache.addAll(readCachingIterator.consumeCachedReads());

        // Add all reads from the cache that belong in this region
        while ( ! readCache.isEmpty() ) {
            final GATKRead nextRead = readCache.peek();

            // Stop once we encounter a read that starts after the end of the last region's span
            // (and leave it in the readCache)
            if ( IntervalUtils.isAfter(nextRead, region.getPaddedSpan(), readHeader.getSequenceDictionary()) ) {
                break;
            }

            // Ok, we're going to consume this read
            readCache.poll();

            // Add the read if it overlaps the region's extended span. If it doesn't, it must end before the
            // start of the region's extended span, so we discard it.
            if ( region.getPaddedSpan().overlaps(nextRead) ) {
                region.add(nextRead);
            }
        }
    }

    private void fillNextAssemblyRegionWithPileupData(final AssemblyRegion region){
        // Save ourselves the memory footprint and work of saving the pileups in the event they aren't needed for processing.
        if (pendingAlignmentData == null){
            return;
        }
        final List<AlignmentAndReferenceContext> overlappingAlignmentData = new ArrayList<>();
        final Queue<AlignmentAndReferenceContext> previousAlignmentData = new ArrayDeque<>();

        while (!pendingAlignmentData.isEmpty()) {
            final AlignmentContext pendingAlignmentContext = pendingAlignmentData.peek().getAlignmentContext();
            if (!pendingAlignmentContext.contigsMatch(region) ||
                pendingAlignmentContext.getStart() < region.getStart()) {
                pendingAlignmentData.poll(); // pop this
            } else {
                break;
            }
        }
        while (!pendingAlignmentData.isEmpty()) {
            final AlignmentContext pendingAlignmentContext = pendingAlignmentData.peek().getAlignmentContext();

            if (!pendingAlignmentContext.contigsMatch(region) ||
                    pendingAlignmentContext.getStart() <= region.getEnd()) {
                overlappingAlignmentData.add(pendingAlignmentData.poll()); // pop into overlappingAlignmentData
            } else {
                break;
            }
        }

        // reconstructing queue to contain items that may be in the next assembly region
        previousAlignmentData.addAll(overlappingAlignmentData);
        previousAlignmentData.addAll(pendingAlignmentData);
        pendingAlignmentData = previousAlignmentData;

        region.addAllAlignmentData(overlappingAlignmentData);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove() not supported by AssemblyRegionIterator");
    }
}
