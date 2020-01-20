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

    private void isolate(Runnable r) {
        ec.checkSucceeds(() -> {
            try {
                r.run();
                return null;
            } finally {
                cr.reset();
            }
        });
    }

    /**
     * Executes a Groovy expression inside of the sandbox.
     * @param expression The Groovy expression to execute.
     * @return the result of executing the expression.
     */
    private Object sandboxedEval(String expression) {
        cr.register();
        try {
            return sandboxedSh.evaluate(expression);
        } catch (Exception e) {
            throw new RuntimeException("Failed to evaluate " + expression, e);
        } finally {
            cr.unregister();
        }
    }

    /**
     * Executes a Groovy expression inside and outside of the sandbox and checks that the result matches.
     * @param expression The Groovy expression to execute.
     * @return the result of executing the expression in the sandbox.
     */
    private Object dualEval(String expression) {
        Object actual = sandboxedEval(expression);
        Object expected = unsandboxedSh.evaluate(expression);
        assertThat("Sandboxed return value does not match unsandboxed return value", actual, equalTo(expected));
        return actual;
    }

    /**
     * Execute a Groovy expression and check that the return value matches the expected value and that the given
     * list of method calls are intercepted by the sandbox.
     * @param expression The Groovy expression to execute.
     * @param expectedReturnValue The expected return value for running the script.
     * @param expectedCalls The method calls that are expected to be intercepted by the sandbox.
     */
    private void assertIntercept(String expression, Object expectedReturnValue, String... expectedCalls) {
        Object actual = dualEval(expression);
        assertThat(actual, equalTo(expectedReturnValue));
        assertThat(cr.toString().split("\n"), equalTo(expectedCalls));
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
        try {
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
                    "} as Collection) as File) as Object[]");
            fail("Expression hould have failed to execute");
        } catch(Exception e) {
            assertThat(e.getCause(), instanceOf(UnsupportedOperationException.class));
            assertThat(e.getCause().getMessage(),
                    containsString("Casting non-standard Collections to a type via constructor is not supported."));
        }
    }

    @Issue("SECURITY-1465")
    @Test public void sandboxWillNotCastNonStandardCollectionsEvenIfHarmless() throws Exception {
        try {
            // Not problematic even before the fix because it is consistent, but there is no good way to differentiate
            // between this and the expression in sandboxWillNotCastNonStandardCollections, so they both get blocked.
            sandboxedEval("(({-> return ['secret.txt'] as Object[] } as Collection) as File) as Object[]");
            fail("Expression hould have failed to execute");
        } catch(Exception e) {
            assertThat(e.getCause(), instanceOf(UnsupportedOperationException.class));
            assertThat(e.getCause().getMessage(),
                    containsString("Casting non-standard Collections to a type via constructor is not supported."));
        }
    }

    @Issue("SECURITY-1465")
    @Test public void sandboxWillCastStandardCollections() throws Exception {
        Path secret = Paths.get("secret.txt");
        try {
            Files.write(secret, Arrays.asList("secretValue"));
            isolate(() -> assertIntercept(
                    "(Arrays.asList('secret.txt') as File) as Object[]",
                    new String[]{"secretValue"},
                    "Arrays:asList(String)",
                    "new File(String)",
                    "ResourceGroovyMethods:readLines(File)"));
            isolate(() -> assertIntercept(
                    "(Collections.singleton('secret.txt') as File) as Object[]",
                    new String[]{"secretValue"},
                    "Collections:singleton(String)",
                    "new File(String)",
                    "ResourceGroovyMethods:readLines(File)"));
            isolate(() -> assertIntercept(
                    "(new ArrayList<>(Arrays.asList('secret.txt')) as File) as Object[]",
                    new String[]{"secretValue"},
                    "Arrays:asList(String)",
                    "new ArrayList(Arrays$ArrayList)",
                    "new File(String)",
                    "ResourceGroovyMethods:readLines(File)"));
            isolate(() -> assertIntercept(
                    "(new HashSet<>(Arrays.asList('secret.txt')) as File) as Object[]",
                    new String[]{"secretValue"},
                    "Arrays:asList(String)",
                    "new HashSet(Arrays$ArrayList)",
                    "new File(String)",
                    "ResourceGroovyMethods:readLines(File)"));
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

    @Test public void closureVariables() throws Exception {
        isolate(() -> assertIntercept(
                "while ([false].stream().noneMatch({s -> s})) {\n" +
                "    return true\n" +
                "}\n" +
                "return false\n",
                true,
                "ArrayList.stream()", "ReferencePipeline$Head.noneMatch(Script1$_run_closure1)"));
        isolate(() -> assertIntercept(
                "while ([false].stream().noneMatch({it})) {\n" +
                "    return true\n" +
                "}\n" +
                "return false\n",
                true,
                "ArrayList.stream()", "ReferencePipeline$Head.noneMatch(Script2$_run_closure1)"));
    }

}
