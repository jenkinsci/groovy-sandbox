package org.kohsuke.groovy.sandbox;

/**
 * {@link GroovyInterceptor} that looks at individual values that are coming into/out of a call,
 * without regard to any context.
 *
 * <p>
 * One of the common strategies for filtering is to ensure that the sandboxed script don't acquire a reference
 * to objects that they are not supposed to. This implementation works as a convenient base class for such interceptor
 * by providing a smaller number of methods that can be overridden.
 *
 * @author Kohsuke Kawaguchi
 */
public class GroovyValueFilter extends GroovyInterceptor {
    /**
     * Called for every receiver.
      */
    public Object filterReceiver(Object receiver) {
        return filter(receiver);
    }
    
    /**
     * Called for a return value of a method call, newly created object, retrieve property/attribute values.
      */
    public Object filterReturnValue(Object returnValue) {
        return filter(returnValue);
    }
    
    /**
     * Called for every argument to method/constructor calls.
      */
    public Object filterArgument(Object arg) {
        return filter(arg);
    }

    /**
     * Called for every index of the array get/set access.
      */
    public Object filterIndex(Object index) {
        return filter(index);
    }

    /**
     * All the specific {@code filterXXX()} methods delegate to this method.
     */
    public Object filter(Object o) {
        return o;
    }

    private Object[] filterArguments(Object[] args) {
        for (int i=0; i<args.length; i++)
            args[i] = filterArgument(args[i]);
        return args;
    }

    @Override
    public Object onMethodCall(Invoker invoker, Object receiver, String method, Object... args) throws Throwable {
        return filterReturnValue(super.onMethodCall(invoker, filterReceiver(receiver), method, filterArguments(args)));
    }

    @Override
    public Object onStaticCall(Invoker invoker, Class receiver, String method, Object... args) throws Throwable {
        return filterReturnValue(super.onStaticCall(invoker, (Class)filterReceiver(receiver), method, filterArguments(args)));
    }

    @Override
    public Object onNewInstance(Invoker invoker, Class receiver, Object... args) throws Throwable {
        return filterReturnValue(super.onNewInstance(invoker, (Class)filterReceiver(receiver), filterArguments(args)));
    }

    @Override
    public Object onGetProperty(Invoker invoker, Object receiver, String property) throws Throwable {
        return filterReturnValue(super.onGetProperty(invoker, filterReceiver(receiver), property));
    }

    @Override
    public Object onSetProperty(Invoker invoker, Object receiver, String property, Object value) throws Throwable {
        return filterReturnValue(super.onSetProperty(invoker, filterReceiver(receiver), property, filterArgument(value)));
    }

    @Override
    public Object onGetAttribute(Invoker invoker, Object receiver, String attribute) throws Throwable {
        return filterReturnValue(super.onGetAttribute(invoker, filterReceiver(receiver), attribute));
    }

    @Override
    public Object onSetAttribute(Invoker invoker, Object receiver, String attribute, Object value) throws Throwable {
        return filterReturnValue(super.onSetAttribute(invoker, filterReceiver(receiver), attribute, filterArgument(value)));
    }

    @Override
    public Object onGetArray(Invoker invoker, Object receiver, Object index) throws Throwable {
        return filterReturnValue(super.onGetArray(invoker, filterReceiver(receiver), filterIndex(index)));
    }

    @Override
    public Object onSetArray(Invoker invoker, Object receiver, Object index, Object value) throws Throwable {
        return filterReturnValue(super.onSetArray(invoker, filterReceiver(receiver), filterIndex(index), filterArgument(value)));
    }
}
