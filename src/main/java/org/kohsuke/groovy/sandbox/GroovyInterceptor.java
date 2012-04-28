package org.kohsuke.groovy.sandbox;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class GroovyInterceptor {
    public interface Invoker {
        Object call(Object receiver, String method) throws Throwable;
        Object call(Object receiver, String method, Object arg1) throws Throwable;
        Object call(Object receiver, String method, Object... args) throws Throwable;
    }

    /**
     * Intercepts an instance method call on some object of the form "foo.bar(...)"
     */
    public Object onMethodCall(Invoker invoker, Object receiver, String method, Object... args) throws Throwable {
        return invoker.call(receiver,method,args);
    }

    /**
     * Intercepts a static method call on some class, like "Class.forName(...)"
     */
    public Object onStaticCall(Invoker invoker, Class receiver, String method, Object... args) throws Throwable {
        return invoker.call(receiver,method,args);
    }

    /**
     * Intercepts an object instantiation, like "new Receiver(...)"
     */
    public Object onNewInstance(Invoker invoker, Class receiver, Object... args) throws Throwable {
        return invoker.call(receiver,null,args);
    }

    /**
     * Intercepts a property access, like "z=foo.bar"
     * 
     * @param receiver
     *      'foo' in the above example, the object whose property is accessed.
     * @param property
     *      'bar' in the above example, the name of the property
     */
    public Object onGetProperty(Invoker invoker, Class receiver, String property) throws Throwable {
        return invoker.call(receiver,property);
    }

    /**
     * Intercepts a property assignment like "foo.bar=z"
     *
     * @param receiver
     *      'foo' in the above example, the object whose property is accessed.
     * @param property
     *      'bar' in the above example, the name of the property
     * @param value
     *      The value to be assigned.
     * @return
     *      The result of the assignment expression. Normally, you should return the same object as {@code value}.
     */
    public Object onSetProperty(Invoker invoker, Class receiver, String property, Object value) throws Throwable {
        return invoker.call(receiver,property,value);
    }

    /**
     * Intercepts an attribute access, like "z=foo.@bar"
     *
     * @param receiver
     *      'foo' in the above example, the object whose attribute is accessed.
     * @param attribute
     *      'bar' in the above example, the name of the attribute
     */
    public Object onGetAttribute(Invoker invoker, Class receiver, String attribute) throws Throwable {
        return invoker.call(receiver, attribute);
    }

    /**
     * Intercepts an attribute assignment like "foo.@bar=z"
     *
     * @param receiver
     *      'foo' in the above example, the object whose attribute is accessed.
     * @param property
     *      'bar' in the above example, the name of the attribute
     * @param value
     *      The value to be assigned.
     * @return
     *      The result of the assignment expression. Normally, you should return the same object as {@code value}.
     */
    public Object onSetAttribute(Invoker invoker, Class receiver, String property, Object value) throws Throwable {
        return invoker.call(receiver,property,value);
    }
}
