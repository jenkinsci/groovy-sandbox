package org.kohsuke.groovy.sandbox.impl;

import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.codehaus.groovy.runtime.MethodClosure;

/**
 * {@link MethodClosure} that checks the call.
 *
 * @author Kohsuke Kawaguchi
 */
public class SandboxedMethodClosure extends MethodClosure {
    public SandboxedMethodClosure(Object owner, String method) {
        super(owner, method);
    }

    @Override
    protected Object doCall(Object arguments) {
        try {
            return Checker.checkedCall(getOwner(), false, false, getMethod(), arguments);
        } catch (Throwable e) {
            throw new InvokerInvocationException(e);
        }
    }
}
