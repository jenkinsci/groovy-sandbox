package org.kohsuke.groovy.sandbox.impl;

import org.kohsuke.groovy.sandbox.GroovyInterceptor.Invoker;

/**
 * Packs argument of the super method call for {@link Invoker}
 * @author Kohsuke Kawaguchi
 */
public final class Super {
    final Class senderType;
    final Object receiver;

    public Super(Class senderType, Object receiver) {
        this.senderType = senderType;
        this.receiver = receiver;
    }
}
