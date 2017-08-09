package org.kohsuke.groovy.sandbox.impl;

import groovy.lang.MetaClassImpl;
import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.codehaus.groovy.runtime.MethodClosure;

import static org.codehaus.groovy.runtime.InvokerHelper.*;

/**
 * {@link MethodClosure} that checks the call.
 *
 * @author Kohsuke Kawaguchi
 */
public class SandboxedMethodClosure extends MethodClosure {
    public SandboxedMethodClosure(Object owner, String method) {
        super(owner, method);
    }

    /**
     * Special logic needed to handle invocation due to not being an instance of MethodClosure itself. See
     * {@link MetaClassImpl#invokeMethod(Class, Object, String, Object[], boolean, boolean)} and its special handling
     * of {@code objectClass == MethodClosure.class}.
     */
    protected Object doCall(Object[] arguments) {
        try {
            return Checker.checkedCall(getOwner(), false, false, getMethod(), arguments);
        } catch (Throwable e) {
            throw new InvokerInvocationException(e);
        }
    }

    protected Object doCall() {
        Object[] emptyArgs = {};
        return doCall(emptyArgs);
    }

    @Override
    protected Object doCall(Object arguments) {
        return doCall(asArray(arguments));
    }
}
