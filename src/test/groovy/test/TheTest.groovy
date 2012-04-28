package test

import java.awt.Point
import junit.framework.TestCase
import org.codehaus.groovy.control.CompilerConfiguration

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class TheTest extends TestCase {
    def sh;
    def binding = new Binding()

    void setUp() {
        binding.foo = "FOO"
        binding.bar = "BAR"
        binding.zot = 5
        binding.point = new Point(1,2)
        binding.points = [new Point(1,2),new Point(3,4)]

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
        assertEquals("java.lang.String",eval("'foo'.class.name"))

        // intercept field access
        assertEquals(1,eval("new java.awt.Point(1,2).@x"))

        // set
        assertEquals(5,eval("foo=5"))
        assertEquals(3,eval("point.x=3"))
        assertEquals(3,binding.point.@x)

        assertEquals(4,eval("point.@x=4"))
        assertEquals(4,binding.point.@x)

        assertEquals(3,eval("points*.x=3"))
        assertEquals(3,binding.points[0].@x)
        assertEquals(3,binding.points[1].@x)
        
        // array
        int[] a = (int[])eval("x=new int[3];x[0]=1;x");
        assertEquals(a.length,3)
        assertEquals(a[0],1)

        assertEquals(0,eval("x=new int[3];x[0]"));
    }
}
