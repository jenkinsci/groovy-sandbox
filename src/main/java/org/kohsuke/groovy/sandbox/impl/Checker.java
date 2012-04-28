package org.kohsuke.groovy.sandbox.impl;

import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;

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
    public static Object checkedCall(Object _receiver, boolean safe, boolean spread, Object _method, Object... _args) throws Throwable {
        if (safe && _receiver==null)     return null;
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
            }.call(_receiver,_method.toString(),_args);
        }
    }

    public static Object checkedStaticCall(Class _receiver, String _method, Object... _args) throws Throwable {
        return new VarArgInvokerChain() {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onStaticCall(this,(Class)receiver,method,args);
                else
                    return fakeCallSite(method).callStatic((Class)receiver,args);
            }
        }.call(_receiver,_method,_args);
    }

    public static Object checkedConstructor(Class _type, Object... _args) throws Throwable {
        return new VarArgInvokerChain() {
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                if (chain.hasNext())
                    return chain.next().onNewInstance(this,(Class)receiver,args);
                else
                    // I believe the name is unused
                    return fakeCallSite("<init>").callConstructor((Class)receiver,args);
            }
        }.call(_type,null,_args);
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

    public static Object checkedSetProperty(Object _receiver, Object _property, boolean safe, boolean spread, Object _value) throws Throwable {
        if (safe && _receiver==null)     return _value;
        if (spread) {
            Iterator itr = InvokerHelper.asIterator(_receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it!=null)
                    checkedSetProperty(it, _property, true, false, _value);
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
                        return chain.next().onGetProperty(this,receiver,property);
                    else
                        // according to AsmClassGenerator this is how the compiler maps it to
                        return ScriptBytecodeAdapter.getField(null,receiver,property);
                }
            }.call(_receiver,_property.toString());
        }
    }

    public static Object checkedSetAttribute(Object _receiver, Object _property, boolean safe, boolean spread, Object _value) throws Throwable {
        if (safe && _receiver==null)     return _value;
        if (spread) {
            Iterator itr = InvokerHelper.asIterator(_receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it!=null)
                    checkedSetAttribute(it,_property,true,false,_value);
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

    public static Object checkedSetArray(Object _receiver, Object _index, Object _value) throws Throwable {
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
