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

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.kohsuke.groovy.sandbox.GroovyInterceptor;
import org.kohsuke.groovy.sandbox.GroovyInterceptor.Invoker;
/**
 * An interceptor used by {@link Invoker} to reject any sandbox-transformed code that is executed when
 * {@link GroovyInterceptor#getApplicableInterceptors} is empty, under the assumption that there is no legitimate
 * reason to run sandbox-transformed code outside of the sandbox.<p>
 *
 * Parameters of overridden methods with type {@link Object} are assumed to be unsafe and must be handled carefully to
 * avoid security vulnerabilities. Safe operations include casting these objects to known-safe final classes such as
 * {@link String}, or calling known-safe final methods such as {@link Object#getClass}.
 */
public class RejectEverythingInterceptor extends GroovyInterceptor {

    @Override
    public Object onMethodCall(Invoker invoker, Object receiver, String method, Object... args) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed method call: " + getClassName(receiver) + "." + method + getArgumentClassNames(args));
    }

    @Override
    public Object onStaticCall(Invoker invoker, Class receiver, String method, Object... args) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed static method call: " + getClassName(receiver) + "." + method + getArgumentClassNames(args));
    }

    @Override
    public Object onNewInstance(Invoker invoker, Class receiver, Object... args) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed constructor call: " + getClassName(receiver) + getArgumentClassNames(args));
    }

    @Override
    public Object onSuperCall(Invoker invoker, Class senderType, Object receiver, String method, Object... args) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed super method call: " + getClassName(receiver) + "." + method + getArgumentClassNames(args));
    }

    @Override
    public void onSuperConstructor(Invoker invoker, Class receiver, Object... args) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed super constructor call: " + getClassName(receiver) + getArgumentClassNames(args));
    }

    @Override
    public Object onGetProperty(Invoker invoker, Object receiver, String property) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed property get: " + getClassName(receiver) + "." + property);
    }

    @Override
    public Object onSetProperty(Invoker invoker, Object receiver, String property, Object value) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed property set: " + getClassName(receiver) + "." + property + " = " + getClassName(value));
    }

    @Override
    public Object onGetAttribute(Invoker invoker, Object receiver, String attribute) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed attribute get: " + getClassName(receiver) + "." + attribute);
    }

    @Override
    public Object onSetAttribute(Invoker invoker, Object receiver, String attribute, Object value) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed attribute set: " + getClassName(receiver) + "." + attribute + " = " + getClassName(value));
    }

    @Override
    public Object onGetArray(Invoker invoker, Object receiver, Object index) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed array get: " + getClassName(receiver) + "[" + getArrayIndex(index) + "]");
    }

    @Override
    public Object onSetArray(Invoker invoker, Object receiver, Object index, Object value) throws Throwable {
        throw new SecurityException("Rejecting unsandboxed array set: " + getClassName(receiver) + "[" + getArrayIndex(index) + "] = " + getClassName(value));
    }

    private static String getClassName(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof Class) {
            return ((Class) value).getName();
        } else {
            return value.getClass().getName();
        }
    }

    private static String getArgumentClassNames(Object[] args) {
        return Stream.of(args)
                .map(RejectEverythingInterceptor::getClassName)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static String getArrayIndex(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof Integer) {
            return value.toString();
        } else {
            return value.getClass().getName();
        }
    }

}
