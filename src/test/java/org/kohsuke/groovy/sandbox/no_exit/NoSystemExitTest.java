package org.kohsuke.groovy.sandbox.no_exit;

import groovy.lang.GroovyShell;
import junit.framework.TestCase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.kohsuke.groovy.sandbox.SandboxTransformer;

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
public class NoSystemExitTest extends TestCase {
    GroovyShell sh;
    NoSystemExitSandbox sandbox = new NoSystemExitSandbox();

    @Override
    protected void setUp() {
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new SandboxTransformer());
        sh = new GroovyShell(cc);
        sandbox.register();
    }

    @Override
    protected void tearDown() {
        sandbox.unregister();
    }

    void assertFail(String script) {
        try {
            sh.evaluate(script);
            fail("Should have failed");
        } catch (SecurityException e) {
            // as expected
        }
    }

    void eval(String script) {
        sh.evaluate(script);
    }

    public void test1() {
        assertFail("System.exit(-1)");
        assertFail("foo(System.exit(-1))");
        assertFail("System.exit(-1)==System.exit(-1)");
        assertFail("def x=System.&exit; x(-1)");

        // but this should be OK
        eval("System.getProperty('abc')");
    }
}
