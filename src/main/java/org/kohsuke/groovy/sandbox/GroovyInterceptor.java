package org.kohsuke.groovy.sandbox;

import org.kohsuke.groovy.sandbox.impl.Super;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Interceptor of Groovy method calls.
 *
 * <p>
 * Once created, it needs to be {@linkplain #register() registered} to start receiving interceptions.
 * List of interceptors are maintained per thread.
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
     * Intercepts a static method call on some class, like "Class.forName(...)".
     * 
     * Note that Groovy doesn't clearly differentiate static method calls from instance method calls.
     * If calls are determined to be static at compile-time, you get this method called, but
     * method calls whose receivers are {@link Class} can invoke static methods, too
     * (that is, {@code x=Integer.class;x.valueOf(5)} results in {@code onMethodCall(invoker,Integer.class,"valueOf",5)}
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
     * Intercepts an super method call, like "super.foo(...)"
     */
    public Object onSuperCall(Invoker invoker, Class senderType, Object receiver, String method, Object... args) throws Throwable {
        return invoker.call(new Super(senderType,receiver),method,args);
    }

    /**
     * Intercepts a {@code super(…)} call from a constructor.
     */
    public void onSuperConstructor(Invoker invoker, Class receiver, Object... args) throws Throwable {
        onNewInstance(invoker, receiver, args);
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
     * @param attribute
     *      'bar' in the above example, the name of the attribute
     * @param value
     *      The value to be assigned.
     * @return
     *      The result of the assignment expression. Normally, you should return the same object as {@code value}.
     */
    public Object onSetAttribute(Invoker invoker, Object receiver, String attribute, Object value) throws Throwable {
        return invoker.call(receiver,attribute,value);
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

    /**
     * Represents the next interceptor in the chain.
     * 
     * As {@link GroovyInterceptor}, you intercept by doing one of the following:
     * 
     * <ul>
     *     <li>Pass on to the next interceptor by calling one of the call() method,
     *         possibly modifying the arguments and return values, intercepting an exception, etc.
     *     <li>Throws an exception to block the call.
     *     <li>Return some value without calling the next interceptor.
     * </ul>
     * 
     * The signature of the call method is as follows:
     * 
     * <dl>
     *     <dt>receiver</dt>
     *     <dd>
     *         The object whose method/property is accessed.
     *         For constructor invocations and static calls, this is {@link Class}.
     *         If the receiver is null, all the interceptors will be skipped.
     *     </dd>
     *     <dt>method</dt>
     *     <dd>
     *         The name of the method/property/attribute. Otherwise pass in null.
     *     </dd>
     *     <dt>args</dt>
     *     <dd>
     *         Arguments of the method call, index of the array access, and/or values to be set.
     *         Multiple override of the call method is provided to avoid the implicit object
     *         array creation, but otherwise they behave the same way.
     *     </dd>
     * </dl>
     */
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

    /**
     * Registers this interceptor to the current thread's interceptor list.
     */
    public void register() {
        threadInterceptors.get().add(this);
    }

    /**
     * Reverses the earlier effect of {@link #register()}
     */
    public void unregister() {
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
