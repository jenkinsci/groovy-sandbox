package org.kohsuke.groovy.sandbox;

import org.codehaus.groovy.runtime.NullObject;
import org.codehaus.groovy.runtime.ProxyGeneratorAdapter;
import org.jvnet.hudson.test.Issue;
import java.awt.Point;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.codehaus.groovy.runtime.ResourceGroovyMethods;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
public class TheTest extends SandboxTransformerTest {
    @Override
    public void configureBinding() {
        binding.setProperty("foo", "FOO");
        binding.setProperty("bar", "BAR");
        binding.setProperty("zot", 5);
        binding.setProperty("point", new Point(1, 2));
        binding.setProperty("points", Arrays.asList(new Point(1, 2), new Point(3, 4)));
        binding.setProperty("intArray", new int[] { 0, 1, 2, 3, 4 });
    }

    private void assertIntercept(String expectedCallSequence, Object expectedValue, String script) throws Exception {
        String[] expectedCalls = expectedCallSequence.isEmpty() ? new String[0] : expectedCallSequence.split("/");
        assertIntercept(script, expectedValue, expectedCalls);
    }

    private void assertInterceptNoScript(String expectedCallSequence, Object expectedValue, String script) throws Exception {
        String[] expectedCalls = expectedCallSequence.isEmpty() ? new String[0] : expectedCallSequence.split("/");
        assertEvaluate(script, expectedValue);
        assertInterceptedExact(expectedCalls);
    }

    private void assertIntercept(List<String> expectedCallSequence, Object expectedValue, String script) throws Exception {
        assertIntercept(script, expectedValue, expectedCallSequence.toArray(new String[0]));
    }

    @Test public void testOK() throws Exception {
        // instance call
        assertIntercept(
                "Integer.class/Class:forName(String)",
                String.class,
                "5.class.forName('java.lang.String')");

        assertIntercept(
                "String.toString()/String.hashCode()",
                "foo".hashCode(),
                "'foo'.toString().hashCode()"
        );

        // static call
        assertIntercept(// turns out this doesn't actually result in onStaticCall
                "Math:max(Float,Float)",
                Math.max(1f,2f),
                "Math.max(1f,2f)"
        );

        assertIntercept(// ... but this does
                "Math:max(Float,Float)",
                Math.max(1f,2f),
                "import static java.lang.Math.*; max(1f,2f)"
        );

        // property access
        assertIntercept(
                "String.class/Class.name",
                String.class.getName(),
                "'foo'.class.name"
        );

        // constructor & field access
        assertIntercept(
                "new Point(Integer,Integer)/Point.@x",
                1,
                "new java.awt.Point(1,2).@x"
        );

        // property set
        assertIntercept(
                "Script7.point/Point.x=Integer",
                3,
                "point.x=3"
        );
        assertEquals(3, ((Point)binding.getProperty("point")).x);

        // attribute set
        assertIntercept(
                "Script8.point/Point.@x=Integer",
                4,
                "point.@x=4"
        );
        assertEquals(4, ((Point)binding.getProperty("point")).x);

        // property spread
        assertIntercept(
                "Script9.points/Point.x=Integer/Point.x=Integer",
                3,
                "points*.x=3"
        );
        assertEquals(3, ((List<Point>)binding.getProperty("points")).get(0).x);
        assertEquals(3, ((List<Point>)binding.getProperty("points")).get(1).x);

        // array set & get
        assertIntercept(
                "int[][Integer]=Integer/int[][Integer]",
                1,
                "def x=new int[3];x[0]=1;x[0]"
        );
    }

    @Test public void testClosure() throws Exception {
        assertIntercept(
                "Script1$_run_closure1.call()/Integer.class/Class:forName(String)",
                null,
                "def foo = { 5.class.forName('java.lang.String') }\n" +
                "foo()\n" +
                "return null");
    }

    @Test public void testClass() throws Exception {
        assertInterceptNoScript(
                "Integer.class/Class:forName(String)",
                null,
                "class foo { static void main(String[] args) throws Exception { 5.class.forName('java.lang.String') } }");
    }

