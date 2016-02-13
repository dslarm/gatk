package org.broadinstitute.hellbender.utils;

import com.google.common.collect.Sets;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.tools.ApplyBQSR;
import org.broadinstitute.hellbender.tools.walkers.annotator.VariantAnnotation;
import org.broadinstitute.hellbender.utils.test.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.*;
import java.util.stream.Collectors;

public final class ClassUtilsUnitTest extends BaseTest{

    private static class Priv{

    }
    @Test
    public void testCanMakeInstances() {
        Assert.assertFalse(ClassUtils.canMakeInstances(null));
        Assert.assertFalse(ClassUtils.canMakeInstances(int.class));
        Assert.assertFalse(ClassUtils.canMakeInstances(List.class));

        class Local{}
        Assert.assertFalse(ClassUtils.canMakeInstances(Local.class));

        Assert.assertFalse(ClassUtils.canMakeInstances(Priv.class));

        Assert.assertFalse(ClassUtils.canMakeInstances(AbstractList.class));
        Assert.assertTrue(ClassUtils.canMakeInstances(ArrayList.class));
    }

    @Test
    public void testMakeInstancesOfAnnotations() throws Exception {
        //check that this does not blow up - ie each VariantAnnotation has a non-arg constructor
        ClassUtils.makeInstancesOfSubclasses(VariantAnnotation.class, VariantAnnotation.class.getPackage());
    }

    abstract static class C{}
    static class C1 extends C{}
    static final class C2 extends C{}
    static final class C11 extends C1{}

    @Test
    public void testMakeInstances() throws Exception {
        final List<? extends C> cs = ClassUtils.makeInstancesOfSubclasses(C.class, C.class.getPackage());
        Assert.assertEquals(cs.stream().map(o -> o.getClass().getSimpleName()).collect(Collectors.toSet()),
                Sets.newHashSet(C1.class.getSimpleName(), C11.class.getSimpleName(), C2.class.getSimpleName()));
    }

    @Test
    public void testMakeInstancesOfCLP() throws Exception {
        //check that this does not blow up - ie CLPs have a non-arg constructor
        ClassUtils.makeInstancesOfSubclasses(CommandLineProgram.class, ApplyBQSR.class.getPackage());
    }


    interface A{}
    interface A1 extends A{}
    interface B1 extends A1{}

    @Test
    public void testKnownSubinterfaces() throws Exception {
        Assert.assertEquals(new HashSet<>(ClassUtils.knownSubInterfaceSimpleNames(A.class)), new HashSet<>(Arrays.asList("A1", "B1")));
    }
}