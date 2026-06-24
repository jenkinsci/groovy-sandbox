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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ErrorCollector;
import org.kohsuke.groovy.sandbox.impl.GroovyCallSiteSelector;
import org.hamcrest.Matcher;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Abstract base class for sandbox testing that provides common utility methods and setup code.
 */
public abstract class AbstractSandboxTest {
    public @Rule ErrorCollector ec = new ErrorCollector();
    public Binding binding = new Binding();
    public GroovyShell sandboxedSh;
    public GroovyShell unsandboxedSh;
    public ClassRecorder cr = new ClassRecorder();

    @Before
    public void setUp() {
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new ImportCustomizer().addImports(getClass().getName()).addStarImports("org.kohsuke.groovy.sandbox"));
        cc.addCompilationCustomizers(new SandboxTransformer());
        sandboxedSh = new GroovyShell(binding, cc);

        cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new ImportCustomizer().addImports(getClass().getName()).addStarImports("org.kohsuke.groovy.sandbox"));
        unsandboxedSh = new GroovyShell(binding, cc);
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
    protected void unsandboxedEval(String expression, Object expectedResult, ExceptionHandler handler) {
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
    @SuppressWarnings("unchecked")
    public void assertInterceptedExact(String... expectedCalls) {
        String[] interceptedCalls = cr.toString().split("\n");
        if (interceptedCalls.length == 1 && interceptedCalls[0].equals("")) {
            interceptedCalls = new String[0];
        }

        // Create matchers for each expected call with flexible proxy matching
        Matcher<String>[] matchers = new Matcher[expectedCalls.length];
        for (int i = 0; i < expectedCalls.length; i++) {
            matchers[i] = createFlexibleCallMatcher(expectedCalls[i]);
        }

        ec.checkThat("Actual calls: " + Arrays.toString(interceptedCalls), interceptedCalls, arrayContaining(matchers));
    }

    /**
     * Creates a matcher that can handle flexible matching for proxy class names.
     * For calls containing _groovyProxy, it matches the pattern ignoring numeric IDs before _groovyProxy.
     * For other calls, it matches exactly.
     */
    private Matcher<String> createFlexibleCallMatcher(String expectedCall) {
        if (expectedCall.contains("_groovyProxy")) {
            String[] parts = expectedCall.split("_groovyProxy", 2);
            String prefixWithNumber = parts[0];
            String suffix = "_groovyProxy" + parts[1];
            String prefix = prefixWithNumber.replaceAll("\\d+$", "");
            String pattern = Pattern.quote(prefix) + "\\d+" + Pattern.quote(suffix);
            return matchesPattern(pattern);
        }
        return equalTo(expectedCall);
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
    protected void assertFailsWithSameException(String expression) {
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
}
