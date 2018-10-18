/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.kohsuke.groovy.sandbox;

import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class FinalizerTest {
    private static final String SCRIPT_HARNESS =
            "class Global {\n" +
            "  static volatile boolean result = false\n" +
            "}\n" +
            "class Test {\n" +
            "  METHOD { Global.result = true; }\n" +
            "}\n" +
            "def t = new Test()\n" +
            "t = null\n" +
            // TODO: Flaky, can it be made more reliable?
            "for (int i = 0; i < 10 && Global.result == false; i++) {\n" +
            "  System.gc()\n" +
            "  System.runFinalization()\n" +
            "  Thread.sleep(100)\n" +
            "}\n" +
            "Global.result";

    private GroovyShell sandboxedSh;
    private GroovyShell unsandboxedSh;

    @Before
    public void setUp() {
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new ImportCustomizer().addImports("groovy.transform.PackageScope"));
        cc.addCompilationCustomizers(new SandboxTransformer());
        sandboxedSh = new GroovyShell(cc);
        cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new ImportCustomizer().addImports("groovy.transform.PackageScope"));
        unsandboxedSh = new GroovyShell(cc);
    }

    /**
     * These scripts are forbidden by {@link SandboxTransformer#call} after the SECURITY-1186 fix.
     */
    @Issue("SECURITY-1186")
    @Test
    public void testOverridingFinalizeForbidden() {
        assertForbidden("@Override public void finalize()", true);
        assertForbidden("protected void finalize()", true);
        // Groovy's default access modifier is public.
        assertForbidden("void finalize()", true);
        assertForbidden("def void finalize()", true);
        // This finalizer would be invoked despite having @PackageScope, so it must be forbidden.
        assertForbidden("@PackageScope void finalize()", true);
        // Finalizers with only default parameters will cause a finalizer with no parameters to be
        // introduced, so they must be forbidden.
        assertForbidden("public void finalize(Object p1 = null)", true);
        assertForbidden("public void finalize(Object p1 = null, Object p2 = null)", true);
        assertForbidden("public void finalize(Object[] args = [null, null])", true);
        assertForbidden("public void finalize(Object... args = [null, null])", true);
    }

    /**
     * These scripts throw compilation failures even before the fix for SECURITY-1186 because they
     * are improper overrides of {@link Object#finalize}.
     */
    @Issue("SECURITY-1186")
    @Test
    public void testImproperOverrideOfFinalize() {
        assertImproperOverride("private void finalize()");
        assertImproperOverride("private static void finalize()");
        assertImproperOverride("private Object finalize()");
        assertImproperOverride("public Object finalize()");
        assertImproperOverride("def finalize()");
    }

    /**
     * These classes are allowed by {@link SandboxTransformer#call} because their finalize method
     * won't be invoked outside of the sandbox by the JVM.
     */
    @Issue("SECURITY-1186")
    @Test
    public void testFinalizePermittedAsNonOverride() {
        assertFinalizerNotCalled("public static void finalize()");
        assertFinalizerNotCalled("static void finalize()");
        assertFinalizerNotCalled("protected static void finalize()");
        assertFinalizerNotCalled("public void finalize(Object p)");
        assertFinalizerNotCalled("protected void finalize(Object p)");
        assertFinalizerNotCalled("private void finalize(Object p)");
        assertFinalizerNotCalled("public void finalize(Object p1, Object p2 = null)");
        assertFinalizerNotCalled("public void finalize(Object p1 = null, Object p2)");
        assertFinalizerNotCalled("def void finalize(Map args)");
    }

    private void assertForbidden(String methodStub, boolean isDangerous) {
        String script = SCRIPT_HARNESS.replace("METHOD", methodStub);
        try {
            sandboxedSh.evaluate(script);
            fail("Should have failed");
        } catch (MultipleCompilationErrorsException e) {
            assertThat(e.getErrorCollector().getErrorCount(), equalTo(1));
            Exception innerE = e.getErrorCollector().getException(0);
            assertThat(innerE, instanceOf(SecurityException.class));
            assertThat(innerE.getMessage(), containsString("Object.finalize()"));
        }
        Object actual = unsandboxedSh.evaluate(script);
        assertThat(actual, equalTo((Object)isDangerous));
    }

    private void assertImproperOverride(String methodStub) {
        try {
            sandboxedSh.evaluate(SCRIPT_HARNESS.replace("METHOD", methodStub));
            fail("Should have failed");
        } catch (MultipleCompilationErrorsException e) {
            assertThat(e.getErrorCollector().getErrorCount(), equalTo(1));
            assertThat(e.getMessage(), anyOf(
                    containsString("cannot override finalize in java.lang.Object"),
                    containsString("incompatible with void in java.lang.Object")));
        }
    }

    private void assertFinalizerNotCalled(String methodStub) {
        Boolean actual = (Boolean)sandboxedSh.evaluate(SCRIPT_HARNESS.replace("METHOD", methodStub));
        assertThat(actual, equalTo(false));
    }

}
