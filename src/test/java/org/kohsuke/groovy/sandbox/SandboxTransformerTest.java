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
import groovy.lang.EmptyRange;
import groovy.lang.GroovyShell;
import groovy.lang.IntRange;
import groovy.lang.ObjectRange;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.jvnet.hudson.test.Issue;
import org.kohsuke.groovy.sandbox.impl.GroovyCallSiteSelector;

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
            String actualType = GroovyCallSiteSelector.getName(actual);
            String expectedType = GroovyCallSiteSelector.getName(expectedResult);
            ec.checkThat("Sandboxed result (" + actualType + ") does not match expected result (" + expectedType + ")", actual, equalTo(expectedResult));
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
            String actualType = GroovyCallSiteSelector.getName(actual);
            String expectedType = GroovyCallSiteSelector.getName(expectedResult);
            ec.checkThat("Unsandboxed result (" + actualType + ") does not match expected result (" + expectedType + ")", actual, equalTo(expectedResult));
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
        assertEvaluate(expression, expectedReturnValue);
        ec.checkThat(cr.toString().split("\n"), equalTo(expectedCalls));
    }

    /**
     * Execute a Groovy expression both in and out of the sandbox and check that the return value matches the
     * expected value.
     * @param expression The Groovy expression to execute.
     * @param expectedReturnValue The expected return value for running the script.
     */
    private void assertEvaluate(String expression, Object expectedReturnValue) {
        sandboxedEval(expression, expectedReturnValue, e -> {
            throw new RuntimeException("Failed to evaluate sandboxed expression: " + expression, e);
        });
        unsandboxedEval(expression, expectedReturnValue, e -> {
            throw new RuntimeException("Failed to evaluate unsandboxed expression: " + expression, e);
        });
    }

    /**
     * Execute a Groovy expression both in and out of the sandbox and check that the script throws an exception with
     * the same class and message in both cases.
     * @param expression The Groovy expression to execute.
     */
    private void assertFailsWithSameException(String expression) {
        AtomicReference<Exception> sandboxedException = new AtomicReference<>();
        sandboxedEval(expression, ShouldFail.class, sandboxedException::set);
        AtomicReference<Exception> unsandboxedException = new AtomicReference<>();
        unsandboxedEval(expression, ShouldFail.class, unsandboxedException::set);
        ec.checkThat("Sandboxed and unsandboxed exception should have the same type",
                unsandboxedException.get().getClass(), equalTo(sandboxedException.get().getClass()));
        ec.checkThat("Sandboxed and unsandboxed exception should have the same message",
                unsandboxedException.get().getMessage(), equalTo(sandboxedException.get().getMessage()));
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

    @Test public void equalsAndHashCode() throws Exception {
        assertIntercept(
                "@groovy.transform.EqualsAndHashCode\n" +
                "class C {\n" +
                "    def prop\n" +
                "    def getProp() {\n" +
                "        System.setProperty('x', 'y')\n" +
                "        'foo'\n" +
                "    }\n" +
                "}\n" +
                "[new C().equals(new C()), new C().hashCode()]\n",
                Arrays.asList(true, 105511),
                "new C()",
                "new C()",
                "C.equals(C)",
                "C.equals(null)",
                "C.is(C)",
                "C.canEqual(C)",
                "C.getProp()",
                "System:setProperty(String,String)",
                "C.getProp()",
                "System:setProperty(String,String)",
                "String.compareTo(String)",
                "new C()",
                "C.hashCode()",
                "HashCodeHelper:initHash()",
                // `getProp` is called twice by the generated `hashCode` method. The first call is used to prevent cycles in case it returns `this`.
                "C.getProp()",
                "System:setProperty(String,String)",
                "String.is(C)",
                "C.getProp()",
                "System:setProperty(String,String)",
                "HashCodeHelper:updateHash(Integer,String)");
    }

    @Test public void sandboxInterceptsUnaryOperatorExpressions() {
        assertIntercept(
                "def auditLog = []\n" +
                "def o = new SandboxTransformerTest.OperatorOverloader(auditLog, 2)\n" +
                "[-o, +o, ~o, *auditLog]",
                Arrays.asList(-2, 2, ~2, "negative", "positive", "bitwiseNegate"),
                "new SandboxTransformerTest$OperatorOverloader(ArrayList,Integer)",
                "SandboxTransformerTest$OperatorOverloader.negative()",
                "SandboxTransformerTest$OperatorOverloader.positive()",
                "SandboxTransformerTest$OperatorOverloader.bitwiseNegate()");
    }

    @Test public void sandboxInterceptsRangeExpressions() {
        assertIntercept(
                "def auditLog = []\n" +
                "def range = new SandboxTransformerTest.OperatorOverloader(auditLog, 1)..<(new SandboxTransformerTest.OperatorOverloader(auditLog, 4))\n" +
                "def result = []\n" +
                "for (o in range) { result.add(o.value) }\n" +
                "result.addAll(auditLog)\n" +
                "result\n",
                // These are the calls that actually happened at runtime.
                Arrays.asList(1, 2, 3, "compareTo", "compareTo", "previous", "compareTo", "compareTo", "next", "compareTo", "compareTo", "next", "compareTo", "compareTo", "next", "compareTo", "compareTo", "next", "next"),
                "new SandboxTransformerTest$OperatorOverloader(ArrayList,Integer)",
                "new SandboxTransformerTest$OperatorOverloader(ArrayList,Integer)",
                // These next 10 interceptions are from Checker.checkedRange and Checker.checkedComparison.
                "SandboxTransformerTest$OperatorOverloader.compareTo(SandboxTransformerTest$OperatorOverloader)",
                "SandboxTransformerTest$OperatorOverloader.compareTo(SandboxTransformerTest$OperatorOverloader)",
                "SandboxTransformerTest$OperatorOverloader.previous()",
                "SandboxTransformerTest$OperatorOverloader.compareTo(null)",
                "SandboxTransformerTest$OperatorOverloader.next()",
                "SandboxTransformerTest$OperatorOverloader.previous()",
                "SandboxTransformerTest$OperatorOverloader.compareTo(null)",
                "SandboxTransformerTest$OperatorOverloader.next()",
                "SandboxTransformerTest$OperatorOverloader.previous()",
                "SandboxTransformerTest$OperatorOverloader.value",
                "ArrayList.add(Integer)",
                "SandboxTransformerTest$OperatorOverloader.value",
                "ArrayList.add(Integer)",
                "SandboxTransformerTest$OperatorOverloader.value",
                "ArrayList.add(Integer)",
                "ArrayList.addAll(ArrayList)");
    }

    @Test public void unaryExpressionsSmoke() {
        // Bitwise negate
        assertEvaluate("~1", ~1);
        assertEvaluate("~2L", ~2L);
        assertEvaluate("~BigInteger.valueOf(3L)", BigInteger.valueOf(3L).not());
        assertEvaluate("(~'test').matcher('test').matches()", true); // Pattern does not override equals or hashcode.
        assertEvaluate("(~\"tes${'t'}\").matcher('test').matches()", true); // Pattern does not override equals or hashcode.
        assertEvaluate("~[1, 2L]", Arrays.asList(~1, ~2L));
        // Unary minus
        assertEvaluate("-1", -1);
        assertEvaluate("-2L", -2L);
        assertEvaluate("-BigInteger.valueOf(3L)", BigInteger.valueOf(3L).negate());
        assertEvaluate("-4.1", BigDecimal.valueOf(4.1).negate());
        assertEvaluate("-5.2d", -5.2);
        assertEvaluate("-6.3f", -6.3f);
        assertEvaluate("-(short)7", (short)(-7));
        assertEvaluate("-(byte)8", (byte)(-8));
        assertEvaluate("-[1, 2L, 6.3f]", Arrays.asList(-1, -2L, -6.3f));
        // Unary plus
        assertEvaluate("+1", 1);
        assertEvaluate("+2L", 2L);
        assertEvaluate("+BigInteger.valueOf(3L)", BigInteger.valueOf(3L));
        assertEvaluate("+4.1", BigDecimal.valueOf(4.1));
        assertEvaluate("+5.2d", 5.2);
        assertEvaluate("+6.3f", 6.3f);
        assertEvaluate("+(short)7", (short)7);
        assertEvaluate("+(byte)8", (byte)8);
        assertEvaluate("+[1, 2L, 6.3f]", Arrays.asList(1, 2L, 6.3f));
    }

    @Test
    public void rangeExpressionsSmoke() {
        assertEvaluate("1..3", new IntRange(true, 1, 3));
        assertEvaluate("1..<3", new IntRange(false, 1, 3));
        assertEvaluate("'a'..'c'", new ObjectRange('a', 'c'));
        assertEvaluate("'a'..<'c'", new ObjectRange('a', 'b'));
        assertEvaluate("'a'..<'a'", new EmptyRange('a'));
        assertEvaluate("1..<1", new EmptyRange(1));
        assertEvaluate("'A'..67", new IntRange(true, 65, 67));
        assertEvaluate("'a'..'ab'", new ObjectRange("a", "ab"));
        assertEvaluate("'ab'..'a'", new ObjectRange("ab", "a"));
        // Checking consistency in error messages.
        assertFailsWithSameException("'a'..67");
        assertFailsWithSameException("null..1");
        assertFailsWithSameException("1..null");
        assertFailsWithSameException("null..null");
        assertFailsWithSameException("1..'abc'");
        assertFailsWithSameException("'abc'..1");
        assertFailsWithSameException("(new Object())..1");
        assertFailsWithSameException("1..(new Object())");
    }

    private static class OperatorOverloader implements Comparable<OperatorOverloader> {
        private final List<String> auditLog;
        private final int value;

        private OperatorOverloader(List<String> auditLog, int value) {
            this.auditLog = auditLog;
            this.value = value;
        }

        public int negative() {
            auditLog.add("negative");
            return -value;
        }

        public int positive() {
            auditLog.add("positive");
            return value;
        }

        public int bitwiseNegate() {
            auditLog.add("bitwiseNegate");
            return  ~value;
        }

        @Override
        public int compareTo(OperatorOverloader other) {
            auditLog.add("compareTo");
            return Integer.compare(value, other.value);
        }

        public OperatorOverloader next() {
            auditLog.add("next");
            return new OperatorOverloader(auditLog, value + 1);
        }

        public OperatorOverloader previous() {
            auditLog.add("previous");
            return new OperatorOverloader(auditLog, value - 1);
        }
    }

}
