package org.kohsuke.groovy.sandbox.impl;

import org.kohsuke.groovy.sandbox.GroovyInterceptor;
import org.kohsuke.groovy.sandbox.GroovyInterceptor.Invoker;

import java.util.Collections;
import java.util.Iterator;

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
        if (receiver==null)
            chain = EMPTY_ITERATOR;
        else
            chain = GroovyInterceptor.getApplicableInterceptors().iterator();
    }

    private static final Iterator<GroovyInterceptor> EMPTY_ITERATOR = Collections.<GroovyInterceptor>emptyList().iterator();
}
