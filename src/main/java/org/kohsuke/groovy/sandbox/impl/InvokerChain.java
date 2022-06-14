package org.kohsuke.groovy.sandbox.impl;

import org.kohsuke.groovy.sandbox.GroovyInterceptor;
import org.kohsuke.groovy.sandbox.GroovyInterceptor.Invoker;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * @author Kohsuke Kawaguchi
 */
abstract class InvokerChain implements Invoker {
    protected final Iterator<GroovyInterceptor> chain;

    protected InvokerChain(Object receiver) {
        // See issue #6, #15. When receiver is null, technically speaking Groovy handles this
        // as if NullObject.INSTANCE is the receiver. OTOH, it's confusing
        // to GroovyInterceptor that the receiver can be null, so I'm
        // bypassing the checker in this case.
        if (receiver==null) {
            chain = EMPTY_ITERATOR;
        } else {
            List<GroovyInterceptor> interceptors = GroovyInterceptor.getApplicableInterceptors();
            if (interceptors.isEmpty()) {
                // We are running sandbox-transformed code, but there is no interceptor on the current thread.
                // This is dangerous (SECURITY-2020), so we reject everything.
                chain = REJECT_EVERYTHING.iterator();
            } else {
                chain = interceptors.iterator();
            }
        }
    }

    private static final Iterator<GroovyInterceptor> EMPTY_ITERATOR = Collections.<GroovyInterceptor>emptyList().iterator();
    private static final Set<GroovyInterceptor> REJECT_EVERYTHING = Collections.<GroovyInterceptor>singleton(new RejectEverythingInterceptor());
}
