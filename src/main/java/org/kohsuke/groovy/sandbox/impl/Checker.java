package org.kohsuke.groovy.sandbox.impl;

import groovy.lang.MetaClass;
import groovy.lang.MetaClassImpl;
import groovy.lang.MetaMethod;
import org.codehaus.groovy.classgen.asm.BinaryExpressionHelper;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;
import org.codehaus.groovy.syntax.Types;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
                MetaClass mc = InvokerHelper.getMetaClass((Class)_receiver);
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

            if (_receiver==null) {
                // See issue #6. Technically speaking Groovy handles this
                // as if NullObject.INSTANCE is invoked. OTOH, it's confusing
                // to GroovyInterceptor that the receiver can be null, so I'm
                // bypassing the checker in this case.
                return fakeCallSite(_method).call(_receiver,_args);
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
            return new VarArgInvokerChain() {
                public Object call(Object receiver, String method, Object... args) throws Throwable {
                    if (chain.hasNext())
                        return chain.next().onMethodCall(this,receiver,method,args);
                    else
                        return fakeCallSite(method).call(receiver,args);
                }
            }.call(_receiver,_method,_args);
        }
    }

    public static Object checkedStaticCall(Class _receiver, String _method, Object[] _args) throws Throwable {
        return new VarArgInvokerChain() {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onStaticCall(this,(Class)receiver,method,args);
                else
                    return fakeCallSite(method).callStatic((Class)receiver,args);
            }
        }.call(_receiver,_method,fixNull(_args));
    }

    public static Object checkedConstructor(Class _type, Object[] _args) throws Throwable {
        return new VarArgInvokerChain() {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onNewInstance(this,(Class)receiver,args);
                else
                    // I believe the name is unused
                    return fakeCallSite("<init>").callConstructor((Class)receiver,args);
            }
        }.call(_type,null,fixNull(_args));
    }

    public static Object checkedGetProperty(Object _receiver, boolean safe, boolean spread, Object _property) throws Throwable {
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
        } else {
// 1st try: do the same call site stuff
//            return fakeCallSite(property.toString()).callGetProperty(receiver);

            return new ZeroArgInvokerChain() {
                public Object call(Object receiver, String property) throws Throwable {
                    if (chain.hasNext())
                        return chain.next().onGetProperty(this,receiver,property);
                    else
                        return ScriptBytecodeAdapter.getProperty(null, receiver, property);
                }
            }.call(_receiver,_property.toString());
        }
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
        } else {
            return new SingleArgInvokerChain() {
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
            return new ZeroArgInvokerChain() {
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
            return new SingleArgInvokerChain() {
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
        return new SingleArgInvokerChain() {
            public Object call(Object receiver, String _, Object index) throws Throwable {
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
            return new TwoArgInvokerChain() {
                public Object call(Object receiver, String _, Object index, Object value) throws Throwable {
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
     * Intercepts the binary expression of the form "lhs op rhs" like "lhs+rhs", "lhs>>rhs", etc.
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

        return new SingleArgInvokerChain() {
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
     * Issue #2 revealed that Groovy can call methods with null in the var-arg array,
     * when it should be passing an Object array of length 1 with null value.
     */
    private static Object[] fixNull(Object[] args) {
        return args==null ? new Object[1] : args;
    }
}
