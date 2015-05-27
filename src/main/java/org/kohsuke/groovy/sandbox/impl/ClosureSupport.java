package org.kohsuke.groovy.sandbox.impl;

import groovy.lang.Closure;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helps with sanbox intercepting Closures, which has unique dispatching rules we need to understand.
 *
 * @author Kohsuke Kawaguchi
 */
final class ClosureSupport {
    /**
     * {@link Closure} forwards methods/properties to other objects, depending on the resolution strategy.
     * <p>
     * This method returns the list of non-null objects that should be considered, in that order.
     */
    public static List<Object> targetsOf(Closure receiver) {
        Object owner = receiver.getOwner();
        Object delegate = receiver.getDelegate();

        // Groovy's method dispatch logic for Closure is defined in MetaClassImpl.invokeMethod
        switch (receiver.getResolveStrategy()) {
        case Closure.OWNER_FIRST:
            return of(owner,delegate);
        case Closure.DELEGATE_FIRST:
            return of(delegate,owner);
        case Closure.OWNER_ONLY:
            return of(owner);
        case Closure.DELEGATE_ONLY:
            return of(delegate);
        case Closure.TO_SELF:
        default:
            // fields/methods defined on Closure are checked by SandboxInterceptor,
            // so if we are here it means we will not find the target of the dispatch.
            return Collections.emptyList();
        }
    }

    private static List<Object> of(Object o1, Object o2) {
        // various cases where the list of two become the list of one (or empty)
        if (o1==null)   return of(o2);
        if (o2==null)   return of(o1);
        if (o1==o2)     return of(o1);

        return Arrays.asList(o1, o2);
    }

    private static List<Object> of(Object maybeNull) {
        if (maybeNull==null)
            return Collections.emptyList();
        return Collections.singletonList(maybeNull);
    }

    /**
     * Built-in properties on {@link Closure} that do not follow the delegation rules.
     */
    public static final Set<String> BUILTIN_PROPERTIES = new HashSet<String>(Arrays.asList(
        "delegate",
        "owner",
        "maximumNumberOfParameters",
        "parameterTypes",
        "metaClass",
        "class",
        "directive",
        "resolveStrategy",
        "thisObject"
    ));

}