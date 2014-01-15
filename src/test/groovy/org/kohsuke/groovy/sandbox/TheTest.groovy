package org.kohsuke.groovy.sandbox

import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.codehaus.groovy.runtime.NullObject

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
        binding.intArray = [0,1,2,3,4] as int[]

        def cc = new CompilerConfiguration()
        cc.addCompilationCustomizers(new ImportCustomizer().addImports(TheTest.class.name))
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
        def actual = eval(script)
        assertEquals(expectedValue, actual);
        assertEquals(expectedCallSequence.replace('/','\n').trim(), cr.toString().trim())
    }

    void testOK() {
        // instance call
        assertIntercept(
                "Integer.class/Class:forName(String)",
                String.class,
                "5.class.forName('java.lang.String')")

        assertIntercept(
                "String.toString()/String.hashCode()",
                "foo".hashCode(),
                "'foo'.toString().hashCode()"
        )

        // static call
        assertIntercept(// turns out this doesn't actually result in onStaticCall
                "Math:max(Float,Float)",
                Math.max(1f,2f),
                "Math.max(1f,2f)"
        )

        assertIntercept(// ... but this does
                "Math:max(Float,Float)",
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
                "int[][Integer]=Integer/int[][Integer]",
                1,
                "x=new int[3];x[0]=1;x[0]"
        )
    }

    void testClosure() {
        assertIntercept(
                "Script1\$_run_closure1.call()/Integer.class/Class:forName(String)",
                null,
                "def foo = { 5.class.forName('java.lang.String') }\n" +
                "foo()\n" +
                "return null")
    }

    void testClass() {
        assertIntercept(
                "Integer.class/Class:forName(String)",
                null,
                "class foo { static void main(String[] args) { 5.class.forName('java.lang.String') } }")
    }

    void testInnerClass() {
        assertIntercept(
                "foo\$bar:juu()/Integer.class/Class:forName(String)",
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
                "Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                "static { 5.class.forName('java.lang.String') }\n" +
                " static void main(String[] args) { }\n" +
                "}")
    }

    void testConstructor() {
        assertIntercept(
                "new foo()/Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                "foo() { 5.class.forName('java.lang.String') }\n" +
                "}\n" +
                "new foo()\n" +
                "return null")
    }

    void testInitializationBlock() {
        assertIntercept(
                "new foo()/Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                        "{ 5.class.forName('java.lang.String') }\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null")
    }

    void testFieldInitialization() {
        assertIntercept(
                "new foo()/Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                        "def obj = 5.class.forName('java.lang.String')\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null")
    }

    void testStaticFieldInitialization() {
        assertIntercept(
                "Integer.class/Class:forName(String)/new foo()",
                null,
                "class foo {\n" +
                        "static obj = 5.class.forName('java.lang.String')\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null")
    }

    void testCompoundAssignment() {
        assertIntercept(
                "Point.x/Double.plus(Integer)/Point.x=Double",
                (double)4.0,
"""
point.x += 3
""")
    }

    void testCompoundAssignment2() {
        // "[I" is the type name of int[]
        assertIntercept(
                "int[][Integer]/Integer.leftShift(Integer)/int[][Integer]=Integer",
                1<<3,
"""
intArray[1] <<= 3;
""")
    }

    void testComparison() {
        assertIntercept(
                "Point.equals(Point)/Integer.compareTo(Integer)",
                true,
"""
point==point
5==5
""")
    }

    void testNestedClass() {
        assertIntercept(
                "new Script1\$1(Script1)/Script1\$1.plusOne(Integer)/Integer.plus(Integer)",
                6,
"""
x = new Object() {
   def plusOne(rhs) {
     return rhs+1;
   }
}
x.plusOne(5)
""")
    }

    void testIssue2() {
        assertIntercept("new HashMap()/HashMap.dummy/Script1.println(null)",null,"println(new HashMap().dummy);")
        assertIntercept("Script2.println()",null,"println();")
        assertIntercept("Script3.println(null)",null,"println(null);")
    }

    void testSystemExitAsFunction() {
        assertIntercept("TheTest:idem(Integer)/TheTest:idem(Integer)",123,"org.kohsuke.groovy.sandbox.TheTest.idem(org.kohsuke.groovy.sandbox.TheTest.idem(123))")
    }

    /**
     * Idempotent function used for testing
     */
    public static Object idem(Object o) {
        return o;
    }

    void testArrayArgumentsInvocation() {
        assertIntercept('new TheTest$MethodWithArrayArg()/TheTest$MethodWithArrayArg.f(Object[])', 3, "new TheTest.MethodWithArrayArg().f(new Object[3])")
    }

    public static class MethodWithArrayArg {
        public Object f(Object[] arg) {
            return arg.length;
        }
    }

    /**
     * See issue #6. We are not intercepting calls to null.
     */
    void testNull() {
        assertIntercept("", NullObject.class, "x=null; null.getClass()")
        assertIntercept("", "null3", "x=null; x.plus('3')")
        assertIntercept("", false, "x=null; x==3")
    }

    /**
     * See issue #9
     */
    void testAnd() {
        assertIntercept("", false, """
            String s = null
            if (s != null && s.length > 0)
              throw new Exception();
            return false;
            """)
    }

    void testLogicalNotEquals() {
        assertIntercept("Integer.toString()/String.compareTo(String)", true,
                "def x = 3.toString(); if (x != '') return true; else return false;")
    }


    // Groovy doesn't allow this?
//    void testLocalClass() {
//        assertIntercept(
//                "new Foo()/Foo.plusOne(Integer)/Integer.plus(Integer)",
//                7,
//"""
//class Foo {
//   def plusTwo(rhs) {
//     class Bar { def plusOne(rhs) { rhs + 2; } }
//     return new Bar().plusOne(rhs)+1;
//   }
//}
//new Foo().plusTwo(5)
//""")
//    }
}
