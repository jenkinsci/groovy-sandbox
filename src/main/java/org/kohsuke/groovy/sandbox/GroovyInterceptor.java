package org.kohsuke.groovy.sandbox;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Interceptor of Groovy method calls
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class GroovyInterceptor {
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
    public Object onGetProperty(Invoker invoker, Object receiver, String property) throws Throwable {
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
    public Object onSetProperty(Invoker invoker, Object receiver, String property, Object value) throws Throwable {
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
    public Object onGetAttribute(Invoker invoker, Object receiver, String attribute) throws Throwable {
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
    public Object onSetAttribute(Invoker invoker, Object receiver, String property, Object value) throws Throwable {
        return invoker.call(receiver,property,value);
    }

    /**
     * Intercepts an array access, like "z=foo[bar]"
     *
     * @param receiver
     *      'foo' in the above example, the array-like object.
     * @param index
     *      'bar' in the above example, the object that acts as an index.
     */
    public Object onGetArray(Invoker invoker, Object receiver, Object index) throws Throwable {
        return invoker.call(receiver,null,index);
    }

    /**
     * Intercepts an attribute assignment like "foo[bar]=z"
     *
     * @param receiver
     *      'foo' in the above example, the array-like object.
     * @param index
     *      'bar' in the above example, the object that acts as an index.
     * @param value
     *      The value to be assigned.
     * @return
     *      The result of the assignment expression. Normally, you should return the same object as {@code value}.
     */
    public Object onSetArray(Invoker invoker, Object receiver, Object index, Object value) throws Throwable {
        return invoker.call(receiver,null,index,value);
    }

    public interface Invoker {
        Object call(Object receiver, String method) throws Throwable;
        Object call(Object receiver, String method, Object arg1) throws Throwable;
        Object call(Object receiver, String method, Object arg1, Object arg2) throws Throwable;
        Object call(Object receiver, String method, Object... args) throws Throwable;
    }
    
//    public void addToGlobal() {
//        globalInterceptors.add(this);
//    }
//
//    public void removeFromGlobal() {
//        globalInterceptors.remove(this);
//    }

    public void addToThread() {
        threadInterceptors.get().add(this);
    }

    public void removeFromThread() {
        threadInterceptors.get().remove(this);
    }

    private static final ThreadLocal<List<GroovyInterceptor>> threadInterceptors = new ThreadLocal<List<GroovyInterceptor>>() {
        @Override
        protected List<GroovyInterceptor> initialValue() {
            return new CopyOnWriteArrayList<GroovyInterceptor>();
        }
    };

    private static final ThreadLocal<List<GroovyInterceptor>> threadInterceptorsView = new ThreadLocal<List<GroovyInterceptor>>() {
        @Override
        protected List<GroovyInterceptor> initialValue() {
            return Collections.unmodifiableList(threadInterceptors.get());
        }
    };
    
//    private static final List<GroovyInterceptor> globalInterceptors = new CopyOnWriteArrayList<GroovyInterceptor>();
    
    public static List<GroovyInterceptor> getApplicableInterceptors() {
        return threadInterceptorsView.get();
    }
}
