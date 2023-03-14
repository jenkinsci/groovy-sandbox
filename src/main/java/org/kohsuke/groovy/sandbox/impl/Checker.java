package org.kohsuke.groovy.sandbox.impl;

import groovy.lang.Closure;
import groovy.lang.EmptyRange;
import groovy.lang.GString;
import groovy.lang.GroovyRuntimeException;
import groovy.lang.IntRange;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassImpl;
import groovy.lang.MetaMethod;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import groovy.lang.ObjectRange;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.codehaus.groovy.classgen.asm.BinaryExpressionHelper;
import org.codehaus.groovy.classgen.asm.UnaryExpressionHelper;
import org.codehaus.groovy.reflection.ParameterTypes;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.ResourceGroovyMethods;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;
import org.codehaus.groovy.syntax.Types;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.codehaus.groovy.runtime.InvokerHelper.getMetaClass;
import static org.codehaus.groovy.runtime.MetaClassHelper.convertToTypeArray;
import static org.kohsuke.groovy.sandbox.impl.ClosureSupport.BUILTIN_PROPERTIES;

/**
 * Intercepted Groovy script calls into this class.
 *
 * @author Kohsuke Kawaguchi
 */
public class Checker {
    private static final Object[] EMPTY_ARRAY = new Object[0];

    /*TODO: specify the proper owner value*/
    private static CallSite fakeCallSite(String method) {
        CallSiteArray csa = new CallSiteArray(Checker.class, new String[]{method});
        return csa.array[0];
    }


