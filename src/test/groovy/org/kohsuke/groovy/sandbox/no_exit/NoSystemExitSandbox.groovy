package org.kohsuke.groovy.sandbox.no_exit

import org.kohsuke.groovy.sandbox.GroovyInterceptor

/**
 * Reject any static calls to {@link System}.
 *
 * @author Kohsuke Kawaguchi
 */
class NoSystemExitSandbox extends GroovyInterceptor {
    @Override
    Object onStaticCall(GroovyInterceptor.Invoker invoker, Class receiver, String method, Object... args) throws Throwable {
        if (receiver==System.class && method=="exit")
            throw new SecurityException("No call on System.exit() please");
        return super.onStaticCall(invoker, receiver, method, args)
    }
}
