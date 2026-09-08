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
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.codehaus.groovy.runtime.typehandling.GroovyCastException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.runtime.ProxyGeneratorAdapter;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.jvnet.hudson.test.Issue;
import org.kohsuke.groovy.sandbox.impl.GroovyCallSiteSelector;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
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

    public void configureBinding() { }

    /**
     * Use {@code ShouldFail.class} as the expected result for {@link #sandboxedEval} and {@link #unsandboxedEval}
     * when the expression is expected to throw an exception.
     */
    public static final class ShouldFail { }

    @FunctionalInterface
    public interface ExceptionHandler {
        public void handleException(Throwable e) throws Exception;
    }

    /**
     * Executes a Groovy expression inside of the sandbox.
     * @param expression The Groovy expression to execute.
     */
    public void sandboxedEval(String expression, Object expectedResult, ExceptionHandler handler) {
        cr.reset();
        cr.register();
        try {
            configureBinding();
            Object actual = sandboxedSh.evaluate(expression);
            String actualType = GroovyCallSiteSelector.getName(actual);
            String expectedType = GroovyCallSiteSelector.getName(expectedResult);
            ec.checkThat("Sandboxed result (" + actualType + ") does not match expected result (" + expectedType + ")", actual, equalTo(expectedResult));
        } catch (Throwable e) {
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
     */
    private void unsandboxedEval(String expression, Object expectedResult, ExceptionHandler handler) {
        try {
            configureBinding();
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
    public void assertIntercept(String expression, Object expectedReturnValue, String... expectedCalls) {
        assertEvaluate(expression, expectedReturnValue);
        assertIntercepted(expectedCalls);
    }

    /**
     * Check that the most recently executed expression intercepted the expected calls.
     * Automatically adds {@code new Script(Binding)} to the list of intercepted calls.
     * @param expectedCalls The method calls that were expected to be intercepted by the sandbox.
     * @see #assertInterceptedExact
     */
    public void assertIntercepted(String... expectedCalls) {
        // Workaround to avoid having to update all existing tests.
        String[] updatedExpectedCalls = expectedCalls;
        if (expectedCalls.length == 0 || (expectedCalls.length > 0 && !expectedCalls[0].equals("new Script(Binding)"))) {
            updatedExpectedCalls = new String[expectedCalls.length + 1];
            updatedExpectedCalls[0] = "new Script(Binding)";
            System.arraycopy(expectedCalls, 0, updatedExpectedCalls, 1, expectedCalls.length);
        }
        assertInterceptedExact(updatedExpectedCalls);
    }

    /**
     * Check that the most recently executed expression intercepted the expected calls.
     * @param expectedCalls The method calls that were expected to be intercepted by the sandbox.
     */
    public void assertInterceptedExact(String... expectedCalls) {
        String[] interceptedCalls = cr.toString().split("\n");
        if (interceptedCalls.length == 1 && interceptedCalls[0].equals("")) {
            interceptedCalls = new String[0];
        }
        ec.checkThat(interceptedCalls, equalTo(expectedCalls));
    }

    /**
     * Execute a Groovy expression both in and out of the sandbox and check that the return value matches the
     * expected value.
     * @param expression The Groovy expression to execute.
     * @param expectedReturnValue The expected return value for running the script.
     */
    public void assertEvaluate(String expression, Object expectedReturnValue) {
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
        AtomicReference<Throwable> sandboxedException = new AtomicReference<>();
        sandboxedEval(expression, ShouldFail.class, sandboxedException::set);
        AtomicReference<Throwable> unsandboxedException = new AtomicReference<>();
        unsandboxedEval(expression, ShouldFail.class, unsandboxedException::set);
        if (sandboxedException.get() == null || unsandboxedException.get() == null) {
            return; // Either sandboxedEval or unsandboxedEval will have already recorded an error because the result was not ShouldFail.
        }
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

    @Issue({"SECURITY-1465", "SECURITY-3923"})
    @Test public void sandboxWillNotCastNonStandardCollections() throws Exception {
        // Note: If you run this test in a debugger and inspect the proxied closure in Checker#preCheckedCast, the test
        // will probably fail because the debugger will invoke the closure while trying to display the value, which will
        // update the value of i, changing the behavior.

        /* The cast to Collection proxies the Closure to a Collection.
         * The cast to File invokes `new File(... proxied collection's elements as constructor arguments)`.
         * The cast to Object[] reads lines from the File.
         * The trick here is that the closure returns null from the first call to `toArray` in `Checker.preCheckedCast`,
         * so it bypassed the interceptor but still worked correctly in the actual cast before the fix (SECURITY-1465).
         * With the SECURITY-3923 snapshot fix, toArray() is called exactly once and the result is reused for both
         * the pre-check and the cast; if toArray() returns null, we throw IllegalStateException rather than
         * silently skipping the check.
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
                    assertThat(e, instanceOf(IllegalStateException.class));
                    assertThat(e.getMessage(),
                            containsString("toArray() must not return null"));
                });
    }

    @Issue({"SECURITY-1465", "SECURITY-3923"})
    @Test public void sandboxWillCastNonStandardCollectionsWhenToArrayIsConsistent() throws Exception {
        // A closure-backed collection whose toArray() is consistent is now handled safely via snapshot.
        // Previously this was blocked (same as sandboxWillNotCastNonStandardCollections) because there was
        // no way to differentiate it without the snapshot approach. With SECURITY-3923, the sandbox snapshots
        // toArray() once and uses that for both the pre-check and the cast, so consistent collections work.
        sandboxedEval("(({-> return ['secret.txt'] as Object[] } as Collection) as File) as Object[]", ShouldFail.class, e -> {
            // The sandboxed cast of ['secret.txt'] to File intercepts new File(String) (pre-check passes),
            // then File as Object[] tries to read the file — expected to fail since it does not exist.
            assertThat(e, instanceOf(FileNotFoundException.class));
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
                "new A(Properties)",
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

    @Issue("SECURITY-3341")
    @Test public void sandboxBlocksCastingInThisConstructorCalls() throws Exception {
        sandboxedEval(
                "class Subclass {\n" +
                "  def x\n" +
                "  Subclass() { this(['secret.key']) }\n" +
                "  Subclass(File f) { this.x = f }\n" +
                "}\n" +
                "new Subclass().x\n",
                ShouldFail.class,
                e -> assertThat(e.getMessage(), containsString("Unable to find constructor: new Subclass java.util.ArrayList")));
        sandboxedEval(
                "class Subclass {\n" +
                "  def x\n" +
                "  Subclass(File f) { this.x = f }\n" +
                "}\n" +
                "(new Subclass(['secret.key']) { def getFoo() { x } }).foo\n",
                ShouldFail.class,
                e -> assertThat(e.getMessage(), containsString("Unable to find constructor: new Subclass java.util.ArrayList")));
    }

    @Issue("SECURITY-3341")
    @Test public void sandboxBlocksCastingInSuperConstructorCalls() throws Exception {
        sandboxedEval(
                "package com.cloudbees.groovy.cps\n" +
                "class SerializableScript {\n" +
                "  def x\n" +
                "  SerializableScript(File f) { this.x = f }\n" +
                "}\n" +
                "class Subclass extends SerializableScript {\n" +
                "  Subclass() { super(['secret.key']) }" +
                "}\n" +
                "new Subclass().x\n",
                ShouldFail.class,
                e -> assertThat(e.getMessage(), containsString("Unable to find constructor: new com.cloudbees.groovy.cps.SerializableScript java.util.ArrayList")));
        sandboxedEval(
                "package java.lang\n" +
                "class Object {\n" +
                "  def x\n" +
                "  Object(File f) { this.x = f }\n" +
                "}\n" +
                "class Subclass extends Object {\n" +
                "  Subclass() { super(['secret.key']) }" +
                "}\n" +
                "new Subclass().x\n",
                ShouldFail.class,
                e -> assertThat(e.getMessage(), containsString("Prohibited package name: java.lang")));
    }

    @Issue({ "SECURITY-1754", "SECURITY-2824" })
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
        // Calls are blocked even if you manage to obtain a valid wrapper.
        sandboxedEval(
                "class Superclass { Superclass(String x) { } }\n" +
                "class Subclass extends Superclass {\n" +
                "  def wrapper\n" +
                "  Subclass() { super('secret.key'); def $cw = $cw; wrapper = $cw }\n" +
                "}\n" +
                "def wrapper = new Subclass().wrapper\n" +
                "class MyFile extends File {\n" +
                "  MyFile(String path) {\n" +
                "    super(path)\n" +
                "  }\n" +
                "}\n" +
                "new MyFile(wrapper, 'unused')",
                ShouldFail.class,
                e -> assertThat(e.getMessage(), equalTo("Rejecting illegal call to synthetic constructor: private MyFile(org.kohsuke.groovy.sandbox.impl.Checker$SuperConstructorWrapper,java.lang.String). " +
                        "Perhaps you meant to use one of these constructors instead: public MyFile(java.lang.String)")));
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

    @Issue("SECURITY-2824")
    @Test
    public void sandboxInterceptsImplicitCastsMethodReturnValues() {
        assertIntercept(
                "File createFile(String path) {\n" +
                "  [path]\n" +
                "}\n" +
                "createFile('secret.key')\n",
                new File("secret.key"),
                "Script1.createFile(String)",
                "new File(String)");
        assertIntercept(
                "File createFile(String path) {\n" +
                "  return [path]\n" +
                "}\n" +
                "createFile('secret.key')\n",
                new File("secret.key"),
                "Script2.createFile(String)",
                "new File(String)");
    }

    @Issue("SECURITY-2824")
    @Test
    public void sandboxInterceptsImplicitCastsVariableAssignment() {
        assertIntercept(
                "File file\n" +
                "file = ['secret.key']\n " +
                "file",
                new File("secret.key"),
                "new File(String)");
    }

    // https://github.com/jenkinsci/groovy-sandbox/issues/7 would allow these casts to be intercepted here, but for now,
    // we handle them in script-security's SandboxInterceptor
    @Ignore("These casts cannot be intercepted by groovy-sandbox itself without extensive modifications")
    @Issue("SECURITY-2824")
    @Test
    public void sandboxInterceptsImplicitCastsPropertyAndAttributeAssignment() {
        assertIntercept(
                "class Test {\n" +
                "  File file\n" +
                "}\n" +
                "def t = new Test()\n" +
                "t.file = ['secret1.key']\n " +
                "def temp = t.file\n" +
                "t.@file = ['secret2.key']\n " +
                "[temp, t.@file]\n",
                Arrays.asList(new File("secret1.key"), new File("secret2.key")),
                "new Test()",
                "new File(String)",
                "Test.file=File",
                "Test.file",
                "new File(String)",
                "Test.@file=File",
                "Test.@file");
    }

    @Issue("SECURITY-2824")
    @Test
    public void sandboxInterceptsImplicitCastsPropertyAssignmentThisField() {
        assertIntercept(
                "class Test {\n" +
                "  File file\n" +
                "  def setFile(String path) {\n" +
                "    this.file = [path]\n" + // This form of property access is handled as a special case in SandboxTransformer
                "  }\n" +
                "}\n" +
                "def t = new Test()\n" +
                "t.setFile('secret.key')\n " +
                "t.file",
                new File("secret.key"),
                "new Test()",
                "Test.setFile(String)",
                "new File(String)",
                "Test.file");
    }

    @Issue("SECURITY-2824")
    @Test
    public void sandboxInterceptsImplicitCastsArrayAssignment() {
        // Regular Groovy casts the rhs of array assignments to match the component type of the array, but the
        // sandbox does not do this. Ideally the sandbox would have the same behavior as regular Groovy, but the
        // current behavior is safe, which is good enough.
        sandboxedEval(
                "File[] files = [null]\n" +
                "files[0] = ['secret.key']\n" +
                "files[0]",
                ShouldFail.class,
                e -> ec.checkThat(e.toString(), equalTo("java.lang.ArrayStoreException: java.util.ArrayList")));
    }

    @Issue("SECURITY-2824")
    @Test
    public void sandboxInterceptsImplicitCastsInitialParameterExpressions() {
        assertIntercept(
                "def method(File file = ['secret.key']) { file }; method()",
                new File("secret.key"),
                "Script1.method()",
                "new File(String)",
                "Script1.method(File)");
        assertIntercept(
                "({ File file = ['secret.key'] -> file })()",
                new File("secret.key"),
                "Script2$_run_closure1.call()",
                "new File(String)");
        assertIntercept(
                "class Test {\n" +
                "  def x\n" +
                "  Test(File file = ['secret.key']) {\n" +
                "   x = file\n" +
                "  }\n" +
                "}\n" +
                "new Test().x",
                new File("secret.key"),
                "new Test()",
                "new File(String)",
                "new Test(File)",
                "Test.x");
    }

    @Issue("SECURITY-2824")
    @Test
    public void sandboxInterceptsImplicitCastsFields() {
        assertIntercept(
                "class Test {\n" +
                "  File file = ['secret.key']\n" +
                "}\n" +
                "new Test().file",
                new File("secret.key"),
                "new Test()",
                "new File(String)",
                "Test.file");
        assertIntercept(
                "@groovy.transform.Field File file = ['secret.key']\n" +
                "file",
                new File("secret.key"),
                "new File(String)",
                "Script2.file");
    }

    @Issue("SECURITY-2824")
    @Test
    public void sandboxInterceptsElementCastsInArrayCasts() {
        assertIntercept(
                "([['secret.key']] as File[])[0]",
                new File("secret.key"),
                "new File(String)",
                "File[][Integer]");
        assertIntercept(
                "(([['secret.key']] as Object[]) as File[])[0]",
                new File("secret.key"),
                "new File(String)",
                "File[][Integer]");
        assertIntercept(
                "((File[])[['secret.key']])[0]",
                new File("secret.key"),
                "new File(String)",
                "File[][Integer]");
        assertIntercept(
                "((File[])((Object[])[['secret.key']]))[0]",
                new File("secret.key"),
                "new File(String)",
                "File[][Integer]");
        assertIntercept(
                "([[['secret.key']]] as File[][])[0][0]",
                new File("secret.key"),
                "new File(String)",
                "File[][][Integer]",
                "File[][Integer]");
    }

    @Issue("SECURITY-3792")
    @Test
    public void sandboxInterceptsImplicitCastsInForEachLoops() {
        // A typed for-each whose element is a Collection triggers an implicit per-element cast to the
        // loop type; castToType invokes a matching constructor (e.g. ['secret.key'] -> new File(String)).
        // The for-each rewrite routes that cast through the sandbox so the constructor is intercepted.
        assertIntercept(
                "for (File f in [['secret.key']]) { return f }",
                new File("secret.key"),
                "new File(String)");
    }

    @Issue("SECURITY-3792")
    @Test
    public void sandboxInterceptsImplicitCastsInForEachLoopsWithArrayElement() {
        // A typed for-each whose element is an Object[] also triggers an implicit per-element cast to
        // the loop type. The for-each rewrite only routes that cast through the sandbox; the refusal
        // itself is pre-existing Checker behavior (it has never supported array-to-type constructor
        // coercion), so once the cast reaches preCheckedCast it fails fast with
        // UnsupportedOperationException rather than ever invoking new File(String).
        sandboxedEval(
                "for (File f in [['secret.key'] as Object[]]) { return f }",
                ShouldFail.class,
                e -> {
                    assertThat(e, instanceOf(UnsupportedOperationException.class));
                    assertThat(e.getMessage(),
                            containsString("casting arrays to types via constructor is not yet supported"));
                });
    }

    @Issue("SECURITY-3792")
    @Test
    public void sandboxDoesNotAffectLegitimateForEachLoops() {
        // A typed loop whose elements already have the loop variable's type still iterates normally:
        // the rewrite injects a checked cast, but casting an element to a type it already has is a
        // no-op that intercepts nothing.
        assertIntercept(
                "def out = []\n" +
                "for (String s in ['a', 'b']) { out.add(s) }\n" +
                "out",
                Arrays.asList("a", "b"),
                "ArrayList.add(String)",
                "ArrayList.add(String)");
        // Java-style for loops use a dummy variable (not a real declaration) and must be left untouched.
        assertEvaluate(
                "int total = 0\n" +
                "for (int j = 0; j < 3; j++) { total += j }\n" +
                "total",
                3);
    }

    @Issue("SECURITY-3792")
    @Test
    public void sandboxPreservesForEachLoopBodyLabels() {
        // The rewrite wraps the original loop body in a new block (to prepend the checked cast); it
        // nests the body rather than flattening it so a label on the body block, and break/continue
        // targeting that label, keep working. Using a File loop ensures the rewrite actually fires
        // (the intercepted new File(String) proves it), and break lbl on the first element stops
        // after one iteration.
        assertIntercept(
                "def names = []\n" +
                "for (File f in [['a'], ['b']]) lbl: { names.add(f.name); break lbl }\n" +
                "names",
                Arrays.asList("a"),
                "new File(String)",
                "File.name",
                "ArrayList.add(String)");
    }

    @Issue("SECURITY-2824")
    @Test
    public void sandboxUsesCastToTypeForImplicitCasts() {
        assertIntercept(
                "class Test {\n" +
                "  def auditLog = []\n" +
                "  def asType(Class c) {\n" +
                "    auditLog.add('asType')\n" +
                "    'Test.asType'\n" +
                "  }\n" +
                "  String toString() {\n" +
                "    auditLog.add('toString')\n" +
                "    'Test.toString'\n" +
                "  }\n" +
                "}\n" +
                "def t = new Test()\n" +
                "String methodReturnValue(def o) { o }\n" +
                "methodReturnValue(t)\n" +
                "String variable = t\n" +
                "String[] array = [t]\n" +
                "(String)t\n" +
                "t as String\n" + // This is the only cast that should call asType.
                "t.auditLog\n",
                Arrays.asList("toString", "toString", "toString", "toString", "asType"),
                "new Test()",
                "Script1.methodReturnValue(Test)",
                "Test.auditLog",
                "ArrayList.add(String)",
                "Test.auditLog",
                "ArrayList.add(String)",
                "Test.auditLog",
                "ArrayList.add(String)",
                "Test.auditLog",
                "ArrayList.add(String)",
                "Test.auditLog",
                "ArrayList.add(String)",
                "Test.auditLog");
    }

    /** {@code toArray()} returns decoy elements; {@code iterator()} and {@code size()} return real elements. */
    public static final class MaskedList extends ArrayList<Object> {
        private final List<Object> real;

        public MaskedList(List<Object> decoy, List<Object> real) {
            super(decoy);
            this.real = real;
        }

        @Override public int size() { return real.size(); }

        @Override public Iterator<Object> iterator() { return real.iterator(); }
    }

    /** {@code toArray()} returns decoy on the first call, real on subsequent calls. */
    public static final class AlternatingList extends ArrayList<Object> {
        private final List<Object> real;
        private int callCount = 0;

        public AlternatingList(List<Object> decoy, List<Object> real) {
            super(decoy);
            this.real = real;
        }

        @Override
        public Object[] toArray() {
            return callCount++ == 0 ? super.toArray() : real.toArray();
        }
    }

    /** Set-backed variant of AlternatingList: {@code toArray()} returns decoy on first call, real on subsequent calls. */
    public static final class AlternatingHashSet extends LinkedHashSet<Object> {
        private final List<Object> real;
        private int callCount = 0;

        public AlternatingHashSet(List<Object> decoy, List<Object> real) {
            super(decoy);
            this.real = real;
        }

        @Override
        public Object[] toArray() {
            return callCount++ == 0 ? super.toArray() : real.toArray();
        }
    }

    /** Set-backed variant of MaskedList: toArray() returns decoy, iterator()/size() return real. */
    public static final class MaskedHashSet extends LinkedHashSet<Object> {
        private final Object[] decoyArray;
        private final List<Object> real;

        public MaskedHashSet(List<Object> decoy, List<Object> real) {
            super(decoy);
            // Store decoy explicitly: LinkedHashSet inherits AbstractCollection.toArray() which
            // calls this.iterator(), so we must override toArray() to bypass that.
            this.decoyArray = decoy.toArray();
            this.real = real;
        }

        @Override public Object[] toArray() { return decoyArray.clone(); }
        @Override public int size() { return real.size(); }
        @Override public Iterator<Object> iterator() { return real.iterator(); }
    }

    /** SortedSet-backed variant of AlternatingList: toArray() returns decoy on first call, real on subsequent calls. */
    public static final class AlternatingTreeSet extends TreeSet<Object> {
        private final List<Object> real;
        private int callCount = 0;

        public AlternatingTreeSet(List<Object> decoy, List<Object> real) {
            super(decoy);
            this.real = real;
        }

        @Override
        public Object[] toArray() {
            return callCount++ == 0 ? super.toArray() : real.toArray();
        }
    }

    /** SortedSet-backed variant of MaskedList: toArray() returns decoy, iterator()/size() return real. */
    public static final class MaskedTreeSet extends TreeSet<Object> {
        private final Object[] decoyArray;
        private final List<Object> real;

        public MaskedTreeSet(List<Object> decoy, List<Object> real) {
            super(decoy);
            // Store decoy explicitly: TreeSet inherits AbstractCollection.toArray() which
            // calls this.iterator(), so we must override toArray() to bypass that.
            this.decoyArray = decoy.toArray();
            this.real = real;
        }

        @Override public Object[] toArray() { return decoyArray.clone(); }
        @Override public int size() { return real.size(); }
        @Override public Iterator<Object> iterator() { return real.iterator(); }
    }

    /**
     * Plain ArrayList wrapper with no overrides. Used to verify that {@code toArray()} called as
     * plain Java in {@code Checker.preCheckedCast} is invisible to the interceptor log, while the
     * resulting constructor call is visible.
     */
    public static final class SideEffectingCollection extends ArrayList<Object> {
        public SideEffectingCollection(Object... elements) {
            super(Arrays.asList(elements));
        }
    }

    @Issue("SECURITY-3923")
    @Test
    public void sandboxSnapshotsCollectionBeforeConstructorCast() {
        AlternatingList masked = new AlternatingList(
                Arrays.asList("secret.key"),
                Arrays.asList("other.key"));
        binding.setVariable("col", Collections.unmodifiableCollection(masked));
        sandboxedEval("col as File", new File("secret.key"), e -> {
            throw new RuntimeException("Unexpected exception in sandboxed cast", e);
        });
        assertIntercepted("Script1.col", "new File(String)");
    }

    @Issue("SECURITY-3923")
    @Test
    public void sandboxSnapshotsCollectionBeforeArrayCast() {
        Collection<Object> masked = new MaskedList(
                Arrays.asList("secret.key"),
                Arrays.asList("other.key"));
        binding.setVariable("col", Collections.unmodifiableCollection(masked));
        sandboxedEval("(col as File[])[0]", new File("secret.key"), e -> {
            throw new RuntimeException("Unexpected exception in sandboxed cast", e);
        });
        assertIntercepted("Script1.col", "new File(String)", "File[][Integer]");
    }

    @Issue("SECURITY-3923")
    @Test
    public void sandboxSnapshotsMultiElementCollectionBeforeConstructorCast() {
        AlternatingList masked = new AlternatingList(
                Arrays.asList("safe-parent", "safe-child"),
                Arrays.asList("dangerous-parent", "dangerous-child"));
        binding.setVariable("col", Collections.unmodifiableCollection(masked));
        sandboxedEval("col as File", new File("safe-parent", "safe-child"), e -> {
            throw new RuntimeException("TOCTOU attack succeeded: " + e, e);
        });
    }

    @Issue("SECURITY-3923")
    @Test
    public void toArrayNotInterceptedOnCollectionCast() {
        // Checker.preCheckedCast calls toArray() as plain Java, so it never appears in the
        // interceptor log. The cast itself succeeds and new File(String) is intercepted as usual.
        binding.setVariable("col", new SideEffectingCollection("secret.key"));
        sandboxedEval("col as File", new File("secret.key"), e -> {
            throw new RuntimeException("Unexpected exception", e);
        });
        assertIntercepted(
                "Script1.col",
                "new File(String)");
    }

    @Issue("SECURITY-3923")
    @Test
    public void toArrayNotInterceptedOnArrayCast() {
        // Same as toArrayNotInterceptedOnCollectionCast but for the array-cast path.
        binding.setVariable("col", new SideEffectingCollection("secret.key"));
        sandboxedEval("col as File[]", new File[]{new File("secret.key")}, e -> {
            throw new RuntimeException("Unexpected exception", e);
        });
        assertIntercepted(
                "Script1.col",
                "new File(String)");
    }

    /**
     * All three families of JDK delegating wrappers (unmodifiable, synchronized, checked) delegate
     * {@code toArray()} to their backing collection. The snapshot taken in {@code preCheckedCast}
     * must capture the first traversal; the constructor cast must use those elements, not whatever
     * a subsequent traversal of the backing collection would return.
     */
    @Issue("SECURITY-3923")
    @Test
    public void sandboxSnapshotsAllDelegatingWrappersBeforeConstructorCast() {
        List<Object> decoy = Arrays.asList("secret.key");
        List<Object> real  = Arrays.asList("other.key");

        // Backing collection returns decoy on first toArray(), real on subsequent calls.
        // Cast result must equal new File("secret.key") (from the snapshot), not new File("other.key").
        Object[][] wrappers = {
            {"unmodifiableCollection", Collections.unmodifiableCollection(new AlternatingList(decoy, real))},
            {"unmodifiableList",       Collections.unmodifiableList(new AlternatingList(decoy, real))},
            {"unmodifiableSet",        Collections.unmodifiableSet(new AlternatingHashSet(decoy, real))},
            {"unmodifiableSortedSet",  Collections.unmodifiableSortedSet(new AlternatingTreeSet(decoy, real))},
            {"synchronizedCollection", Collections.synchronizedCollection(new AlternatingList(decoy, real))},
            {"synchronizedList",       Collections.synchronizedList(new AlternatingList(decoy, real))},
            {"synchronizedSet",        Collections.synchronizedSet(new AlternatingHashSet(decoy, real))},
            {"synchronizedSortedSet",  Collections.synchronizedSortedSet(new AlternatingTreeSet(decoy, real))},
            {"checkedCollection",      Collections.checkedCollection(new AlternatingList(decoy, real), Object.class)},
            {"checkedList",            Collections.checkedList(new AlternatingList(decoy, real), Object.class)},
            {"checkedSet",             Collections.checkedSet(new AlternatingHashSet(decoy, real), Object.class)},
            {"checkedSortedSet",       Collections.checkedSortedSet(new AlternatingTreeSet(decoy, real), Object.class)},
            // Double-wrap: inner wrapper delegates to outer, outer delegates to backing.
            {"unmodifiable(unmodifiable(evil))", Collections.unmodifiableCollection(
                    Collections.unmodifiableCollection(new AlternatingList(decoy, real)))},
        };

        for (Object[] entry : wrappers) {
            String label = (String) entry[0];
            binding.setVariable("col", entry[1]);
            sandboxedEval("col as File", new File("secret.key"), e -> {
                throw new RuntimeException("Constructor-cast used wrong traversal via " + label + ": " + e, e);
            });
        }
    }

    @Issue("SECURITY-3923")
    @Test
    public void sandboxSnapshotsAllDelegatingWrappersBeforeArrayCast() {
        List<Object> decoy = Arrays.asList("secret.key");
        List<Object> real  = Arrays.asList("other.key");

        // Backing collection's toArray() returns decoy; iterator()/size() return real.
        // Array cast result must equal [new File("secret.key")] (from the snapshot), not [new File("other.key")].
        Object[][] wrappers = {
            {"unmodifiableCollection", Collections.unmodifiableCollection(new MaskedList(decoy, real))},
            {"unmodifiableList",       Collections.unmodifiableList(new MaskedList(decoy, real))},
            {"unmodifiableSet",        Collections.unmodifiableSet(new MaskedHashSet(decoy, real))},
            {"unmodifiableSortedSet",  Collections.unmodifiableSortedSet(new MaskedTreeSet(decoy, real))},
            {"synchronizedCollection", Collections.synchronizedCollection(new MaskedList(decoy, real))},
            {"synchronizedList",       Collections.synchronizedList(new MaskedList(decoy, real))},
            {"synchronizedSet",        Collections.synchronizedSet(new MaskedHashSet(decoy, real))},
            {"synchronizedSortedSet",  Collections.synchronizedSortedSet(new MaskedTreeSet(decoy, real))},
            {"checkedCollection",      Collections.checkedCollection(new MaskedList(decoy, real), Object.class)},
            {"checkedList",            Collections.checkedList(new MaskedList(decoy, real), Object.class)},
            {"checkedSet",             Collections.checkedSet(new MaskedHashSet(decoy, real), Object.class)},
            {"checkedSortedSet",       Collections.checkedSortedSet(new MaskedTreeSet(decoy, real), Object.class)},
            // Double-wrap
            {"unmodifiable(unmodifiable(evil))", Collections.unmodifiableCollection(
                    Collections.unmodifiableCollection(new MaskedList(decoy, real)))},
        };

        for (Object[] entry : wrappers) {
            String label = (String) entry[0];
            binding.setVariable("col", entry[1]);
            sandboxedEval("(col as File[])[0]", new File("secret.key"), e -> {
                throw new RuntimeException("Array-cast used wrong traversal via " + label + ": " + e, e);
            });
        }
    }

    @Issue("SECURITY-3923")
    @Test
    public void sandboxAllowsUserDefinedCollectionInConstructorCast() {
        // Behavior change: non-java.util Collections previously threw UnsupportedOperationException.
        binding.setVariable("custom", new AlternatingList(
                Arrays.asList("secret.key"), Arrays.asList("secret.key")));
        assertIntercept("custom as File", new File("secret.key"), "Script1.custom", "new File(String)");
    }

    @Issue("SECURITY-3923")
    @Test
    public void sandboxAllowsUserDefinedCollectionInArrayCast() {
        // Behavior change: non-java.util Collections previously threw UnsupportedOperationException.
        binding.setVariable("custom", new MaskedList(
                Arrays.asList("secret.key"), Arrays.asList("secret.key")));
        assertIntercept("(custom as File[])[0]", new File("secret.key"),
                "Script1.custom", "new File(String)", "File[][Integer]");
    }

    @Test
    public void sandboxInterceptsAttributeExpressionsInPrefixPostfixOps() {
        assertIntercept(
                "class Test { int x }\n" +
                "def t = new Test()\n" +
                "t.@x++\n" +
                "t.@x\n",
                1,
                "new Test()", "Test.@x", "Integer.next()", "Test.@x=Integer", "Test.@x");
    }

    @Test
    public void sandboxInterceptsEnums() {
        assertIntercept(
                "enum Test { FIRST, SECOND }\n" +
                "Test.FIRST.toString()\n",
                "FIRST",
                // Enum classes are generated before SandboxTransformer runs, so various synthetic constructs are
                // (unnecessarily?) intercepted if you do not define an explicit constructor.
                "Class.FIRST",
                "Test:$INIT(String,Integer)",
                "new LinkedHashMap()",
                "new Test(String,Integer,LinkedHashMap)",
                "new Enum(String,Integer)",
                "LinkedHashMap.equals(null)",
                "ImmutableASTTransformation:checkPropNames(Test,LinkedHashMap)",
                "Test:$INIT(String,Integer)",
                "new LinkedHashMap()",
                "new Test(String,Integer,LinkedHashMap)",
                "new Enum(String,Integer)",
                "LinkedHashMap.equals(null)",
                "ImmutableASTTransformation:checkPropNames(Test,LinkedHashMap)",
                "Class.@FIRST",
                "Class.@SECOND",
                "Class.@FIRST",
                "Class.@SECOND",
                "Test.toString()");
        assertIntercept(
                "enum Test { FIRST(), SECOND(); Test() {} }\n" +
                "Test.FIRST.toString()\n",
                "FIRST",
                // You can define an explicit constructor to simplify the generated code.
                "Class.FIRST",
                "Test:$INIT(String,Integer)",
                "new Enum(String,Integer)",
                "Test:$INIT(String,Integer)",
                "new Enum(String,Integer)",
                "Class.@FIRST",
                "Class.@SECOND",
                "Class.@FIRST",
                "Class.@SECOND",
                "Test.toString()");
    }

    @Test
    public void sandboxInterceptsBooleanCasts() {
        assertIntercept("null as Boolean", null);
        assertIntercept("true as Boolean", true);
        assertIntercept("[:] as Boolean", false,
                "LinkedHashMap.asBoolean()");
        assertIntercept("[] as Boolean", false,
                "ArrayList.asBoolean()");
        assertIntercept("[false] as Boolean", true,
                "ArrayList.asBoolean()");
        assertIntercept("new Object() as Boolean", true,
                "new Object()",
                "Object.asBoolean()");
        assertIntercept("new Object() { boolean asBoolean() { false } } as Boolean", false,
                "new Script7$1(Script7)",
                "Script7$1.@this$0=Script7",
                "Script7$1.asBoolean()");
    }

    @Test
    public void sandboxAllowsBoxedPrimitiveCasts() {
        assertIntercept("1.0 as Integer", 1);
        assertFailsWithSameException("[] as Integer");
        assertIntercepted();
        assertFailsWithSameException("[] as int");
        assertIntercepted();
        assertIntercept("1 as Double", 1.0);
        assertFailsWithSameException("[] as Double");
        assertIntercepted();
        assertFailsWithSameException("[] as double");
        assertIntercepted();
        assertIntercept("'test' as Character", 't');
        assertFailsWithSameException("[] as Character");
        assertIntercepted();
        assertFailsWithSameException("[] as char");
        assertIntercepted();
        assertIntercept("1 as String", "1");
        assertIntercept("[] as String", "[]");
    }

    @Test
    public void sandboxInterceptsCastsToAbstractClasses() throws Throwable {
        // Other tests that generate proxy classes will increment the counter.
        // TODO: Could flake if tests are configured to run in parallel in the same JVM.
        Field pxyCounterField = ProxyGeneratorAdapter.class.getDeclaredField("pxyCounter");
        pxyCounterField.setAccessible(true);
        AtomicLong pxyCounter = (AtomicLong) pxyCounterField.get(null);
        long counter = pxyCounter.get() + 1;
        assertIntercept(
                "def proxy = { -> 'overridden' } as org.kohsuke.groovy.sandbox.SandboxTransformerTest.AbstractClass\n" +
                "[proxy.get(), proxy.get2()]",
                Arrays.asList("overridden", "overridden"),
                "new SandboxTransformerTest$AbstractClass()",
                "SandboxTransformerTest$AbstractClass" + counter + "_groovyProxy.get()",
                "SandboxTransformerTest$AbstractClass" + counter + "_groovyProxy.get2()");
        counter = pxyCounter.get() + 1;
        assertIntercept(
                "def proxy = ['get': { -> 'overridden' }] as org.kohsuke.groovy.sandbox.SandboxTransformerTest.AbstractClass\n" +
                "[proxy.get(), proxy.get2()]",
                Arrays.asList("overridden", "default"),
                "new SandboxTransformerTest$AbstractClass()",
                "SandboxTransformerTest$AbstractClass" + counter + "_groovyProxy.get()",
                "SandboxTransformerTest$AbstractClass" + counter + "_groovyProxy.get2()");
    }

    public static abstract class AbstractClass {
        public abstract Object get();
        public Object get2() {
            return "default";
        }
        public abstract void thisMethodExistsToAvoidCodePathsForSingleAbstractMethodClasses();
    }

    @Test
    public void sandboxDoesNotMutateReturnStatementConstant() {
        sandboxedEval("", null, null);
        sandboxedEval("", null, null);
        unsandboxedEval("", null, ec::addError);
    }

    @Issue("JENKINS-69899")
    @Test
    public void sandboxDoesNotCastEmptyExpression() {
        assertIntercept("@groovy.transform.Field String x", null);
        assertIntercept("@groovy.transform.Field String x; x = null", null);
    }

    @Test
    public void sandboxSupportsConstructorsWithVarArgs() throws Exception {
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

    @Test
    public void sandboxSupportsReturnStatementsInClosures() throws Exception {
        assertIntercept(
                "@groovy.transform.Field\n" +
                "private static final field = [\n" +
                "  key: { x -> return x == 123 }\n" +
                "]\n" +
                "field.key(123)\n",
                true,
                "Script1.field",
                "LinkedHashMap.key(Integer)",
                "Integer.compareTo(Integer)");
        assertIntercept(
                "File method() {\n" +
                "  def field = [\n" +
                "    key: { x -> return x }\n" +
                "  ]\n" +
                "  result = field.key(['secret.key'])\n" +
                "  null\n" +
                "}\n" +
                "method()\n" +
                "result",
                Arrays.asList("secret.key"),
                "Script2.method()",
                "LinkedHashMap.key(ArrayList)",
                "Script2.result=ArrayList",
                "Script2.result");
    }

    @Test public void closureVariablesInLoopExpressions() throws Exception {
        assertIntercept(
                "for (int x = 0; ({s -> s})(true); x++) {\n" +
                "    return true\n" +
                "}\n" +
                "return false\n",
                true,
                "Script1$_run_closure1.call(Boolean)");
        assertIntercept(
                "while (({s -> s})(true)) {\n" +
                "    return true\n" +
                "}\n" +
                "return false\n",
                true,
                "Script2$_run_closure1.call(Boolean)");
        assertIntercept(
                "while (({it})(true)) {\n" +
                "    return true\n" +
                "}\n" +
                "return false\n",
                true,
                "Script3$_run_closure1.call(Boolean)");
    }

    @Test public void forLoopDummyParameterIsNotDeclared() {
        assertFailsWithSameException(
                "for (int i = 0; i < 1; i++) {\n" +
                "  println(forLoopDummyParameter)\n" +
                "}\n");
    }

    @Test
    public void sandboxSupportsFinalFields() {
        assertIntercept(
                "class Test {\n" +
                "  final String p\n" +
                "  Test() {\n" +
                "    p = 'value'\n" +
                "  }\n" +
                "}\n" +
                "new Test().p",
                "value",
                // Intercepted operations:
                "new Test()",
                "Test.p");
    }

    @Test
    public void sandboxDoesNotRecurseInfinitelyInSetters() {
        assertIntercept(
                "class Test {\n" +
                "  String prop\n" +
                "  def setProp(newProp) {\n" +
                "    prop = newProp\n" +
                "    prop\n" +
                "  }\n" +
                "}\n" +
                "new Test().prop = 'value'",
                "value",
                // Intercepted operations:
                "new Test()",
                "Test.prop=String",
                "Test.prop");
    }

    @Test
    public void sandboxDoesNotPerformImplicitCastsForOverloadedOperators() {
        // groovy.lang.MissingMethodException: No signature of method: OverridePlus.plus() is applicable for argument types: (java.util.ArrayList) values: [[secret.key]]
        assertFailsWithSameException(
                "class OverridePlus {\n" +
                "  def file\n" +
                "  OverridePlus plus(File file) {\n" +
                "    this.file = file\n" +
                "    this\n" +
                "  }\n" +
                "}\n" +
                "new OverridePlus() + ['secret.key']\n");
    }

    @Issue("JENKINS-70080")
    @Test
    public void sandboxSupportsCompoundAssignmentsToFields() throws Throwable {
        assertIntercept(
                "class Test {\n" +
                "  def map = [:]\n" +
                "  def add(newMap) {\n" +
                "    map += newMap\n" +
                "    map\n" +
                "  }\n" +
                "}\n" +
                "new Test().add([k: 'v'])\n",
                Collections.singletonMap("k", "v"),
                // Intercepted operations:
                "new Test()",
                "Test.add(LinkedHashMap)",
                "LinkedHashMap.plus(LinkedHashMap)",
                "Test.map");
        assertIntercept(
                "class Test {\n" +
                "  final Map map = [:]\n" +
                "  Test(newMap) {\n" +
                "    map += newMap\n" + // Groovy is more lenient with 'final' than Java
                "  }\n" +
                "}\n" +
                "new Test([k: 'v']).map\n",
                Collections.singletonMap("k", "v"),
                // Intercepted operations:
                "new Test(LinkedHashMap)",
                "LinkedHashMap.plus(LinkedHashMap)",
                "Test.map");
        assertFailsWithSameException(
                "class Test {\n" +
                "  final Map map\n" +
                "  Test(newMap) {\n" +
                "    map += newMap\n" + // java.lang.NullPointerException: Cannot execute null+{}
                "  }\n" +
                "}\n" +
                "new Test([:]).map\n");
    }

    @Issue("SECURITY-3931")
    @Test
    public void nullReceiverCallsShouldBeIntercepted() {
        sandboxedEval("def x = null; x.someMethod()", ShouldFail.class, e -> ec.checkThat(e.toString(), equalTo("java.lang.NullPointerException: Cannot invoke method someMethod() on null object")));
        assertIntercepted("NullObject.someMethod()");

        sandboxedEval("def x = null; x.call()", ShouldFail.class, e -> ec.checkThat(e.toString(), equalTo("java.lang.NullPointerException: Cannot invoke method call() on null object")));
        assertIntercepted("NullObject.call()");

        sandboxedEval("def x = null; x.someProp", ShouldFail.class, e -> ec.checkThat(e.toString(), equalTo("java.lang.NullPointerException: Cannot get property 'someProp' on null object")));
        assertIntercepted("null.someProp");

        sandboxedEval("def x = null; x.someProp = 1", ShouldFail.class, e -> ec.checkThat(e.toString(), equalTo("java.lang.NullPointerException: Cannot set property 'someProp' on null object")));
        assertIntercepted("null.someProp=Integer");

        sandboxedEval("def x = null; x[0]", ShouldFail.class, e -> ec.checkThat(e.toString(), equalTo("java.lang.NullPointerException: Cannot invoke method getAt() on null object")));
        assertIntercepted("null[Integer]");

        sandboxedEval("def x = null; x[0] = 1", ShouldFail.class, e -> ec.checkThat(e.toString(), equalTo("java.lang.NullPointerException: Cannot invoke method putAt() on null object")));
        assertIntercepted("null[Integer]=Integer");

        sandboxedEval("def x = null; x.@someAttr", ShouldFail.class, e -> ec.checkThat(e.toString(), equalTo("groovy.lang.MissingFieldException: No such field: someAttr for class: org.codehaus.groovy.runtime.NullObject")));
        assertIntercepted("null.@someAttr");

        sandboxedEval("def x = null; x.@someAttr = 1", ShouldFail.class, e -> ec.checkThat(e.toString(), equalTo("groovy.lang.MissingFieldException: No such field: someAttr for class: org.codehaus.groovy.runtime.NullObject")));
        assertIntercepted("null.@someAttr=Integer");

        // NullObject defines real methods (e.g. asBoolean() for boolean coercion) — verify they still
        // return the correct value after null receivers are routed through the interceptor chain.
        assertEvaluate("def x = null; x.asBoolean()", false);
        assertEvaluate("def x = null; x ? 'yes' : 'no'", "no");
    }

    // Base with an inherited dangerous overload
    public static class InheritedOverloadBase {
        public static final AtomicBoolean STATIC_OVERLOAD_INVOKED = new AtomicBoolean();
        public static Object overloaded(List command) {
            STATIC_OVERLOAD_INVOKED.set(true);
            return "list-overload";
        }
    }

    public static class InstanceOverloadBase {
        public static final AtomicBoolean INSTANCE_OVERLOAD_INVOKED = new AtomicBoolean();
        public Object overloaded(List command) {
            INSTANCE_OVERLOAD_INVOKED.set(true);
            return "instance-list-overload";
        }
    }

    @Issue("SECURITY-3929")
    @Test public void interfaceCoercionCannotReachUncheckedInheritedOverload() throws Exception {
        InheritedOverloadBase.STATIC_OVERLOAD_INVOKED.set(false);
        // The precheck only vets the harmless overloaded(Integer), the coercion must be rejected rather than let the
        // proxy dispatch to the inherited overloaded(List) unchecked.
        sandboxedEval(
                "class Facade extends SandboxTransformerTest.InheritedOverloadBase {\n" +
                "  static Object overloaded(Integer command) { return 'harmless' }\n" +
                "}\n" +
                "interface Coerced { Object overloaded(Object command) }\n" +
                "def proxy = Facade as Coerced\n" +
                "proxy.overloaded(['echo', 'hi'])\n",
                ShouldFail.class,
                e -> {
                    assertThat(e, instanceOf(SecurityException.class));
                    assertThat(e.getMessage(), containsString("has an inherited overload"));
                });
        assertFalse(InheritedOverloadBase.STATIC_OVERLOAD_INVOKED.get());
    }

    public static abstract class AbstractWithInheritedOverload extends InstanceOverloadBase {
        public abstract Object method1();
        public abstract Object method2();
    }

    @Issue("SECURITY-3929")
    @Test public void abstractClassCoercionCannotUseInstanceAsSource() throws Exception {
        // Abstract-class coercion only accepts Closure/Map as source.
        // A plain instance is rejected by Groovy itself with GroovyCastException before the sandbox
        // pre-check is involved, so no inherited-overload mismatch is possible via this path.
        sandboxedEval(
                "def obj = new SandboxTransformerTest.InstanceOverloadBase()\n" +
                "obj as SandboxTransformerTest.AbstractWithInheritedOverload\n",
                ShouldFail.class,
                e -> assertThat(e, instanceOf(GroovyCastException.class)));
    }

    @Issue("SECURITY-3929")
    @Test public void interfaceCoercionInstanceReceiverCanReachUncheckedInheritedOverload() throws Exception {
        InstanceOverloadBase.INSTANCE_OVERLOAD_INVOKED.set(false);
        sandboxedEval(
                "class InstanceFacade extends SandboxTransformerTest.InstanceOverloadBase {\n" +
                "  Object overloaded(Integer command) { return 'harmless' }\n" +
                "}\n" +
                "interface Coerced { Object overloaded(Object command) }\n" +
                "def obj = new InstanceFacade()\n" +
                "def proxy = obj as Coerced\n" +
                "proxy.overloaded(['echo', 'hi'])\n",
                ShouldFail.class,
                e -> {
                    assertThat(e, instanceOf(SecurityException.class));
                    assertThat(e.getMessage(), containsString("has an inherited overload"));
                });
        assertFalse(InstanceOverloadBase.INSTANCE_OVERLOAD_INVOKED.get());
    }

}
