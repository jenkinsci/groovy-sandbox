package org.kohsuke.groovy.sandbox.robot

import org.kohsuke.groovy.sandbox.GroovyValueFilter

/**
 * This {@link org.kohsuke.groovy.sandbox.GroovyInterceptor} implements a security check.
 *
 * @author Kohsuke Kawaguchi
 */
class RobotSandbox extends GroovyValueFilter {
    @Override
    Object filter(Object o) {
        if (o==null || ALLOWED_TYPES.contains(o.class))
            return o;
        throw new SecurityException("Oops, unexpected type: "+o.class);
    }
    
    private static final Set<Class> ALLOWED_TYPES = [
            Robot,
            Robot.Arm,
            Robot.Leg,
            String,
            Integer,
            Boolean
            // all the primitive types should be OK, but I'm too lazy

            // I'm not adding Class, which rules out all the static method calls
    ] as Set
}