    @Test public void testInnerClass() throws Exception {
        assertInterceptNoScript(
                "foo$bar:juu()/Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                "  class bar {\n" +
                "    static void juu() throws Exception { 5.class.forName('java.lang.String') }\n" +
                "  }\n" +
                "static void main(String[] args) throws Exception { bar.juu() }\n" +
                "}");
    }

    @Test public void testStaticInitializationBlock() throws Exception {
        assertInterceptNoScript(
                "Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                "static { 5.class.forName('java.lang.String') }\n" +
                " static void main(String[] args) throws Exception { }\n" +
                "}");
    }

    @Test public void testConstructor() throws Exception {
        assertIntercept(
                "new foo()/Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                "foo() { 5.class.forName('java.lang.String') }\n" +
                "}\n" +
                "new foo()\n" +
                "return null");
    }

    @Test public void testInitializationBlock() throws Exception {
        assertIntercept(
                "new foo()/Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                        "{ 5.class.forName('java.lang.String') }\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null");
    }

    @Test public void testFieldInitialization() throws Exception {
        assertIntercept(
                "new foo()/Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                        "def obj = 5.class.forName('java.lang.String')\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null");
    }

    @Test public void testStaticFieldInitialization() throws Exception {
        assertIntercept(
                "new foo()/Integer.class/Class:forName(String)",
                null,
                "class foo {\n" +
                        "static obj = 5.class.forName('java.lang.String')\n" +
                        "}\n" +
                        "new foo()\n" +
                        "return null");
    }

    @Test public void testCompoundAssignment() throws Exception {
        assertIntercept(
                "Script1.point/Point.x/Double.plus(Integer)/Point.x=Double",
                (double)4.0,
                "point.x += 3");
    }

    @Test public void testCompoundAssignment2() throws Exception {
        // "[I" is the type name of int[]
        assertIntercept(
                "Script1.intArray/int[][Integer]/Integer.leftShift(Integer)/int[][Integer]=Integer",
                1<<3,
                "intArray[1] <<= 3");
    }

    @Test public void testComparison() throws Exception {
        assertIntercept(
                "Script1.point/Script1.point/Point.equals(Point)/Integer.compareTo(Integer)",
                true,
                "point==point; 5==5");
    }

    @Test public void testAnonymousClass() throws Exception {
        assertIntercept(
                "new Script1$1(Script1)/Script1$1.@this$0=Script1/Script1$1.plusOne(Integer)/Integer.plus(Integer)",
                6,
                "def x = new Object() {\n" +
                "  def plusOne(rhs) {\n" +
                "    return rhs+1\n" +
                "  }\n" +
                "}\n" +
                "x.plusOne(5)\n");
    }

    @Test public void testIssue2() throws Exception {
        assertIntercept("new HashMap()/HashMap.get(String)/Script1.nop(null)",null,"def nop(v) { }; nop(new HashMap().dummy);");
        assertIntercept("Script2.nop()",null,"def nop() { }; nop();");
        assertIntercept("Script3.nop(null)",null,"def nop(v) { }; nop(null);");
    }

    @Test public void testSystemExitAsFunction() throws Exception {
        assertIntercept("TheTest:idem(Integer)/TheTest:idem(Integer)",123,"org.kohsuke.groovy.sandbox.TheTest.idem(org.kohsuke.groovy.sandbox.TheTest.idem(123))");
    }

    /**
     * Idempotent function used for testing
     */
    public static Object idem(Object o) {
        return o;
    }

    @Test public void testArrayArgumentsInvocation() throws Exception {
        assertIntercept(
                "new TheTest$MethodWithArrayArg()/TheTest$MethodWithArrayArg.f(Object[])",
                3,
                "new TheTest.MethodWithArrayArg().f(new Object[3])");
    }

    public static class MethodWithArrayArg {
        public Object f(Object[] arg) {
            return arg.length;
        }
    }

    /**
     * See issue #6. We are not intercepting calls to null.
     */
    @Test public void testNull() throws Exception {
        assertIntercept("", NullObject.class, "def x=null; null.getClass()");
        assertIntercept("", "null3", "def x=null; x.plus('3')");
        assertIntercept("", false, "def x=null; x==3");
    }

    /**
     * See issue #9
     */
    @Test public void testAnd() throws Exception {
        assertIntercept("", false,
                "String s = null\n" +
                "if (s != null && s.length > 0)\n" +
                "  throw new Exception()\n" +
                "return false\n");
    }

    @Test public void testLogicalNotEquals() throws Exception {
        assertIntercept("Integer.toString()/String.compareTo(String)", true,
                "def x = 3.toString(); if (x != '') return true; else return false;");
    }

    // see issue 8
    @Test public void testClosureDelegation() throws Exception {
        assertIntercept(Arrays.asList
            (
                    "Script1$_run_closure1.call()",
                    "Script1$_run_closure1.delegate=String",
                    "String.length()"
            ), 3,
            "def x = 0\n" +
            "def c = { ->\n" +
            "    delegate = 'foo'\n" +
            "    x = length()\n" +
            "}\n" +
            "c()\n" +
            "x\n");
    }

    @Test public void testClosureDelegationOwner() throws Exception {
        assertIntercept(Arrays.asList
            (
                "Script1$_run_closure1.call()",
                "Script1$_run_closure1.delegate=String",
                "Script1$_run_closure1$_closure2.call()",
                "String.length()"
            ),
            3,
            "def x = 0\n" +
            "def c = { ->\n" +
            "    delegate = 'foo';\n" +
            "    { -> x = length() }()\n" +
            "}\n" +
            "c()\n" +
            "x\n");
    }

    @Test public void testClosureDelegationProperty() throws Exception {
        // TODO: ideally we should be seeing String.length()
        // doing so requires a call site selection and deconstruction
        assertIntercept(Arrays.asList
            (
                "Script1$_run_closure1.call()",
                "new SomeBean(Integer,Integer)",
                "Script1$_run_closure1.delegate=SomeBean",
                // by the default delegation rule of Closure, it first attempts to get Script1.x,
                // and only after we find out that there's no such property, we fall back to SomeBean.x
                "Script1.x",
                "SomeBean.x",
                "Script1.y",
                "SomeBean.y",
                "Integer.plus(Integer)"
            ),
            3,
            "def sum = 0\n" +
            "def c = { ->\n" +
            "    delegate = new SomeBean(1,2)\n" +
            "    sum = x+y\n" +
            "}\n" +
            "c()\n" +
            "sum\n");
    }

    @Test public void testClosureDelegationPropertyDelegateOnly() throws Exception {
        assertIntercept(Arrays.asList
            (
                "Script1$_run_closure1.call()",
                "new SomeBean(Integer,Integer)",
                "Script1$_run_closure1.delegate=SomeBean",
                "Script1$_run_closure1.resolveStrategy=Integer",
                // with DELEGATE_FIRST rule, unlike testClosureDelegationProperty() it shall not touch Script1.*
                "SomeBean.x",
                "SomeBean.y",
                "Integer.plus(Integer)"
            ),
            3,
            "def sum = 0\n" +
            "def c = { ->\n" +
            "    delegate = new SomeBean(1,2)\n" +
            "    resolveStrategy = 1; // Closure.DELEGATE_FIRST\n" +
            "    sum = x+y\n" +
            "}\n" +
            "c()\n" +
            "sum\n");
    }

    @Test public void testClosureDelegationPropertyOwner() throws Exception {
        /*
            The way property access of 'x' gets dispatched to is:

            innerClosure.getProperty("x"), which delegates to its owner, which is
            outerClosure.getProperty("x"), which delegates to its delegate, which is
            SomeBean.x
         */
        assertIntercept(Arrays.asList
            (
                "Script1$_run_closure1.call()",
                "new SomeBean(Integer,Integer)",
                "Script1$_run_closure1.delegate=SomeBean",
                "Script1$_run_closure1$_closure2.call()",
                "Script1.x",
                "SomeBean.x",
                "Script1.y",
                "SomeBean.y",
                "Integer.plus(Integer)"
            ),
            3,
            "def sum = 0\n" +
            "def c = { ->\n" +
            "    delegate = new SomeBean(1,2);\n" +
            "    { -> sum = x+y; }()\n" +
            "}\n" +
            "c()\n" +
            "sum\n");
    }

    @Test public void testGString() throws Exception {
        assertIntercept("Integer.plus(Integer)/Integer.plus(Integer)/GStringImpl.toString()", "answer=6",
            "def x = /answer=${1+2+3}/; x.toString()");
    }

    @Test public void testClosurePropertyAccess() throws Exception {
        assertIntercept(Arrays.asList(
                "Script1$_run_closure1.call()",
                "new Exception(String)",
                "Script1$_run_closure1.delegate=Exception",
                "Script1.message",
                "Exception.message"),
                "foo",
                "{ ->\n" +
                "  delegate = new Exception('foo')\n" +
                "  return message\n" +
                "}()\n");
    }

    /**
     * Calling method on Closure that's not delegated to somebody else.
     */
    @Test public void testNonDelegatingClosure() throws Exception {
        assertIntercept(Arrays.asList(
            "Script1$_run_closure1.hashCode()",
            "Script1$_run_closure1.equals(Script1$_run_closure1)"
        ), true,
            "def c = { -> }\n" +
            "c.hashCode()\n" +
            "c.equals(c)\n");

        // but these guys are not on closure
        assertIntercept(Arrays.asList(
            "Script2$_run_closure1.call()",
            "Script2$_run_closure1.hashCode()",
            "Script2$_run_closure1.hashCode()",
            "Integer.compareTo(Integer)"
        ), true,
            "def c = { ->\n" +
            "    hashCode()\n" +
            "}\n" +
            "return c()==c.hashCode()\n");
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

    // bug 14
    @Test public void testUnclassifiedStaticMethod() throws Exception {
        assertIntercept(Arrays.asList
        (
            "Script1.m()",
            "System:getProperty(String)"
        ),null,
            "m()\n" +
            "def m() {\n" +
            "    System.getProperty('foo')\n" +
            "}\n");
    }

    @Test public void testInstanceOf() throws Exception {
        assertIntercept("", true,
            "def x = 'foo'\n" +
            "x instanceof String\n");
    }

    @Test public void testRegexp() throws Exception {
        assertIntercept(Arrays.asList
        (
            "ScriptBytecodeAdapter:findRegex(String,String)",
            "ScriptBytecodeAdapter:matchRegex(String,String)"
        ), false,
            "def x = 'foo'\n" +
            "x =~ /bla/\n" +
            "x ==~ /bla/\n");
    }

    @Issue("JENKINS-46088")
    @Test public void testMatcherTypeAssignment() throws Exception {
        assertIntercept(Arrays.asList
            (
                "ScriptBytecodeAdapter:findRegex(String,String)",
                "Matcher.matches()"
            ), false,
            "def x = 'foo'\n" +
            "java.util.regex.Matcher m = x =~ /bla/\n" +
            "return m.matches()\n");
    }

    @Test public void testNumericComparison() throws Exception {
        assertIntercept("Integer.compareTo(Integer)", true,
            "5 < 8");
    }

    @Test public void testIssue17() throws Exception {
        assertIntercept("new IntRange(Boolean,Integer,Integer)", 45,
            "def x = 0\n" +
            "for ( i in 0..9 ) {\n" +
            "    x+= i\n" +
            "}\n" +
            "return x\n");
    }

    // issue 16
    @Test public void testPrePostfixLocalVariable() throws Exception {
        assertIntercept("Integer.next()/ArrayList[Integer]", Arrays.asList(1, 0),
            "def x = 0\n" +
            "def y=x++\n" +
            "return [x,y]");

        assertIntercept("Integer.previous()", Arrays.asList(2, 2),
            "def x = 3\n" +
            "def y=--x\n" +
            "return [x,y]");
    }

    @Test public void testPrePostfixArray() throws Exception {
        assertIntercept(Arrays.asList(
            "ArrayList[Integer]",           // for reading x[1] before increment
            "Integer.next()",
            "ArrayList[Integer]=Integer",   // for writing x[1] after increment
            "ArrayList[Integer]"            // for reading x[1] in the return statement
        ), Arrays.asList(3, 2),
            "def x = [1,2,3]\n" +
            "def y=x[1]++\n" +
            "return [x[1],y]");

        assertIntercept(Arrays.asList(
            "ArrayList[Integer]",           // for reading x[1] before increment
            "Integer.previous()",
            "ArrayList[Integer]=Integer",   // for writing x[1] after increment
            "ArrayList[Integer]"            // for reading x[1] in the return statement
        ), Arrays.asList(1, 1),
            "def x = [1,2,3]\n" +
            "def y=--x[1]\n" +
            "return [x[1],y]");
    }

    @Test public void testPrePostfixProperty() throws Exception {
        assertIntercept(Arrays.asList(
            "Script1.x=Integer",    // x=3
            "Script1.x",
            "Integer.next()",
            "Script1.x=Integer",    // read, plus, then write back
            "Script1.x"             // final read for the return statement
        ), Arrays.asList(4, 3),
            "x = 3\n" +
            "def y=x++\n" +
            "return [x,y]\n");

        assertIntercept(Arrays.asList(
                "Script2.x=Integer",    // x=3
                "Script2.x",
                "Integer.previous()",
                "Script2.x=Integer",    // read, plus, then write back
                "Script2.x"             // final read for the return statement
        ), Arrays.asList(2, 2),
            "x = 3\n" +
            "def y=--x\n" +
            "return [x,y]\n");
    }

    @Test public void testCatchStatement() throws Exception {
        sandboxedEval(
            "def o = null\n" +
            "try {\n" +
            "    o.hello()\n" +
            "    return null\n" +
            "} catch (Exception e) {\n" +
            "    throw new Exception('wrapped', e)\n" +
            "}",
            ShouldFail.class,
            e -> {
                assertThat(e.getMessage(), containsString("wrapped"));
                assertThat(e.getCause(), instanceOf(NullPointerException.class));
            });
    }

    /**
     * Makes sure the line number in the source code is preserved after translation.
     */
    @Test public void testIssue21() throws Exception {
        sandboxedEval(
            "\n" + // line 1
            "def x = null\n" +
            "def cl = {\n" +
            "    x.hello()\n" + // line 4
            "}\n" +
            "try {\n" +
            "  cl();\n" + // line 7
            "} catch (Exception e) {\n" +
            "  throw new Exception('wrapped', e)\n" +
            "}",
            ShouldFail.class,
            e -> {
                assertThat(e.getMessage(), containsString("wrapped"));
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));

                String s = sw.toString();
                assertThat(s, containsString("Script1.groovy:4"));
                assertThat(s, containsString("Script1.groovy:7"));
            });
    }

    @Test public void testIssue15() throws Exception {
        sandboxedEval(
            "try {\n" +
            "  def x = null\n" +
            "  return x.nullProp\n" +
            "} catch (Exception e) {\n" +
            "  throw new Exception('wrapped', e)\n" +
            "}",
            ShouldFail.class,
            e -> {
                assertThat(e.getMessage(), containsString("wrapped"));
                assertThat(e.getCause(), instanceOf(NullPointerException.class));
            });
        // x.nullProp shouldn't be intercepted
        assertIntercepted("new Exception(String,NullPointerException)");

        sandboxedEval(
            "try {\n" +
            "  def x = null\n" +
            "  x.nullProp = 1\n" +
            "} catch (Exception e) {\n" +
            "  throw new Exception('wrapped', e)\n" +
            "}",
            ShouldFail.class,
            e -> {
                assertThat(e.getMessage(), containsString("wrapped"));
                assertThat(e.getCause(), instanceOf(NullPointerException.class));
            });
        // x.nullProp shouldn't be intercepted
        assertIntercepted("new Exception(String,NullPointerException)");
    }

    @Test public void testInOperator() throws Exception {
        assertIntercept(
            "Integer.isCase(Integer)", true, "1 in 1"
        );

        assertIntercept(
            "Integer.isCase(Integer)", false, "1 in 2"
        );

        assertIntercept(
            "ArrayList.isCase(Integer)", true, "1 in [1]"
        );

        assertIntercept(
            "ArrayList.isCase(Integer)", false, "1 in [2]"
        );
    }

    /**
     * Property access to Map is handled specially by MetaClassImpl, so our interceptor needs to treat that
     * accordingly.
     */
    @Test public void testMapPropertyAccess() throws Exception {
        assertIntercept("new HashMap()/HashMap.get(String)",null,"new HashMap().dummy;");
        assertIntercept("new HashMap()/HashMap.put(String,Integer)",5,"new HashMap().dummy=5");
    }

    /**
     * Intercepts super.toString()
     */
    @Issue("JENKINS-42563")
    @Test public void testSuperCall() throws Exception {
        assertIntercept(Arrays.asList(
            "new Zot()",
            "new Bar()",
            "new Foo()",
            "Zot.toString()",
            "Zot.super(Bar).toString()",
            "String.plus(String)"
        ), "xfoo",
            "class Foo {\n" +
            "    public String toString() {\n" +
            "        return 'foo'\n" +
            "    }\n" +
            "}\n" +
            "class Bar extends Foo {\n" +
            "    public String toString() {\n" +
            "        return 'x'+super.toString()\n" +
            "    }\n" +
            "}\n" +
            "class Zot extends Bar {}\n" +
            "new Zot().toString()\n");
    }

    @Test public void testPostfixOpInClosure() throws Exception {
        assertIntercept(Arrays.asList(
                "ArrayList.each(Script1$_run_closure1)",
                "Integer.next()",
                "ArrayList[Integer]",
                "Integer.next()",
                "ArrayList[Integer]",
                "Integer.next()",
                "ArrayList[Integer]",
                "Integer.next()",
                "ArrayList[Integer]",
                "Integer.next()",
                "ArrayList[Integer]"),
        5,
        "def cnt = 0\n" +
        "[0, 1, 2, 3, 4].each {\n" +
        "  cnt++\n" +
        "}\n" +
        "return cnt\n");
    }

    @Issue("SECURITY-566")
    @Test public void testTypeCoercion() throws Exception {
        Field pxyCounterField = ProxyGeneratorAdapter.class.getDeclaredField("pxyCounter");
        pxyCounterField.setAccessible(true);
        AtomicLong pxyCounterValue = (AtomicLong) pxyCounterField.get(null);
        pxyCounterValue.set(0); // make sure *_groovyProxy names are predictable
        assertIntercept("Locale:getDefault()/Class1_groovyProxy.getDefault()",
            Locale.getDefault(),
            "interface I {\n" +
            "    Locale getDefault()\n" +
            "}\n" +
            "(Locale as I).getDefault()\n");
    }

    @Issue("JENKINS-33468")
    @Test public void testClosureImplicitIt() throws Exception {
        assertIntercept(Arrays.asList(
            "Script1.c=Script1$_run_closure1",
            "Script1.c(Integer)",
            "Integer.plus(Integer)"
        ), 2,
                "c = { it + 1 }\n" +
                "c(1)\n"
        );

        assertIntercept(Arrays.asList(
            "Script2.c=Script2$_run_closure1",
            "Script2.c(Integer)",
            "Integer.plus(Integer)"
        ), 2,
                "c = {v -> v + 1 }\n" +
                "c(1)\n"
        );

        assertIntercept(Arrays.asList(
            "Script3.c=Script3$_run_closure1",
            "Script3.c()"
        ), 2,
                "c = {-> 2 }\n" +
                "c()"
        );
    }

    @Issue("JENKINS-46191")
    @Test public void testEmptyDeclaration() throws Exception {
        assertIntercept("",
            "abc",
            "String a\n" +
            "a = 'abc'\n" +
            "return a\n");
    }

    @Issue("SECURITY-663")
    @Test public void testAsFile() throws Exception {
        File f = File.createTempFile("foo", ".tmp");

        ResourceGroovyMethods.write(f, "This is\na test\n");
        assertIntercept(Arrays.asList(
                "new File(String)",
                "File.each(Script1$_run_closure1)",
                "ArrayList.leftShift(String)",
                "ArrayList.leftShift(String)",
                "ArrayList.join(String)"),
                "This is a test",
                "def s = []\n" +
                "($/" + f.getCanonicalPath() + "/$ as File).each { s << it }\n" +
                "s.join(' ')\n");
    }

    @Issue("JENKINS-50380")
    @Test public void testCheckedCastWhenAssignable() throws Exception {
        assertIntercept("new NonArrayConstructorList(Boolean,Boolean)/NonArrayConstructorList.join(String)",
                "one",
                "NonArrayConstructorList foo = new NonArrayConstructorList(true, false)\n" +
                "List castFoo = (List)foo\n" +
                "return castFoo.join('')\n");
    }

    @Issue("JENKINS-50470")
    @Test public void testCollectionGetProperty() throws Exception {
        assertIntercept(Arrays.asList(
                "new SimpleNamedBean(String)",
                "new SimpleNamedBean(String)",
                "new SimpleNamedBean(String)",
                // Before the JENKINS-50470 fix, this would just be ArrayList.name
                "SimpleNamedBean.name",
                "SimpleNamedBean.name",
                "SimpleNamedBean.name",
                "ArrayList.class",
                "ArrayList.join(String)",
                "String.plus(String)",
                "String.plus(Class)"),
                "abc class java.util.ArrayList",
                "def l = [new SimpleNamedBean('a'), new SimpleNamedBean('b'), new SimpleNamedBean('c')]\n" +
                "def nameList = l.name\n" +
                "def cl = l.class\n" +
                "return nameList.join('') + ' ' + cl\n");
    }
}
