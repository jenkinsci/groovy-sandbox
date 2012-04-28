package test

import junit.framework.TestCase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class TheTest extends TestCase {
    def sh;

    void setUp() {
        def binding = new Binding()
        binding.foo = "FOO"
        binding.bar = "BAR"
        binding.zot = 5

        def cc = new CompilerConfiguration()
        cc.addCompilationCustomizers(new SecureTransformer())
        sh = new GroovyShell(binding,cc)

    }

    def eval(String expression) {
        try {
            return sh.evaluate(expression)
        } catch (Exception e) {
            throw new Exception("Failed to evaluate "+expression,e)
        }
    }

    void testOK() {
        eval("5.class.forName('java.lang.String')")
        assertEquals("foo".hashCode(),eval("'foo'.toString().hashCode()"))
        assertEquals(Math.max(1f,2f),eval("Math.max(1f,2f)"))
        assertEquals(Math.max(1f,2f),eval("import static java.lang.Math.*; max(1f,2f)"))
    }
}
