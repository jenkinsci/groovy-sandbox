package org.kohsuke.groovy.sandbox.impl;

import org.kohsuke.groovy.sandbox.GroovyInterceptor;

import java.util.Iterator;

/**
 * {@link GroovyInterceptor.Invoker} that chains multiple {@link GroovyInterceptor} instances.
 *
 * This version expects exactly one argument.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class SingleArgInvokerChain implements GroovyInterceptor.Invoker {
    protected final Iterator<GroovyInterceptor> chain = GroovyInterceptor.getApplicableInterceptors().iterator();

    public final Object call(Object receiver, String method) throws Throwable {
        throw new UnsupportedOperationException();
    }

    public final Object call(Object receiver, String method, Object arg1, Object arg2) throws Throwable {
        throw new UnsupportedOperationException();
    }

    public final Object call(Object receiver, String method, Object... args) throws Throwable {
        if (args.length!=1)
            throw new UnsupportedOperationException();
        return call(receiver,method,args[0]);
    }
}
