package org.kohsuke.groovy.sandbox.impl;

import groovy.lang.Closure;
import groovy.lang.GroovyRuntimeException;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassImpl;
import groovy.lang.MetaMethod;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.codehaus.groovy.classgen.asm.BinaryExpressionHelper;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;
import org.codehaus.groovy.syntax.Types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.codehaus.groovy.runtime.InvokerHelper.getMetaClass;
import static org.codehaus.groovy.runtime.MetaClassHelper.convertToTypeArray;
import org.kohsuke.groovy.sandbox.SandboxTransformer;
import static org.kohsuke.groovy.sandbox.impl.ClosureSupport.BUILTIN_PROPERTIES;

/**
 * Intercepted Groovy script calls into this class.
 *
 * @author Kohsuke Kawaguchi
 */
public class Checker {
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
        return new VarArgInvokerChain(_receiver) {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onStaticCall(this,(Class)receiver,method,args);
                else
                    return fakeCallSite(method).callStatic((Class)receiver,args);
            }
        }.call(_receiver,_method,fixNull(_args));
    }

    public static Object checkedConstructor(Class _type, Object[] _args) throws Throwable {
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
                    MetaClass mc = InvokerHelper.getMetaClass(s.receiver.getClass());
                    return mc.invokeMethod(s.senderType.getSuperclass(), s.receiver, method, args, true, true);
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

    public static SuperConstructorWrapper checkedSuperConstructor(final Class<?> superClass, Object[] args) throws Throwable {
        new VarArgInvokerChain(superClass) {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext()) {
                    chain.next().onSuperConstructor(this, superClass, args);
                }
                return null;
            }
        }.call(superClass, null, fixNull(args));
        return new SuperConstructorWrapper(args);
    }

    public static Object checkedGetProperty(final Object _receiver, boolean safe, boolean spread, Object _property) throws Throwable {
        if (safe && _receiver==null)     return null;

        if (spread) {
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
     * A compare method that invokes a.equals(b) or a.compareTo(b)==0
     */
    public static Object checkedComparison(Object lhs, final int op, Object rhs) throws Throwable {
        if (lhs==null) {// bypass the checker if lhs is null, as it will not result in any calls that will require protection anyway
            return InvokerHelper.invokeStaticMethod(ScriptBytecodeAdapter.class,
                    Ops.binaryOperatorMethods(op), new Object[]{lhs,rhs});
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
     * @see SandboxTransformer#mightBePositionalArgumentConstructor
     */
    public static Object checkedCast(Class<?> clazz, Object exp, boolean ignoreAutoboxing, boolean coerce, boolean strict) throws Throwable {
        if (coerce && exp != null &&
                // Ignore some things handled by DefaultGroovyMethods.asType(Collection, Class), e.g., `[1, 2, 3] as Set` (interface → first clause) or `[1, 2, 3] as HashSet` (collection assigned to concrete class → second clause):
                !(Collection.class.isAssignableFrom(clazz) && clazz.getPackage().getName().equals("java.util"))) {
            if (clazz.isInterface()) {
                for (Method m : clazz.getMethods()) {
                    Object[] args = new Object[m.getParameterTypes().length];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = getDefaultValue(m.getParameterTypes()[i]);
                    }
                    // Yes we are deliberately ignoring the return value here:
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
            } else if (!clazz.isArray() && clazz != Object.class && !Modifier.isAbstract(clazz.getModifiers()) && (exp instanceof Collection || exp.getClass().isArray() || exp instanceof Map)) {
                // cf. mightBePositionalArgumentConstructor
                Object[] args = null;
                if (exp instanceof Collection) {
                    args = ((Collection) exp).toArray();
                } else if (exp instanceof Map) {
                    args = new Object[] {exp};
                } else { // arrays
                    // TODO tricky to determine which constructor will actually be called; array might be expanded, or might not
                    throw new UnsupportedOperationException("casting arrays to types via constructor is not yet supported");
                }
                if (args != null) {
                    // Again we are deliberately ignoring the return value:
                    new VarArgInvokerChain(clazz) {
                        public Object call(Object receiver, String method, Object... args) throws Throwable {
                            if (chain.hasNext()) {
                                return chain.next().onNewInstance(this, (Class) receiver, args);
                            } else {
                                return null;
                            }
                        }
                    }.call(clazz, null, args);
                }
            }
        }
        // TODO what does ignoreAutoboxing do?
        return strict ? clazz.cast(exp) : coerce ? ScriptBytecodeAdapter.asType(exp, clazz) : ScriptBytecodeAdapter.castToType(exp, clazz);
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
}
