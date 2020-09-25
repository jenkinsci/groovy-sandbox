/*
 * The MIT License
 *
 * Copyright 2019 CloudBees, Inc.
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

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.jvnet.hudson.test.Issue;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class SandboxTransformerTest {
    public @Rule ErrorCollector ec = new ErrorCollector();
    public Binding binding = new Binding();
    public GroovyShell sandboxedSh;
    public GroovyShell unsandboxedSh;
    public ClassRecorder cr = new ClassRecorder();

    @Before
    public void setUp() {
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new ImportCustomizer().addImports(SandboxTransformerTest.class.getName()).addStarImports("org.kohsuke.groovy.sandbox"));
        cc.addCompilationCustomizers(new SandboxTransformer());
        sandboxedSh = new GroovyShell(binding,cc);

        cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new ImportCustomizer().addImports(SandboxTransformerTest.class.getName()).addStarImports("org.kohsuke.groovy.sandbox"));
        unsandboxedSh = new GroovyShell(binding,cc);
    }

    /**
     * Use {@code ShouldFail.class} as the expected result for {@link #sandboxedEval} and {@link #unsandboxedEval}
     * when the expression is expected to throw an exception.
     */
    private static final class ShouldFail { }

    @FunctionalInterface
    public interface ExceptionHandler {
        public void handleException(Exception e) throws Exception;
    }

    /**
     * Executes a Groovy expression inside of the sandbox.
     * @param expression The Groovy expression to execute.
     * @return the result of executing the expression.
     */
    private void sandboxedEval(String expression, Object expectedResult, ExceptionHandler handler) {
        cr.reset();
        cr.register();
        try {
            Object actual = sandboxedSh.evaluate(expression);
            ec.checkThat("Sandboxed result does not match expected result", actual, equalTo(expectedResult));
        } catch (Exception e) {
            ec.checkSucceeds(() -> {
                try {
                    handler.handleException(e);
                } catch (Throwable t) {
                    t.addSuppressed(e); // Keep the original error around in case an assertion fails in the handler.
                    throw t;
                }
                return null;
            });
        } finally {
            cr.unregister();
        }
    }

    /**
     * Executes a Groovy expression outside of the sandbox.
     * @param expression The Groovy expression to execute.
     * @return the result of executing the expression.
     */
    private void unsandboxedEval(String expression, Object expectedResult, ExceptionHandler handler) {
        try {
            Object actual = unsandboxedSh.evaluate(expression);
            ec.checkThat("Unsandboxed result does not match expected result", actual, equalTo(expectedResult));
        } catch (Exception e) {
            ec.checkSucceeds(() -> {
                handler.handleException(e);
                return null;
            });
        }
    }

    /**
     * Execute a Groovy expression both in and out of the sandbox and check that the return value matches the
     * expected value and that the given list of method calls are intercepted by the sandbox.
     * @param expression The Groovy expression to execute.
     * @param expectedReturnValue The expected return value for running the script.
     * @param expectedCalls The method calls that are expected to be intercepted by the sandbox.
     */
    private void assertIntercept(String expression, Object expectedReturnValue, String... expectedCalls) {
        sandboxedEval(expression, expectedReturnValue, e -> {
            throw new RuntimeException("Failed to evaluate sandboxed expression: " + expression, e);
        });
        unsandboxedEval(expression, expectedReturnValue, e -> {
            throw new RuntimeException("Failed to evaluate unsandboxed expression: " + expression, e);
        });
        ec.checkThat(cr.toString().split("\n"), equalTo(expectedCalls));
    }

    @Issue("SECURITY-1465")
    @Test public void sandboxTransformsMethodPointerLhs() throws Exception {
        assertIntercept(
                "({" +
                "  System.getProperties()\n" +
                "  1" +
                "}().&toString)()",
                "1",
                "Script1$_run_closure1.call()",
                "System:getProperties()",
                "SandboxedMethodClosure.call()",
                "Integer.toString()");
    }

    @Issue("SECURITY-1465")
    @Test public void sandboxTransformsMethodPointerRhs() throws Exception {
        try {
            System.setProperty("sandboxTransformsMethodPointerRhs", "toString");
            assertIntercept(
                "1.&(System.getProperty('sandboxTransformsMethodPointerRhs'))()",
                "1",
                "System:getProperty(String)",
                "SandboxedMethodClosure.call()",
                "Integer.toString()");
        } finally {
            System.clearProperty("sandboxTransformsMethodPointerRhs");
        }
    }

    @Issue("SECURITY-1465")
    @Test public void sandboxWillNotCastNonStandardCollections() throws Exception {
        // Note: If you run this test in a debugger and inspect the proxied closure in Checker#preCheckedCast, the test
        // will probably fail because the debugger will invoke the closure while trying to display the value, which will
        // update the value of i, changing the behavior.

        /* The cast to Collection proxies the Closure to a Collection.
         * The cast to File invokes `new File(... proxied collection's elements as constructor arguments)`.
         * The cast to Object[] reads lines from the File.
         * The trick here is that the closure returns null from the first call to `toArray` in `Checker.preCheckedCast`,
         * so it bypassed the interceptor but still worked correctly in the actual cast before the fix.
         */
        sandboxedEval(
                "def i = 0\n" +
                "(({-> if(i) {\n" +
                "    return ['secret.txt'] as Object[]\n" + // Cast here is just so `toArray` returns an array instead of a List.
                "  } else {\n" +
                "    i = 1\n" +
                "    return null\n" +
                "  }\n" +
                "} as Collection) as File) as Object[]",
                ShouldFail.class,
                e -> {
                    assertThat(e, instanceOf(UnsupportedOperationException.class));
                    assertThat(e.getMessage(),
                            containsString("Casting non-standard Collections to a type via constructor is not supported."));
                });
    }

    @Issue("SECURITY-1465")
    @Test public void sandboxWillNotCastNonStandardCollectionsEvenIfHarmless() throws Exception {
        // Not problematic even before the fix because it is consistent, but there is no good way to differentiate
        // between this and the expression in sandboxWillNotCastNonStandardCollections, so they both get blocked.
        sandboxedEval("(({-> return ['secret.txt'] as Object[] } as Collection) as File) as Object[]", ShouldFail.class, e -> {
            assertThat(e, instanceOf(UnsupportedOperationException.class));
            assertThat(e.getMessage(),
                    containsString("Casting non-standard Collections to a type via constructor is not supported."));
        });
    }

    @Issue("SECURITY-1465")
    @Test public void sandboxWillCastStandardCollections() throws Exception {
        Path secret = Paths.get("secret.txt");
        try {
            Files.write(secret, Arrays.asList("secretValue"));
            assertIntercept(
                    "(Arrays.asList('secret.txt') as File) as Object[]",
                    new String[]{"secretValue"},
                    "Arrays:asList(String)",
                    "new File(String)",
                    "ResourceGroovyMethods:readLines(File)");
            assertIntercept(
                    "(Collections.singleton('secret.txt') as File) as Object[]",
                    new String[]{"secretValue"},
                    "Collections:singleton(String)",
                    "new File(String)",
                    "ResourceGroovyMethods:readLines(File)");
            assertIntercept(
                    "(new ArrayList<>(Arrays.asList('secret.txt')) as File) as Object[]",
                    new String[]{"secretValue"},
                    "Arrays:asList(String)",
                    "new ArrayList(Arrays$ArrayList)",
                    "new File(String)",
                    "ResourceGroovyMethods:readLines(File)");
            assertIntercept(
                    "(new HashSet<>(Arrays.asList('secret.txt')) as File) as Object[]",
                    new String[]{"secretValue"},
                    "Arrays:asList(String)",
                    "new HashSet(Arrays$ArrayList)",
                    "new File(String)",
                    "ResourceGroovyMethods:readLines(File)");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Issue("SECURITY-1465")
    @Test public void sandboxInterceptsEnumClassToArrayCasts() throws Exception {
        assertIntercept(
                "(java.util.concurrent.TimeUnit.class as Object[])",
                TimeUnit.values(),
                "Class.NANOSECONDS",
                "Class.MICROSECONDS",
                "Class.MILLISECONDS",
                "Class.SECONDS",
                "Class.MINUTES",
                "Class.HOURS",
                "Class.DAYS");
    }

    @Issue("SECURITY-1538")
    @Test public void sandboxTransformsMethodNameInMethodCalls() throws Exception {
        assertIntercept(
            "1.({ System.getProperties(); 'toString' }())()",
            "1",
            "Script1$_run_closure1.call()",
            "System:getProperties()",
            "Integer.toString()");
    }

    @Issue("SECURITY-1538")
    @Test public void sandboxTransformsPropertyNameInLhsOfAssignmentOps() throws Exception {
        assertIntercept(
            "class Test {\n" +
            "  def x\n" +
            "}\n" +
            "def t = new Test()\n" +
            "t.({\n" +
            "  System.getProperties()\n" +
            "  'x'\n" +
            "}()) = 1\n" +
            "t.x",
            1,
            "new Test()",
            "Script1$_run_closure1.call()",
            "System:getProperties()",
            "Test.x=Integer",
            "Test.x");
    }

    @Issue("SECURITY-1538")
    @Test public void sandboxTransformsPropertyNameInPrefixPostfixOps() throws Exception {
        assertIntercept(
            "class Test {\n" +
            "  def x = 0\n" +
            "}\n" +
            "def t = new Test()\n" +
            "(t.({\n" +
            "  System.getProperties()\n" +
            "  'x'\n" +
            "}()))++\n" +
            "t.x",
            1,
            "new Test()",
            "Script1$_run_closure1.call()",
            "System:getProperties()",
            "Test.x",
            "Integer.next()",
            "Test.x=Integer",
            "Test.x");
    }

    @Issue("SECURITY-1538")
    @Test public void sandboxTransformsComplexExpressionsInPrefixOps() throws Exception {
        assertIntercept(
            "++({ System.getProperties(); 1 }())",
            2,
            "Script1$_run_closure1.call()",
            "System:getProperties()",
            "Integer.next()");
    }

    @Issue("SECURITY-1538")
    @Test public void sandboxTransformsComplexExpressionsInPostfixOps() throws Exception {
        assertIntercept(
            "({ System.getProperties(); 1 }())++",
            1,
            "Script1$_run_closure2.call()",
            "System:getProperties()",
            "Script1$_run_closure1.call(Integer)",
            "Integer.next()");
    }

    @Test public void sandboxTransformsInitialExpressionsForConstructorParameters() throws Exception {
        assertIntercept(
                "class B { }\n" +
                "class A extends B {\n" +
                "  A(x = System.getProperties()) {\n" +
                "    super()\n" +
                "  }\n" +
                "}\n" +
                "new A()\n" +
                "true\n",
                true,
                "new A()",
                "System:getProperties()",
                "new B()");
    }

    @Issue("SECURITY-1658")
    @Test public void sandboxTransformsInitialExpressionsForClosureParameters() throws Exception {
        assertIntercept(
                "({ p = System.getProperties() -> true })()",
                true,
                "Script1$_run_closure1.call()",
                "System:getProperties()");
    }

    @Issue("SECURITY-1754")
    @Test public void interceptThisConstructorCalls() throws Exception {
        assertIntercept(
                "class Superclass { }\n" +
                "class Subclass extends Superclass {\n" +
                "  Subclass() { this(1) }\n" +
                "  Subclass(int x) {  }\n" +
                "}\n" +
                "new Subclass()\n" +
                "null",
                null,
                "new Subclass()",
                "new Subclass(Integer)",
                "new Superclass()");
    }

    @Issue("SECURITY-1754")
    @Test public void blocksDirectCallsToSyntheticConstructors() throws Exception {
        sandboxedEval(
                "class Superclass { }\n" +
                "class Subclass extends Superclass {\n" +
                "  Subclass() { }\n" +
                "}\n" +
                "new Subclass(null)\n",
                ShouldFail.class,
                e -> assertThat(e.getMessage(), equalTo(
                        "Rejecting illegal call to synthetic constructor: private Subclass(org.kohsuke.groovy.sandbox.impl.Checker$SuperConstructorWrapper). " +
                        "Perhaps you meant to use one of these constructors instead: public Subclass()")));
    }

    @Issue("SECURITY-1754")
    @Test public void blocksCallsToSyntheticConstructorsViaOtherConstructors() throws Exception {
        sandboxedEval(
                "class Superclass { }\n" +
                "class Subclass extends Superclass {\n" +
                "  Subclass() { }\n" +
                "  Subclass(int x, int y) { this(null) }\n" + // Directly calls synthetic constructor generated the handle the other constructor.
                "}\n" +
                "new Subclass(1, 2)\n",
                ShouldFail.class,
                e -> assertThat(e.getMessage(), equalTo(
                        "Rejecting illegal call to synthetic constructor: private Subclass(org.kohsuke.groovy.sandbox.impl.Checker$SuperConstructorWrapper). " +
                        "Perhaps you meant to use one of these constructors instead: public Subclass(), public Subclass(int,int)")));
    }

    @Issue("SECURITY-1754")
    @Test public void blocksUnintendedCallsToNonSyntheticConstructors() throws Exception {
        sandboxedEval(
                "class B { }\n" +
                "class F extends B { }\n" +
                "class S extends B {\n" +
                "  Object scw\n" +
                "  S(Object o) { }\n" +
                "  S(Object o, F f) { scw = o }\n" +
                "}\n" +
                "new S(new F()).scw",
                ShouldFail.class,
                e -> assertThat(e.getMessage(), equalTo(
                        "Rejecting unexpected invocation of constructor: public S(java.lang.Object,F). " +
                        "Expected to invoke synthetic constructor: private S(org.kohsuke.groovy.sandbox.impl.Checker$SuperConstructorWrapper,java.lang.Object)")));
    }

    @Issue("SECURITY-1754")
    @Test public void localVarsInIfStatementsAreNotInScopeInElseStatements() throws Exception {
        sandboxedEval(
                "class Super { }\n" +
                "class Sub extends Super {\n" +
                "  def var\n" +
                "  Sub() {\n" +
                "    if (false)\n" +  // Intentionally not using braces for the body.
                "      def $cw\n" + // The name of the parameter for constructor wrappers added by `SandboxTransformer.processConstructors()`.
                "    else {\n" +
                "      this.var = $cw\n" +
                "    }\n" +
                "  }\n" +
                "}\n" +
                "new Sub().var\n",
                ShouldFail.class, // Previously, would have been an instance of Checker.SuperConstructorWrapper.
                e -> assertThat(e.getMessage(), containsString("No such property: $cw for class: Sub")));
    }

    @Issue("SECURITY-1754")
    @Test public void statementsInSyntheticConstructorsAreScopedCorrectly() throws Exception {
        assertIntercept(
                "class Super { }\n" +
                "class Sub extends Super {\n" +
                "  Sub() {\n" +
                "    def x = 1\n" +
                "    x += 1\n" +
                "  }\n" +
                "}\n" +
                "new Sub()\n" +
                "null\n",
                null,
                "new Sub()",
                "new Super()");
    }

    @Issue("SECURITY-2020")
    @Test public void sandboxedCodeRejectedWhenExecutedOutsideOfSandbox() throws Exception {
        cr.reset();
        cr.register();
        Object returnValue;
        try {
            returnValue = sandboxedSh.evaluate(
                    "class Test {\n" +
                    "  @Override public String toString() {\n" +
                    "    System.getProperties()\n" +
                    "    'test'\n" +
                    "  }\n" +
                    "}\n" +
                    "new Test()");
        } finally {
            cr.unregister();
        }
        try {
            // Test.equals and Test.getClass are inherited and not sandbox-transformed, so they can be called outside of the sandbox.
            assertFalse(returnValue.equals(new Object()));
            assertThat(returnValue.getClass().getSimpleName(), equalTo("Test"));
            // Test.toString is defined in the sandbox, so it cannot be called outside of the sandbox.
            returnValue.toString();
            fail("Test.toString should have thrown a SecurityException");
        } catch (SecurityException e) {
            assertThat(e.getMessage(), equalTo("Rejecting unsandboxed static method call: java.lang.System.getProperties()"));
        }
    }

    @Test public void constructorWithVarArgs() throws Exception {
        assertIntercept(
                "def result = []\n" +
                "class Test {\n" +
                "  Test(List<Integer> result, Integer... vals) {\n" +
                "    result.add(vals.sum())\n" +
                "  }\n" +
                "}\n" +
                "new Test(result, 1)\n" +
                "new Test(result, 2, 3)\n" +
                "new Test(result)\n" +
                "result",
                Arrays.asList(1, 5, null),
                "new Test(ArrayList,Integer)",
                "Integer[].sum()",
                "ArrayList.add(Integer)",
                "new Test(ArrayList,Integer,Integer)",
                "Integer[].sum()",
                "ArrayList.add(Integer)",
                "new Test(ArrayList)",
                "Integer[].sum()",
                "ArrayList.add(null)");
    }

}
