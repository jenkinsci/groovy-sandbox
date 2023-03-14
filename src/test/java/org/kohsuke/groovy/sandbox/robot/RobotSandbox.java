package org.kohsuke.groovy.sandbox.robot;

import groovy.lang.Closure;
import groovy.lang.Script;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.kohsuke.groovy.sandbox.GroovyValueFilter;

/**
 * This {@link org.kohsuke.groovy.sandbox.GroovyInterceptor} implements a security check.
 *
 * @author Kohsuke Kawaguchi
 */
public class RobotSandbox extends GroovyValueFilter {
    @Override
    public Object filter(Object o) {
        if (o == null || ALLOWED_TYPES.contains(o.getClass()))
            return o;
        if (o instanceof Script || o instanceof Closure)
            return o; // access to properties of compiled groovy script
        throw new SecurityException("Oops, unexpected type: " + o.getClass());
    }

    private static final Set<Class> ALLOWED_TYPES = new HashSet<>(Arrays.asList(
            Robot.class,
            Robot.Arm.class,
            Robot.Leg.class,
            String.class,
            Integer.class,
            Boolean.class
            // all the primitive types should be OK, but I'm too lazy

            // I'm not adding Class, which rules out all the static method calls
    ));
}
