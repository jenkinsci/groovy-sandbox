package org.kohsuke.groovy.sandbox.impl;

import org.kohsuke.groovy.sandbox.GroovyInterceptor;

import java.util.Iterator;

/**
 * {@link GroovyInterceptor.Invoker} that chains multiple {@link GroovyInterceptor} instances.
 * 
 * This version is optimized for arbitrary number arguments.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class VarArgInvokerChain implements GroovyInterceptor.Invoker {
    protected final Iterator<GroovyInterceptor> chain = GroovyInterceptor.getApplicableInterceptors().iterator();

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
