package org.kohsuke.groovy.sandbox

import junit.framework.TestCase

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class StaticMethodSelectionTest extends TestCase {

    public static void strangeThirdSelection(Class x,Class y) {
        fail("I'm expecting this method not to be invoked");
    }

    /*
        A part of call routing to onMethodCall vs onStaticCall requires that we emulate the groovy's method
        picking logic. This is implemented inside Groovy in MetaClassImpl.chooseMethod.

        In 1.8.5, The first call to chooseMethod picks a static method defined on the class,
        then the 2nd check looks for instance methods from java.lang.Class.

        But the third one is strange, as it's checking the static methods defined on this class again,
        but with extra MetaClassHelper.convertToTypeArray(arguments)). Since arguments is already Class[],
        this means it will find a method like static void Foo.foo(Class,Class) against a call
        like Foo.foo(1,2). When I tried this in a test, the call subsequently fail with
        java.lang.reflect.Method.invoke():

        This is most likely a bug in Groovy, but since I cannot be certain, writing a test case here
        to monitor the behaviour change.

        private MetaMethod pickStaticMethod(String methodName, Class[] arguments) {
            MetaMethod method = null;
            MethodSelectionException mse = null;
            Object methods = getStaticMethods(theClass, methodName);

            if (!(methods instanceof FastArray) || !((FastArray)methods).isEmpty()) {
                try {
                    method = (MetaMethod) chooseMethod(methodName, methods, arguments);
                } catch(MethodSelectionException msex) {
                    mse = msex;
                }
            }
            if (method == null && theClass != Class.class) {
                MetaClass classMetaClass = registry.getMetaClass(Class.class);
                method = classMetaClass.pickMethod(methodName, arguments);
            }
            if (method == null) {
                method = (MetaMethod) chooseMethod(methodName, methods, MetaClassHelper.convertToTypeArray(arguments));
            }

            if (method == null && mse != null) {
                throw mse;
            } else {
                return method;
            }
        }

     */
    void testStrangeThirdSelection() {
        try {
            StaticMethodSelectionTest.strangeThirdSelection(1,2)
            fail();
        } catch (IllegalArgumentException e) {
            assert e.message.contains("argument type mismatch")
        }
    }
}
