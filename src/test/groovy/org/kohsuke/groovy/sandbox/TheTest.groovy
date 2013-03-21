package org.kohsuke.groovy.sandbox

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
    def ClassRecorder cr = new ClassRecorder()

    void setUp() {
        binding.foo = "FOO"
        binding.bar = "BAR"
        binding.zot = 5
        binding.point = new Point(1,2)
        binding.points = [new Point(1,2),new Point(3,4)]

        def cc = new CompilerConfiguration()
        cc.addCompilationCustomizers(new SandboxTransformer())
        sh = new GroovyShell(binding,cc)

    }

    def eval(String expression) {
        cr.reset()
        cr.register();
        try {
            return sh.evaluate(expression)
        } catch (Exception e) {
            throw new Exception("Failed to evaluate "+expression,e)
        } finally {
            cr.unregister();
        }
    }
    
    def assertIntercept(String expectedCallSequence, Object expectedValue, String script) {
        assertEquals(expectedValue,eval(script));
        assertEquals(expectedCallSequence.replace('/','\n').trim(), cr.toString().trim())
    }

    void testOK() {
        // instance call
        assertIntercept(
                "Integer.class/Class.forName(String)",
                String.class,
                "5.class.forName('java.lang.String')")

        assertIntercept(
                "String.toString()/String.hashCode()",
                "foo".hashCode(),
                "'foo'.toString().hashCode()"
        )

        // static call
        assertIntercept(// turns out this doesn't actually result in onStaticCall
                "Class.max(Float,Float)",
                Math.max(1f,2f),
                "Math.max(1f,2f)"
        )

        assertIntercept(// ... but this does
                "Math.max(Float,Float)",
                Math.max(1f,2f),
                "import static java.lang.Math.*; max(1f,2f)"
        )

        // property access
        assertIntercept(
                "String.class/Class.name",
                String.class.name,
                "'foo'.class.name"
        )

        // constructor & field access
        assertIntercept(
                "new Point(Integer,Integer)/Point.@x",
                1,
                "new java.awt.Point(1,2).@x"
        )
        
        // property set
        assertIntercept(
                "Point.x=Integer",
                3,
                "point.x=3"
        )
        assertEquals(3,binding.point.@x)

        // attribute set
        assertIntercept(
                "Point.@x=Integer",
                4,
                "point.@x=4"
        )
        assertEquals(4,binding.point.@x)

        // property spread
        assertIntercept(
                "Point.x=Integer/Point.x=Integer",
                3,
                "points*.x=3"
        )
        assertEquals(3,binding.points[0].@x)
        assertEquals(3,binding.points[1].@x)
        
        // array set & get
        assertIntercept(
                "[I[Integer]=Integer/[I[Integer]",
                1,
                "x=new int[3];x[0]=1;x[0]"
        )
    }

    void testClosure() {
        assertIntercept(
                "Script1\$_run_closure1.call()/Integer.class/Class.forName(String)",
                null,
                "def foo = { 5.class.forName('java.lang.String') }\n" +
                "foo()\n" +
                "return null")
    }

    void testClass() {
        assertIntercept(
                "Integer.class/Class.forName(String)",
                null,
                "class foo { static void main(String[] args) { 5.class.forName('java.lang.String') } }")
    }

    void testInnerClass() {
        assertIntercept(
                "Class.juu()/Integer.class/Class.forName(String)",
                null,
                "class foo {\n" +
                "  class bar {\n" +
                "    static void juu() { 5.class.forName('java.lang.String') }\n" +
                "  }\n" +
                "static void main(String[] args) { bar.juu() }\n" +
                "}")
    }

    void testStaticInitializationBlock() {
        assertIntercept(
                "Integer.class/Class.forName(String)",
                null,
                "class foo {\n" +
                "static { 5.class.forName('java.lang.String') }\n" +
                " static void main(String[] args) { }\n" +
                "}")
    }

    void testConstructor() {
        assertIntercept(
                "new foo()/Integer.class/Class.forName(String)",
                null,
                "class foo {\n" +
                "foo() { 5.class.forName('java.lang.String') }\n" +
                "}\n" +
                "new foo()\n" +
                "return null")
    }

    void testInitializationBlock() {
        assertIntercept(
                "new foo()/Integer.class/Class.forName(String)",
                null,
                "class foo {\n" +
                        "{ 5.class.forName('java.lang.String') }\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null")
    }

    void testFieldInitialization() {
        assertIntercept(
                "new foo()/Integer.class/Class.forName(String)",
                null,
                "class foo {\n" +
                        "def obj = 5.class.forName('java.lang.String')\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null")
    }

    void testStaticFieldInitialization() {
        assertIntercept(
                "Integer.class/Class.forName(String)/new foo()",
                null,
                "class foo {\n" +
                        "static obj = 5.class.forName('java.lang.String')\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null")
    }
}