    // TODO: we need an owner class
    public static Object checkedCall(Object _receiver, boolean safe, boolean spread, String _method, Object[] _args) throws Throwable {
        if (safe && _receiver==null)     return null;
        _args = fixNull(_args);
        if (spread) {
            List<Object> r = new ArrayList<Object>();
            Iterator itr = InvokerHelper.asIterator(_receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it!=null)
                    r.add(checkedCall(it, true, false, _method, _args));
            }
            return r;
        } else {
            // the first try
            // but this fails to properly intercept 5.class.forName('java.lang.String')
//            def m = receiver.&"${method}";
//            return m(args)

// from http://groovy.codehaus.org/Using+invokeMethod+and+getProperty
            // but it still doesn't resolve static method
//            def m = receiver.metaClass.getMetaMethod(method.toString(),args)
//            return m.invoke(receiver,args);

            /*
                When Groovy evaluates expression like "FooClass.bar()", it routes the call here.
                (as to why we cannot rewrite the expression to statically route the call to checkedStaticCall,
                consider "def x = FooClass.class; x.bar()", which still resolves to FooClass.bar() if it is present!)

                So this is where we really need to distinguish a call to a static method defined on the class
                vs an instance method call to a method on java.lang.Class.

                Then the question is how do we know when to do which, which one takes precedence, etc.
                Groovy doesn't commit to any specific logic at the level of MetaClass. In MetaClassImpl,
                the logic is defined in MetaClassImpl.pickStaticMethod.

                BTW, this makes me wonder if StaticMethodCallExpression is used at all in AST, and it looks like
                this is no longer used.
             */

            if (_receiver instanceof Class) {
                Thunk maybeReplacement = findCheckedReplacement((Class<?>)_receiver, _method, _args);
                if (maybeReplacement != null) {
                    return maybeReplacement.call();
                }

                MetaClass mc = getMetaClass((Class) _receiver);
                if (mc instanceof MetaClassImpl) {
                    MetaClassImpl mci = (MetaClassImpl) mc;
                    MetaMethod m = mci.retrieveStaticMethod(_method,_args);
                    if (m!=null) {
                        if (m.isStatic()) {
                            // Foo.forName() still finds Class.forName() method, so we need to test for that
                            if (m.getDeclaringClass().getTheClass()==Class.class)
                                return checkedStaticCall(Class.class,_method,_args);
                            else
                                return checkedStaticCall((Class)_receiver,_method,_args);
                        }
                    }
                }
            }

            if (_receiver instanceof Closure) {
                if (_method.equals("invokeMethod") && isInvokingMethodOnClosure(_receiver,_method,_args)) {
                    // if someone is calling closure.invokeMethod("foo",args), map that back to closure.foo("args")
                    _method = _args[0].toString();
                    _args = (Object[])_args[1];
                }

                MetaMethod m = getMetaClass(_receiver).pickMethod(_method, convertToTypeArray(_args));
                if (m==null) {
                    // if we are trying to call a method that's actually defined in Closure, then we'll get non-null 'm'
                    // in that case, treat it like normal method call

                    // if we are here, that means we are trying to delegate the call to 'owner', 'delegate', etc.
                    // is going to, and check access accordingly. Groovy's corresponding code is in MetaClassImpl.invokeMethod(...)
                    List<Object> targets = ClosureSupport.targetsOf((Closure) _receiver);

                    Class[] argTypes = convertToTypeArray(_args);

                    // in the first phase, we look for exact method match
                    for (Object candidate : targets) {
                        if (InvokerHelper.getMetaClass(candidate).pickMethod(_method,argTypes)!=null)
                            return checkedCall(candidate,false,false, _method, _args);
                    }
                    // in the second phase, we try to call invokeMethod on them
                    for (Object candidate : targets) {
                        try {
                            return checkedCall(candidate,false,false,"invokeMethod",new Object[]{_method,_args});
                        } catch (MissingMethodException e) {
                            // try the next one
                        }
                    }
                    // we tried to be smart about Closure.invokeMethod, but we are just not finding any.
                    // so we'll have to treat this like any other method.
                }
            }

            /*
                The third try:

                Groovyc produces one CallSites instance per a call site, then
                pack them into a single array and put them as a static field in a class.
                this encapsulates the actual method dispatching logic.

                Ideally we'd like to get the CallSite object that would have been used for a call,
                but because it's packed in an array and the index in that array is determined
                only at the code generation time, I can't get the access to it.

                So here we are faking it by creating a new CallSite object.
             */
            return new VarArgInvokerChain(_receiver) {
                public Object call(Object receiver, String method, Object... args) throws Throwable {
                    if (chain.hasNext())
                        return chain.next().onMethodCall(this,receiver,method,args);
                    else
                        return fakeCallSite(method).call(receiver,args);
                }
            }.call(_receiver,_method,_args);
        }
    }

    /**
     * Are we trying to invoke a method defined on Closure or its super type?
     * (If so, we'll need to chase down which method we are actually invoking.)
     *
     * <p>
     * Used for invokeMethod/getProperty/setProperty.
     *
     * <p>
     * If the receiver overrides this method, return false since we don't know how such methods behave.
     */
    private static boolean isInvokingMethodOnClosure(Object receiver, String method, Object... args) {
        if (receiver instanceof Closure) {
            MetaMethod m = getMetaClass(receiver).pickMethod(method, convertToTypeArray(args));
            if (m!=null && m.getDeclaringClass().isAssignableFrom(Closure.class))
                return true;
        }
        return false;
    }

    public static Object checkedStaticCall(Class _receiver, String _method, Object[] _args) throws Throwable {
        _args = fixNull(_args);
        Thunk maybeReplacement = findCheckedReplacement((Class<?>)_receiver, _method, _args);
        if (maybeReplacement != null) {
            return maybeReplacement.call();
        }
        return new VarArgInvokerChain(_receiver) {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onStaticCall(this,(Class)receiver,method,args);
                else
                    return fakeCallSite(method).callStatic((Class)receiver,args);
            }
        }.call(_receiver, _method, _args);
    }

    public static Object checkedConstructor(Class _type, Object[] _args) throws Throwable {
        // Make sure that this is not an illegal call to a synthetic constructor.
        GroovyCallSiteSelector.findConstructor(_type, _args, null);
        return new VarArgInvokerChain(_type) {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onNewInstance(this,(Class)receiver,args);
                else
                    // I believe the name is unused
                    return fakeCallSite("<init>").callConstructor((Class)receiver,args);
            }
        }.call(_type,null,fixNull(_args));
    }

    public static Object checkedSuperCall(Class _senderType, Object _receiver, String _method, Object[] _args) throws Throwable {
        Super s = new Super(_senderType, _receiver);
        return new VarArgInvokerChain(s) {
            public Object call(Object _s, String method, Object... args) throws Throwable {
                Super s = (Super)_s;
                if (chain.hasNext()) {
                    return chain.next().onSuperCall(this, s.senderType, s.receiver, method, args);
                } else {
                    try {
                        MetaClass mc = InvokerHelper.getMetaClass(s.receiver.getClass());
                        return mc.invokeMethod(s.senderType.getSuperclass(), s.receiver, method, args, true, true);
                    } catch (GroovyRuntimeException gre) {
                        throw ScriptBytecodeAdapter.unwrap(gre);
                    }
                }
            }
        }.call(s,_method,fixNull(_args));
    }

    public static class SuperConstructorWrapper {
        private final Object[] args;
        SuperConstructorWrapper(Object[] args) {
            this.args = args;
        }
        public Object arg(int idx) {
            return args[idx];
        }
    }

    public static SuperConstructorWrapper checkedSuperConstructor(Class<?> thisClass, Class<?> superClass, Object[] superCallArgs, Object[] constructorArgs, Class<?>[] constructorParamTypes) throws Throwable {
        // Make sure that the call to this synthetic constructor is not illegal.
        GroovyCallSiteSelector.findConstructor(superClass, superCallArgs, SuperConstructorWrapper.class);
        explicitConstructorCallSanity(thisClass, SuperConstructorWrapper.class, constructorArgs, constructorParamTypes);
        new VarArgInvokerChain(superClass) {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext()) {
                    chain.next().onSuperConstructor(this, superClass, args);
                }
                return null;
            }
        }.call(superClass, null, fixNull(superCallArgs));
        return new SuperConstructorWrapper(superCallArgs);
    }

    public static class ThisConstructorWrapper {
        private final Object[] args;
        ThisConstructorWrapper(Object[] args) {
            this.args = args;
        }
        public Object arg(int idx) {
            return args[idx];
        }
    }

    public static ThisConstructorWrapper checkedThisConstructor(final Class<?> clazz, Object[] thisCallArgs, Object[] constructorArgs, Class<?>[] constructorParamTypes) throws Throwable {
        // Make sure that the call to this synthetic constructor is not illegal.
        GroovyCallSiteSelector.findConstructor(clazz, thisCallArgs, ThisConstructorWrapper.class);
        explicitConstructorCallSanity(clazz, ThisConstructorWrapper.class, constructorArgs, constructorParamTypes);
        new VarArgInvokerChain(clazz) {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext()) {
                    chain.next().onNewInstance(this, clazz, args);
                }
                return null;
            }
        }.call(clazz, null, fixNull(thisCallArgs));
        return new ThisConstructorWrapper(thisCallArgs);
    }

    /**
     * Makes sure that explicit constructor calls inside of synthetic constructors will go to the intended constructor
     * at runtime (Part of SECURITY-1754).
     * See {@code SandboxTransformerTest.blocksUnintendedCallsToNonSyntheticConstructors()} for an example of this problem.
     */
    private static void explicitConstructorCallSanity(Class<?> thisClass, Class<?> wrapperClass, Object[] argsExcludingWrapper, Class<?>[] paramsIncludingWrapper) {
        // Construct argument types for the explicit constructor call.
        Class<?>[] argTypes = new Class<?>[argsExcludingWrapper.length + 1];
        argTypes[0] = wrapperClass;
        System.arraycopy(MetaClassHelper.convertToTypeArray(argsExcludingWrapper), 0, argTypes, 1, argsExcludingWrapper.length);
        // Find the constructor that the sandbox is expecting will be called.
        Constructor<?> expectedConstructor = null;
        try {
            expectedConstructor = thisClass.getDeclaredConstructor(paramsIncludingWrapper);
        } catch (NoSuchMethodException e) {
            // The original constructor that made it necessary to create a synthetic constructor should always exist.
            throw new AssertionError("Unable to find original constructor", e);
        }
        ParameterTypes expectedParamTypes = new ParameterTypes(paramsIncludingWrapper);
        for (Constructor<?> c : thisClass.getDeclaredConstructors()) {
            // Make sure that no other constructor matches the arguments better than the constructor we are expecting to
            // call, because otherwise that would be the constructor that would actually be invoked.
            ParameterTypes cParamTypes = new ParameterTypes(c.getParameterTypes());
            if (!c.equals(expectedConstructor) && cParamTypes.isValidMethod(argTypes) && GroovyCallSiteSelector.isMoreSpecific(cParamTypes, expectedParamTypes, argTypes)) {
                throw new SecurityException("Rejecting unexpected invocation of constructor: " + c + ". Expected to invoke synthetic constructor: " + expectedConstructor);
            }
        }
    }

    public static Object checkedGetProperty(final Object _receiver, boolean safe, boolean spread, Object _property) throws Throwable {
        if (safe && _receiver==null)     return null;

        if (spread || (_receiver instanceof Collection && !BUILTIN_PROPERTIES.contains(_property))) {
            List<Object> r = new ArrayList<Object>();
            Iterator itr = InvokerHelper.asIterator(_receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it!=null)
                    r.add(checkedGetProperty(it,true,false,_property));
            }
            return r;
        }
