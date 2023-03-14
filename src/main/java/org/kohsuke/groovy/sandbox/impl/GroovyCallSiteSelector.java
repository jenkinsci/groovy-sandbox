/*
 * The MIT License
 *
 * Copyright 2020 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.kohsuke.groovy.sandbox.impl;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.codehaus.groovy.reflection.ParameterTypes;
import org.codehaus.groovy.runtime.MetaClassHelper;

public class GroovyCallSiteSelector {

    private GroovyCallSiteSelector() {}

    /**
     * Find the {@link Constructor} that Groovy will invoke at runtime for the given type and arguments.
     *
     * @throws SecurityException if no valid constructor is found, or if the constructor is a synthetic constructor
     * added by SandboxTransformer and the constructor wrapper argument is invalid.
     */
    public static Constructor<?> findConstructor(Class<?> type, Object[] args, Class<?> expectedConstructorWrapper) {
        Constructor<?> c = constructor(type, args);
        if (c == null) {
            throw new SecurityException("Unable to find constructor: " + GroovyCallSiteSelector.formatConstructor(type, args));
        }
        // Check to make sure that users are not directly calling synthetic constructors without going through
        // `Checker.checkedSuperConstructor` or `Checker.checkedThisConstructor`. Part of SECURITY-1754.
        if (isSandboxGeneratedConstructor(c) && (
                expectedConstructorWrapper == null || // Generated constructors should never be called directly, so any call from Checker.checkedConstructor should be rejected
                args.length < 1 || // Should always be false since isSandboxGeneratedConstructor returned true
                args[0] == null || // The wrapper argument must not be null
                args[0].getClass() != expectedConstructorWrapper)) { // The first argument must match the expected wrapper type
            String alternateConstructors = Stream.of(c.getDeclaringClass().getDeclaredConstructors())
                .filter(tempC -> !isSandboxGeneratedConstructor(tempC))
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", "));
            throw new SecurityException("Rejecting illegal call to synthetic constructor: " + c + ". Perhaps you meant to use one of these constructors instead: " + alternateConstructors);
        }
        return c;
    }

    static Constructor<?> constructor(Class<?> receiver, Object[] args) {
        Constructor<?>[] constructors = receiver.getDeclaredConstructors();
        Constructor<?> bestMatch = null;
        ParameterTypes bestMatchParamTypes = null;
        Class<?>[] argTypes = MetaClassHelper.convertToTypeArray(args);
        for (Constructor<?> c : constructors) {
            ParameterTypes cParamTypes = new ParameterTypes(c.getParameterTypes());
            if (cParamTypes.isValidMethod(argTypes)) {
                if (bestMatch == null || isMoreSpecific(cParamTypes, bestMatchParamTypes, argTypes)) {
                    bestMatch = c;
                    bestMatchParamTypes = cParamTypes;
                }
            }
        }
        if (bestMatch != null) {
            return bestMatch;
        }

        // Only check for the magic Map constructor if we haven't already found a real constructor.
        // Also note that this logic is derived from how Groovy itself decides to use the magic Map constructor, at
        // MetaClassImpl#invokeConstructor(Class, Object[]).
        if (args.length == 1 && args[0] instanceof Map) {
            for (Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0 && !c.isVarArgs()) {
                    return c;
                }
            }
        }

        return null;
    }

    public static boolean isMoreSpecific(ParameterTypes paramsForCandidate, ParameterTypes paramsForBaseline, Class<?>[] argTypes) {
        long candidateDistance = MetaClassHelper.calculateParameterDistance(argTypes, paramsForCandidate);
        long currentBestDistance = MetaClassHelper.calculateParameterDistance(argTypes, paramsForBaseline);
        return candidateDistance < currentBestDistance;
    }

    private static final Class<?>[] SYNTHETIC_CONSTRUCTOR_PARAMETER_TYPES = new Class<?>[] {
        Checker.SuperConstructorWrapper.class,
        Checker.ThisConstructorWrapper.class,
    };

    /**
     * @return true if this constructor is one that was added by groovy-sandbox in {@code SandboxTransformer.processConstructors}
     * specifically to be able to intercept calls to super in constructors.
     */
    private static boolean isSandboxGeneratedConstructor(Constructor<?> c) {
        if (!c.isSynthetic()) {
            return false;
        }
        Class<?>[] parameterTypes = c.getParameterTypes();
        if (parameterTypes.length > 0) {
            for (Class<?> syntheticParamType : SYNTHETIC_CONSTRUCTOR_PARAMETER_TYPES) {
                if (parameterTypes[0] == syntheticParamType) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String formatConstructor(Class<?> c, Object... args) {
        return "new " + getName(c) + printArgumentTypes(args);
    }

    private static String printArgumentTypes(Object[] args) {
        StringBuilder b = new StringBuilder();
        for (Object arg : args) {
            b.append(' ');
            b.append(getName(arg));
        }
        return b.toString();
    }

    public static String getName(Object o) {
        return o == null ? "null" : getName(o.getClass());
    }

    private static String getName(Class<?> c) {
        Class<?> e = c.getComponentType();
        if (e == null) {
            return c.getName();
        } else {
            return getName(e) + "[]";
        }
    }

}
