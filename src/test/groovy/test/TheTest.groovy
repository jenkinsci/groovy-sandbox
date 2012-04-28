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
        cc.addCompilationCustomizers(new GroovySecureFilterExpressionChecker())
        sh = new GroovyShell(binding,cc)

    }

    def eval(String expression) {
        try {
            use (BooleanCategory) {
                return sh.evaluate(expression)
            }
        } catch (Exception e) {
            throw new Exception("Failed to compile "+expression,e)
        }
    }

    def assertRejectedExpression(String expectedMsg, String script) {
        try {
            sh.evaluate(script)
            fail("${script} should have failed to compile")
        } catch(MultipleCompilationErrorsException e) {
            if (e.message.contains(expectedMsg))
                return; // as expected
            throw new Exception("${script} failed to compile but for unexpected reason",e);
        }
    }

    void testOK() {
        assertEquals(true,eval("foo=='FOO'"))
        assertEquals(true,eval("foo=='FOO' && bar!='BBB'"))
        assertEquals(true,eval("true.implies(true)"))
        assertEquals(true,eval("(foo=='FOO').implies(true)"))
        assertEquals(true,eval("['FOO','BAR'].contains(foo)"))
        assertEquals(true,eval("zot<10"))
        assertEquals(true,eval("(foo =~ /FOO/) as boolean"))
        assertEquals(true,eval("zot%3==2"))
//        eval("5.class.\"forName\"(\"java.lang.String\")")
    }

    void testReject() {
        assertRejectedExpression("Importing [java.lang.System]",        "System.out.println('bad')")
        assertRejectedExpression("Importing [java.lang.System]",        "System.exit(0)")
        assertRejectedExpression("Importing [jenkins.model.Jenkins]",   "jenkins.model.Jenkins.instance")
        assertRejectedExpression("Importing [java.net.URLClassLoader]", "new java.net.URLClassLoader()")
        assertRejectedExpression("Importing [java.lang.Class]",         "Class.forName('java.lang.String')")
//        assertRejectedExpression("Importing [java.lang.Object]",         "'foo'.class.forName('java.lang.String')")
        assertRejectedExpression("Importing [java.lang.System]",         "System.methods.find { it.name=='exit' }.invoke(null,1)")
        assertRejectedExpression("Importing [java.lang.Object]",         "x=5.class.class; x.getMethod('forName',String.class).invoke(null,'java.lang.String')")
    }
    /*

        The problem is that this check is compile time, when Groovy is a dynamic language.
        So the static check can only detect so much.

        And even that limited check is quickly become useless, because we need to allow Object as a receiver
        (or else (foo=='FOO').implies(true) would fail because Groovy compiler cannot infer the type of
        foo=='FOO' as boolean, yet allowing Object as a receiver enables "foo"["class"]

     */
}