// 1st try: do the same call site stuff
//            return fakeCallSite(property.toString()).callGetProperty(receiver);

        if (isInvokingMethodOnClosure(_receiver, "getProperty", _property) && !BUILTIN_PROPERTIES.contains(_property)) {
            // if we are trying to invoke Closure.getProperty(),
            // we want to find out where the call is going to, and check that target
            MissingPropertyException x=null;
            for (Object candidate : ClosureSupport.targetsOf((Closure) _receiver)) {
                try {
                    return checkedGetProperty(candidate, false, false, _property);
                } catch (MissingPropertyException e) {
                    x = e;
                    // try the next one
                }
            }
            if (x!=null)    throw x;
            throw new MissingPropertyException(_property.toString(), _receiver.getClass());
        }
        if (_receiver instanceof Map) {
            /*
                MetaClassImpl.getProperty looks for Map subtype and handles it as Map.get call,
                so dispatch that call accordingly.
             */
            return checkedCall(_receiver,false,false,"get",new Object[]{_property});
        }

        return new ZeroArgInvokerChain(_receiver) {
            public Object call(Object receiver, String property) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onGetProperty(this,receiver,property);
                else
                    return ScriptBytecodeAdapter.getProperty(null, receiver, property);
            }
        }.call(_receiver,_property.toString());
    }

    public static Object checkedSetProperty(Object _receiver, Object _property, boolean safe, boolean spread, int op, Object _value) throws Throwable {
        if (op!=Types.ASSIGN) {
            // a compound assignment operator is decomposed into get+op+set
            // for example, a.x += y  => a.x=a.x+y
            Object v = checkedGetProperty(_receiver, safe, spread, _property);
            return checkedSetProperty(_receiver, _property, safe, spread, Types.ASSIGN,
                    checkedBinaryOp(v, Ops.compoundAssignmentToBinaryOperator(op), _value));
        }
        if (safe && _receiver==null)     return _value;
        if (spread) {
            Iterator itr = InvokerHelper.asIterator(_receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it!=null)
                    checkedSetProperty(it, _property, true, false, op, _value);
            }
            return _value;
        }

        if (isInvokingMethodOnClosure(_receiver, "setProperty", _property, _value) && !BUILTIN_PROPERTIES.contains(_property)) {
            // if we are trying to invoke Closure.setProperty(),
            // we want to find out where the call is going to, and check that target
            GroovyRuntimeException x=null;
            for (Object candidate : ClosureSupport.targetsOf((Closure) _receiver)) {
                try {
                    return checkedSetProperty(candidate, _property, false, false, op, _value);
                } catch (GroovyRuntimeException e) {
                    // Cathing GroovyRuntimeException feels questionable, but this is how Groovy does it in
                    // Closure.setPropertyTryThese().
                    x = e;
                    // try the next one
                }
            }
            if (x!=null)
                throw x;
            throw new MissingPropertyException(_property.toString(), _receiver.getClass());
        }
        if (_receiver instanceof Map) {
            /*
                MetaClassImpl.setProperty looks for Map subtype and handles it as Map.put call,
                so dispatch that call accordingly.
             */
            checkedCall(_receiver,false,false,"put",new Object[]{_property,_value});
            return _value;
        }

        return new SingleArgInvokerChain(_receiver) {
            public Object call(Object receiver, String property, Object value) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onSetProperty(this,receiver,property,value);
                else {
                    // according to AsmClassGenerator this is how the compiler maps it to
                    // TODO: There is an implicit cast here. Very awkward for us to handle because we have to fully
                    // understand the meaning of receiver.property to know the target type of the cast.
                    // For now, API consumers must handle it themselves in onSetProperty.
                    ScriptBytecodeAdapter.setProperty(value,null,receiver,property);
                    return value;
                }
            }
        }.call(_receiver,_property.toString(),_value);
    }

    public static Object checkedGetAttribute(Object _receiver, boolean safe, boolean spread, Object _property) throws Throwable {
        if (safe && _receiver==null)     return null;
        if (spread) {
            List<Object> r = new ArrayList<Object>();
            Iterator itr = InvokerHelper.asIterator(_receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it!=null)
                    r.add(checkedGetAttribute(it, true, false, _property));
            }
            return r;
        } else {
            return new ZeroArgInvokerChain(_receiver) {
                public Object call(Object receiver, String property) throws Throwable {
                    if (chain.hasNext())
                        return chain.next().onGetAttribute(this,receiver,property);
                    else
                        // according to AsmClassGenerator this is how the compiler maps it to
                        return ScriptBytecodeAdapter.getField(null,receiver,property);
                }
            }.call(_receiver,_property.toString());
        }
    }

    /**
     * Intercepts the attribute assignment of the form "receiver.@property = value"
     *
     * @param op
     *      One of the assignment operators of {@link Types}
     */
    public static Object checkedSetAttribute(Object _receiver, Object _property, boolean safe, boolean spread, int op, Object _value) throws Throwable {
        if (op!=Types.ASSIGN) {
            // a compound assignment operator is decomposed into get+op+set
            // for example, a.@x += y  => a.@x=a.@x+y
            Object v = checkedGetAttribute(_receiver, safe, spread, _property);
            return checkedSetAttribute(_receiver, _property, safe, spread, Types.ASSIGN,
                    checkedBinaryOp(v, Ops.compoundAssignmentToBinaryOperator(op), _value));
        }
        if (safe && _receiver==null)     return _value;
        if (spread) {
            Iterator itr = InvokerHelper.asIterator(_receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it!=null)
                    checkedSetAttribute(it,_property,true,false,op,_value);
            }
        } else {
            return new SingleArgInvokerChain(_receiver) {
                public Object call(Object receiver, String property, Object value) throws Throwable {
                    if (chain.hasNext())
                        return chain.next().onSetAttribute(this,receiver,property,value);
                    else {
                        ScriptBytecodeAdapter.setField(value,null,receiver,property);
                        return value;
                    }
                }
            }.call(_receiver,_property.toString(),_value);
        }
        return _value;
    }

    public static Object checkedGetArray(Object _receiver, Object _index) throws Throwable {
        return new SingleArgInvokerChain(_receiver) {
            public Object call(Object receiver, String method, Object index) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onGetArray(this,receiver,index);
                else
                    // BinaryExpressionHelper.eval maps this to "getAt" call
                    return fakeCallSite("getAt").call(receiver,index);
            }
        }.call(_receiver,null,_index);
    }

    /**
     * Intercepts the array assignment of the form "receiver[index] = value"
     *
     * @param op
     *      One of the assignment operators of {@link Types}
     */
    public static Object checkedSetArray(Object _receiver, Object _index, int op, Object _value) throws Throwable {
        if (op!=Types.ASSIGN) {
            // a compound assignment operator is decomposed into get+op+set
            // for example, a[x] += y  => a[x]=a[x]+y
            Object v = checkedGetArray(_receiver, _index);
            return checkedSetArray(_receiver, _index, Types.ASSIGN,
                    checkedBinaryOp(v, Ops.compoundAssignmentToBinaryOperator(op), _value));
        } else {
            // Note that in regular Groovy, value is cast to the component type of the array, but this code does not do that.
            return new TwoArgInvokerChain(_receiver) {
                public Object call(Object receiver, String method, Object index, Object value) throws Throwable {
                    if (chain.hasNext())
                        return chain.next().onSetArray(this,receiver,index,value);
                    else {
                        // BinaryExpressionHelper.assignToArray maps this to "putAt" call
                        fakeCallSite("putAt").call(receiver,index,value);
                        return value;
                    }
                }
            }.call(_receiver,null,_index,_value);
        }
    }

    /**
     * a[i]++ / a[i]--
     *
     * @param op
     *      "next" for ++, "previous" for --. These names are defined by Groovy.
     */
    public static Object checkedPostfixArray(Object r, Object i, String op) throws Throwable {
        Object o = checkedGetArray(r, i);
        Object n = checkedCall(o, false, false, op, new Object[0]);
        checkedSetArray(r,i,Types.ASSIGN,n);
        return o;
    }

    /**
     * ++a[i] / --a[i]
     */
    public static Object checkedPrefixArray(Object r, Object i, String op) throws Throwable {
        Object o = checkedGetArray(r, i);
        Object n = checkedCall(o, false, false, op, new Object[0]);
        checkedSetArray(r,i,Types.ASSIGN,n);
        return n;
    }

    /**
     * a.x++ / a.x--
     */
    public static Object checkedPostfixProperty(Object receiver, Object property, boolean safe, boolean spread, String op) throws Throwable {
        Object o = checkedGetProperty(receiver, safe, spread, property);
        Object n = checkedCall(o, false, false, op, new Object[0]);
        checkedSetProperty(receiver, property, safe, spread, Types.ASSIGN, n);
        return o;
    }

    /**
     * ++a.x / --a.x
     */
    public static Object checkedPrefixProperty(Object receiver, Object property, boolean safe, boolean spread, String op) throws Throwable {
        Object o = checkedGetProperty(receiver, safe, spread, property);
        Object n = checkedCall(o, false, false, op, new Object[0]);
        checkedSetProperty(receiver, property, safe, spread, Types.ASSIGN, n);
        return n;
    }

    /**
     * a.@x++ / a.@x--
     */
    public static Object checkedPostfixAttribute(Object receiver, Object property, boolean safe, boolean spread, String op) throws Throwable {
        Object o = checkedGetAttribute(receiver, safe, spread, property);
        Object n = checkedCall(o, false, false, op, new Object[0]);
        checkedSetAttribute(receiver, property, safe, spread, Types.ASSIGN, n);
        return o;
    }

    /**
     * ++a.@x / --a.@x
     */
    public static Object checkedPrefixAttribute(Object receiver, Object property, boolean safe, boolean spread, String op) throws Throwable {
        Object o = checkedGetAttribute(receiver, safe, spread, property);
        Object n = checkedCall(o, false, false, op, new Object[0]);
        checkedSetAttribute(receiver, property, safe, spread, Types.ASSIGN, n);
        return n;
    }

    /**
     * Intercepts the binary expression of the form {@code lhs op rhs} like {@code lhs+rhs}, {@code lhs>>rhs}, etc.
     *
     * In Groovy, binary operators are method calls.
     *
     * @param op
     *      One of the binary operators of {@link Types}
     * @see BinaryExpressionHelper#evaluateBinaryExpressionWithAssignment
     */
    public static Object checkedBinaryOp(Object lhs, int op, Object rhs) throws Throwable {
        return checkedCall(lhs,false,false,Ops.binaryOperatorMethods(op),new Object[]{rhs});
    }

    /**
     * Intercepts unary expressions of the form {@code ~value}.
     *
     * In Groovy, this operator may result in a call to a method named {@code bitwiseNegate} on the receiver or to one
     * of the {@code DefaultGroovyMethods.bitwiseNegate} overloads.
     *
     * @see UnaryExpressionHelper#writeBitwiseNegate
     * @see ScriptBytecodeAdapter#bitwiseNegate
     * @see InvokerHelper#bitwiseNegate
     */
    public static Object checkedBitwiseNegate(Object value) throws Throwable {
        if (value instanceof Integer) {
            return ~((Integer)value);
        }
        if (value instanceof Long) {
            return ~((Long)value);
        }
        if (value instanceof BigInteger) {
            return checkedCall(value, false, false, "not", new Object[]{});
        }
        if (value instanceof String) {
            // value is a regular expression.
            return checkedStaticCall(StringGroovyMethods.class, "bitwiseNegate", new Object[]{ value.toString() });
        }
        if (value instanceof GString) {
            // value is a regular expression.
            return checkedStaticCall(StringGroovyMethods.class, "bitwiseNegate", new Object[]{ value.toString() });
        }
        if (value instanceof ArrayList) { // ArrayList is the exact type that Groovy checks in InvokerHelper.bitwiseNegate.
            // value is a list.
            List newlist = new ArrayList();
            for (Object element : ((ArrayList) value)) {
                newlist.add(checkedBitwiseNegate(element));
            }
            return newlist;
        }
        return checkedCall(value, false, false, "bitwiseNegate", EMPTY_ARRAY);
    }

    /**
     * Intercepts range expressions of the form {@code [x..y]} or {@code [x..<y]}.
     *
     * If the from and to expressions are not integers, this operator constructs a {@link ObjectRange}, which involves
     * calling various methods on the endpoints during the construction of the range and whenever the range is used.
     * We handle this like we do interfaces in {@link #preCheckedCast} and intercept all of the methods that may be
     * called before allowing the range to be constructed rather than trying to implement our own range type that is
     * sandbox-aware.
     *
     * @see ScriptBytecodeAdapter#createRange
     * @see ObjectRange
     */
    public static Object checkedCreateRange(Object from, Object to, boolean inclusive) throws Throwable {
        if (from instanceof Integer && to instanceof Integer) {
            if (inclusive || from != to) {
                return checkedConstructor(IntRange.class, new Object[] { inclusive, from, to });
            }
            // Fallthrough for EmptyRange
        }
        if (!inclusive) {
            if (Boolean.TRUE.equals(checkedComparison(from, Types.COMPARE_EQUAL, to))) {
                // Unchecked cast to Comparable matches the behavior of ScriptBytecodeAdapter.createRange.
                return checkedConstructor(EmptyRange.class, new Object[] { (Comparable)from });
            }
            if (Boolean.TRUE.equals(checkedComparison(from, Types.COMPARE_GREATER_THAN, to))) {
                to = checkedCall(to, false, false, "next", EMPTY_ARRAY);
            } else {
                to = checkedCall(to, false, false, "previous", EMPTY_ARRAY);
            }
        }
        // Unlike IntRange and EmptyRange, ObjectRange calls various methods reflectively, so we cannot allow users to
        // create an ObjectRange directly. Instead, we intercept potential reflective calls and create the range ourselves.
        interceptRangeMethods((Comparable)from);
        interceptRangeMethods((Comparable)to);
        // Unchecked cast to Comparable matches the behavior of ScriptBytecodeAdapter.createRange.
        return new ObjectRange((Comparable)from, (Comparable)to);
    }

    /**
     * {@link ObjectRange} calls various methods on its endpoints internally depending on how it is used.
     *
     * Rather than trying to intercept those calls as they happen (by implementing a sandbox-aware {@link ObjectRange} subclass),
     * we intercept all of the possible calls that might be made before we even create the range.
     */
    private static void interceptRangeMethods(Comparable value) throws Throwable {
        if (value == null) {
            return;
        }
        for (String method : new String[] { "compareTo", "next", "previous" }) {
            Object[] args = new Object[0];
            if (method.equals("compareTo")) {
                args = new Object[]{ null };
            }
            new VarArgInvokerChain(value) {
                public Object call(Object receiver, String method, Object... args) throws Throwable {
                    if (chain.hasNext()) {
                        return chain.next().onMethodCall(this, receiver, method, args);
                    } else {
                        return null;
                    }
                }
            }.call(value, method, args);
        }
    }

    /**
     * Intercepts unary expressions of the form {@code -value}.
     *
     * In Groovy, this operator may result in a call to a method named {@code negative} on the receiver or to one
     * of the {@code DefaultGroovyMethods.unaryMinus} overloads.
     *
     * @see UnaryExpressionHelper#writeUnaryMinus
     * @see ScriptBytecodeAdapter#unaryMinus
     * @see InvokerHelper#unaryMinus
     */
    public static Object checkedUnaryMinus(Object value) throws Throwable {
        if (value instanceof Integer) {
            Integer number = (Integer) value;
            return -number;
        }
        if (value instanceof Long) {
            Long number = (Long) value;
            return -number;
        }
        if (value instanceof BigInteger) {
            return checkedCall(value, false, false, "negate", new Object[]{});
        }
        if (value instanceof BigDecimal) {
            return checkedCall(value, false, false, "negate", new Object[]{});
        }
        if (value instanceof Double) {
            Double number = (Double) value;
            return -number;
        }
        if (value instanceof Float) {
            Float number = (Float) value;
            return -number;
        }
        if (value instanceof Short) {
            Short number = (Short) value;
            return (short) -number;
        }
        if (value instanceof Byte) {
            Byte number = (Byte) value;
            return (byte) -number;
        }
        if (value instanceof ArrayList) { // ArrayList is the exact type that Groovy checks in InvokerHelper.unaryMinus.
            // value is a list.
            List newlist = new ArrayList();
            for (Object element : ((ArrayList) value)) {
                newlist.add(checkedUnaryMinus(element));
            }
            return newlist;
        }
        return checkedCall(value, false, false, "negative", EMPTY_ARRAY);
    }

    /**
     * Intercepts unary expressions of the form {@code +value}.
     *
     * In Groovy, this operator may result in a call to a method named {@code positive} on the receiver or to one
     * of the {@code DefaultGroovyMethods.unaryMinus} overloads.
     *
     * @see UnaryExpressionHelper#writeUnaryPlus
     * @see ScriptBytecodeAdapter#unaryPlus
     * @see InvokerHelper#unaryPlus
     */
    public static Object checkedUnaryPlus(Object value) throws Throwable {
        if (value instanceof Integer ||
                value instanceof Long ||
                value instanceof BigInteger ||
                value instanceof BigDecimal ||
                value instanceof Double ||
                value instanceof Float ||
                value instanceof Short ||
                value instanceof Byte) {
            return value;
        }
        if (value instanceof ArrayList) { // ArrayList is the exact type that Groovy checks in InvokerHelper.unaryPlus.
            // value is a list.
            List newlist = new ArrayList();
            for (Object element : ((ArrayList) value)) {
                newlist.add(checkedUnaryPlus(element));
            }
            return newlist;
        }
        return checkedCall(value, false, false, "positive", EMPTY_ARRAY);
    }

    /**
     * A compare method that invokes a.equals(b) or a.compareTo(b)==0
     */
    public static Object checkedComparison(Object lhs, final int op, Object rhs) throws Throwable {
        if (lhs==null) {// bypass the checker if lhs is null, as it will not result in any calls that will require protection anyway
            return InvokerHelper.invokeStaticMethod(ScriptBytecodeAdapter.class,
                    Ops.binaryOperatorMethods(op), new Object[]{null, rhs});
        }

        return new SingleArgInvokerChain(lhs) {
            public Object call(Object lhs, String method, Object rhs) throws Throwable {
                if (chain.hasNext()) {
                    // based on what ScriptBytecodeAdapter actually does
                    return chain.next().onMethodCall(this, lhs,
                            lhs instanceof Comparable ? "compareTo" : "equals",rhs);
                } else {
                    return InvokerHelper.invokeStaticMethod(ScriptBytecodeAdapter.class,
                            Ops.binaryOperatorMethods(op), new Object[]{lhs,rhs});
                }
            }
        }.call(lhs, null, rhs);
    }

    /**
     * Runs {@link ScriptBytecodeAdapter#asType} but only after giving interceptors the chance to reject any possible interface methods as applied to the receiver.
     * For example, might run {@code receiver.method1(null, false)} and {@code receiver.method2(0, null)} if methods with matching signatures were defined in the interfaces.
     */
    public static Object checkedCast(Class<?> clazz, Object exp, boolean ignoreAutoboxing, boolean coerce, boolean strict) throws Throwable {
        return preCheckedCast(clazz, exp, ignoreAutoboxing, coerce, strict).call();
    }

    /** Same as {@link Callable} but can throw {@link Throwable}. */
    @FunctionalInterface
    public interface Thunk {
        Object call() throws Throwable;
    }

    public static Thunk preCheckedCast(Class<?> clazz, Object exp, boolean ignoreAutoboxing, boolean coerce, boolean strict) throws Throwable {
        // Note: Be careful calling methods on exp here since the user has control over that object.
        if (exp != null &&
                // Ignore some things handled by DefaultGroovyMethods.asType(Collection, Class), e.g., `[1, 2, 3] as Set` (interface → first clause) or `[1, 2, 3] as HashSet` (collection assigned to concrete class → second clause):
                !(Collection.class.isAssignableFrom(clazz) && clazz.getPackage().getName().equals("java.util"))) {
            // Don't actually cast at all if this is already assignable.
            if (clazz.isAssignableFrom(exp.getClass())) {
                return () -> exp;
            } else if (clazz.isInterface()) {
                for (Method m : clazz.getMethods()) {
                    Object[] args = new Object[m.getParameterTypes().length];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = getDefaultValue(m.getParameterTypes()[i]);
                    }
                    // We intercept all methods defined on the interface to ensure they are permitted, and deliberately ignore the return value:
                    new VarArgInvokerChain(exp) {
                        public Object call(Object receiver, String method, Object... args) throws Throwable {
                            if (chain.hasNext()) {
                                if (receiver instanceof Class) {
                                    return chain.next().onStaticCall(this, (Class) receiver, method, args);
                                } else {
                                    return chain.next().onMethodCall(this, receiver, method, args);
                                }
                            } else {
                                return null;
                            }
                        }
                    }.call(exp, m.getName(), args);
                }
            } else if (Modifier.isAbstract(clazz.getModifiers()) && !Modifier.isFinal(clazz.getModifiers()) && (exp instanceof Closure || exp instanceof Map)) {
                // Groovy will create a proxy object whose methods will delegate to the closure or map values.
                // The bodies of any closures cast using this mechanism will be be sandbox transformed, but we check
                // whether the abstract class is allowed to be instantiated in the sandbox as a precaution.
                // Technically, if coerce is false, then this should only happen if the abstract class has a single
                // abstract method, but it seems simplest to handle the cases symmetrically and risk a false positive
                // RejectedAccessException in some cases that would throw a GroovyCastException in regular Groovy.
                for (Constructor c : clazz.getConstructors()) { // ProxyGeneratorAdapter seems to generate a constructor for each constructor in the abstract class, and I am not sure which one will be used, so we intercept them all.
                    Object[] args = new Object[c.getParameterTypes().length];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = getDefaultValue(c.getParameterTypes()[i]);
                    }
                    new VarArgInvokerChain(exp) {
                        public Object call(Object receiver, String method, Object... args) throws Throwable {
                            if (chain.hasNext()) {
                                return chain.next().onNewInstance(this, clazz, args);
                            } else {
                                return null;
                            }
                        }
                    }.call(clazz, null, args);
                }
            } else if ((clazz == boolean.class || clazz == Boolean.class) && exp.getClass() != Boolean.class) {
                // Boolean casts must never be handled as constructor invocation.
                new ZeroArgInvokerChain(exp) {
                    public Object call(Object receiver, String method) throws Throwable {
                        if (chain.hasNext()) {
                            return chain.next().onMethodCall(this, receiver, method);
                        } else {
                            return null;
                        }
                    }
                }.call(exp, "asBoolean");
            } else if (unbox(clazz).isPrimitive() || clazz == String.class) {
                // Casts to non-boolean primitives (and their boxed equivalents) and to String never
                // perform any reflective operations, so we do not care about them, and they should never be handled as
                // constructor invocation.
            } else if (!clazz.isArray() && clazz != Object.class && !Modifier.isAbstract(clazz.getModifiers()) && (exp instanceof Collection || exp.getClass().isArray() || exp instanceof Map)) {
                Object[] args = null;
                if (exp instanceof Collection) {
                    if (isCollectionSafeToCast((Collection) exp)) {
                        args = ((Collection) exp).toArray();
                    } else {
                        throw new UnsupportedOperationException(
                                "Casting non-standard Collections to a type via constructor is not supported. " +
                                "Consider converting " + exp.getClass() + " to a Collection defined in the java.util package and then casting to " + clazz + ".");
                    }
                } else if (exp instanceof Map) {
                    args = new Object[] {exp};
                } else { // arrays
                    // TODO tricky to determine which constructor will actually be called; array might be expanded, or might not
                    throw new UnsupportedOperationException("casting arrays to types via constructor is not yet supported");
                }
                if (args != null) {
                    // We intercept the constructor that will be used for the cast, and again, deliberately ignore the return value:
                    new VarArgInvokerChain(clazz) {
                        public Object call(Object receiver, String method, Object... args) throws Throwable {
                            if (chain.hasNext()) {
                                return chain.next().onNewInstance(this, (Class) receiver, args);
                            } else {
                                return null;
                            }
                        }
                    }.call(clazz, null, args);
                } else {
                    throw new IllegalStateException(exp.getClass() + ".toArray() must not return null");
                }
            } else if (clazz.isArray() && !clazz.getComponentType().isPrimitive() && (exp instanceof Collection || exp instanceof Object[])) {
                Object[] array;
                if (exp instanceof Collection) {
                    if (isCollectionSafeToCast((Collection) exp)) {
                        array = ((Collection) exp).toArray();
                    } else {
                        throw new UnsupportedOperationException(
                                "Casting non-standard implementations of Collection to an array is not supported. " +
                                "Consider converting " + exp.getClass() + " to a Collection defined in the java.util package and then casting to " + clazz + ".");
                    }
                } else {
                    array = (Object[])exp;
                }
                // We intercept the per-element casts.
                for (Object element : array) {
                    preCheckedCast(clazz.getComponentType(), element, coerce, strict, ignoreAutoboxing);
                }
            } else if (clazz == File.class && exp instanceof CharSequence) {
                Object[] args = new Object[]{exp.toString()};
                // We intercept the constructor that will be used for the cast, and again, deliberately ignore the return value:
                new VarArgInvokerChain(clazz) {
                    public Object call(Object receiver, String method, Object... args) throws Throwable {
                        if (chain.hasNext()) {
                            return chain.next().onNewInstance(this, (Class) receiver, args);
                        } else {
                            return null;
                        }
                    }
                }.call(clazz, null, args);
            } else if (exp instanceof File && (clazz.isArray() || Collection.class.isAssignableFrom(clazz))) {
                // see https://github.com/apache/groovy/blob/edcd6c4435138733668cd75ac0d3342efb39dc05/src/main/org/codehaus/groovy/runtime/typehandling/DefaultTypeTransformation.java#L472-L479
                // We intercept the method that will be used for the cast, and again, deliberately ignore the return value:
                new VarArgInvokerChain(clazz) {
                    public Object call(Object receiver, String method, Object... args) throws Throwable {
                        if (chain.hasNext() && receiver instanceof Class) {
                            return chain.next().onStaticCall(this, (Class) receiver, method, args);
                        } else {
                            return null;
                        }
                    }
                }.call(ResourceGroovyMethods.class, "readLines", exp);
            } else if (exp instanceof Class && ((Class) exp).isEnum() && (clazz.isArray() || Collection.class.isAssignableFrom(clazz))) {
                // see https://github.com/apache/groovy/blob/edcd6c4435138733668cd75ac0d3342efb39dc05/src/main/org/codehaus/groovy/runtime/typehandling/DefaultTypeTransformation.java#L480-L483
                for (Field f : ((Class) exp).getFields()) {
                    if (f.isEnumConstant()) {
                        // We intercept all Enum constants to ensure they are permitted, and deliberately ignore the return value:
                        new ZeroArgInvokerChain(exp) {
                            public Object call(Object receiver, String field) throws Throwable {
                                if (chain.hasNext() && receiver instanceof Class) {
                                    return chain.next().onGetProperty(this, receiver, field);
                                } else {
                                    return null;
                                }
                            }
                        }.call(exp, f.getName());
                    }
                }
            }
        }
        // TODO what does ignoreAutoboxing do?
        return () -> strict ? clazz.cast(exp) : coerce ? ScriptBytecodeAdapter.asType(exp, clazz) : ScriptBytecodeAdapter.castToType(exp, clazz);
    }
    // https://stackoverflow.com/a/38243203/12916
    @SuppressWarnings("unchecked")
    private static <T> T getDefaultValue(Class<T> clazz) {
        return (T) Array.get(Array.newInstance(clazz, 1), 0);
    }

    /**
     * Issue #2 revealed that Groovy can call methods with null in the var-arg array,
     * when it should be passing an Object array of length 1 with null value.
     */
    private static Object[] fixNull(Object[] args) {
        return args==null ? new Object[1] : args;
    }

    /**
     * When casting collections to types via constructor, we cannot allow user-defined implementations of {@link Collection}.
     * This is because a user-defined implementation of {@link Collection} can do tricky things to return a different
     * set of elements for {@link Collection#toArray} inside of {@link #preCheckedCast} than whatever
     * {@link ScriptBytecodeAdapter#asType} ends up using as the elements, so we are not able to guarantee that the
     * Constructor we pre-checked is the one that will end up being invoked.
     */
    private static boolean isCollectionSafeToCast(Collection c) {
        Package p = c.getClass().getPackage();
        String packageName = null;
        if (p != null) {
            packageName = p.getName();
        }
        // TODO: Are there any other packages with collections that we should allow?
        return "java.util".equals(packageName);
    }

    /**
     * Look in {@link #GROOVY_RUNTIME_REPLACEMENTS} to see if {@link Checker} defines a checked equivalent of the given
     * method, and if so, return a {@link Thunk} that will invoke the checked method rather than the original.
     *
     * <p>Groovy uses runtime APIs (e.g. {@link ScriptByteCodeAdapter}) to support various standard language features
     * such as unary operators. Some of these APIs invoke methods reflectively based on the runtime types of arguments,
     * which the sandbox does not see and so it cannot intercept those calls. We define sandbox-aware replacements for
     * these methods so that these reflective calls can be intercepted.
     * <p>When using groovy-sandbox through script-security without groovy-cps, these replacements only take effect if
     * a script directly calls one of the original methods. When using groovy-cps, these replacements also take effect
     * when their corresponding AST nodes (e.g. unary operators) are used.
     */
    private static Thunk findCheckedReplacement(Class<?> clazz, String method, Object[] args) {
        Method maybeReplacement = GROOVY_RUNTIME_REPLACEMENTS.get(new SimpleImmutableEntry(clazz, method));
        if (maybeReplacement == null) {
            return null;
        }
        ParameterTypes parameterTypes = new ParameterTypes(maybeReplacement.getParameterTypes());
        if (!parameterTypes.isValidExactMethod(args)) {
            return null;
        }
        return () -> {
            try {
                return maybeReplacement.invoke(null, args);
            } catch (InvocationTargetException e) {
                throw e.getCause(); // e.g. CpsCallableInvocation
            }
        };
    }

    /**
     * A map from Groovy methods to checked replacements that will be used instead when the sandbox is active.
     */
    private static final HashMap<Map.Entry<Class<?>, String>, Method> GROOVY_RUNTIME_REPLACEMENTS = new HashMap<>();
    static {
        addReplacement(InvokerHelper.class, "bitwiseNegate", "checkedBitwiseNegate", Object.class);
        addReplacement(InvokerHelper.class, "unaryMinus", "checkedUnaryMinus", Object.class);
        addReplacement(InvokerHelper.class, "unaryPlus", "checkedUnaryPlus", Object.class);
        addReplacement(ScriptBytecodeAdapter.class, "bitwiseNegate", "checkedBitwiseNegate", Object.class);
        addReplacement(ScriptBytecodeAdapter.class, "unaryMinus", "checkedUnaryMinus", Object.class);
        addReplacement(ScriptBytecodeAdapter.class, "unaryPlus", "checkedUnaryPlus", Object.class);
        addReplacement(ScriptBytecodeAdapter.class, "createRange", "checkedCreateRange", Object.class, Object.class, boolean.class);
    }

    private static void addReplacement(Class<?> clazz, String name, String checkedName, Class<?>... parameterTypes) {
        try {
            GROOVY_RUNTIME_REPLACEMENTS.put(new SimpleImmutableEntry(clazz, name), Checker.class.getDeclaredMethod(checkedName, parameterTypes));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e); // Developer error.
        }
    }

    private static Class<?> unbox(Class<?> clazz) {
        return BOX_TO_PRIMITIVE.getOrDefault(clazz, clazz);
    }

    private static final Map<Class<?>, Class<?>> BOX_TO_PRIMITIVE = new HashMap<>();
    static {
        BOX_TO_PRIMITIVE.put(Boolean.class, boolean.class);
        BOX_TO_PRIMITIVE.put(Byte.class, byte.class);
        BOX_TO_PRIMITIVE.put(Character.class, char.class);
        BOX_TO_PRIMITIVE.put(Double.class, double.class);
        BOX_TO_PRIMITIVE.put(Float.class, float.class);
        BOX_TO_PRIMITIVE.put(Integer.class, int.class);
        BOX_TO_PRIMITIVE.put(Long.class, long.class);
        BOX_TO_PRIMITIVE.put(Short.class, short.class);
    }
}
