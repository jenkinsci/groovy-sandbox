package org.kohsuke.groovy.sandbox.impl;

import org.kohsuke.groovy.sandbox.GroovyInterceptor;

/**
 * {@link GroovyInterceptor.Invoker} that chains multiple {@link GroovyInterceptor} instances.
 * 
 * This version is optimized for arbitrary number arguments.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class VarArgInvokerChain extends InvokerChain {
    protected VarArgInvokerChain(Object receiver) {
        super(receiver);
    }

    public final Object call(Object receiver, String method) throws Throwable {
        return call(receiver,method,EMPTY_ARRAY);
    }

    public final Object call(Object receiver, String method, Object arg1) throws Throwable {
        return call(receiver,method,new Object[]{arg1});
    }

    public final Object call(Object receiver, String method, Object arg1, Object arg2) throws Throwable {
        return call(receiver,method,new Object[]{arg1,arg2});
    }

    private static final Object[] EMPTY_ARRAY = new Object[0];
}
