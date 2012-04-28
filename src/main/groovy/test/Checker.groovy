package test

import org.codehaus.groovy.runtime.ScriptBytecodeAdapter
import org.codehaus.groovy.runtime.callsite.CallSite
import org.codehaus.groovy.runtime.callsite.CallSiteArray
import org.kohsuke.groovy.sandbox.impl.VarArgInvokerChain
import org.kohsuke.groovy.sandbox.impl.ZeroArgInvokerChain
import org.kohsuke.groovy.sandbox.impl.SingleArgInvokerChain
import org.kohsuke.groovy.sandbox.impl.TwoArgInvokerChain

/**
 * Intercepted Groovy script calls into this class.
 *
 * @author Kohsuke Kawaguchi
 */
class Checker {
    /*TODO: specify the proper owner value*/
    private static CallSite fakeCallSite(String method) {
        def names = new String[1]
        names[0] = method
        CallSiteArray csa = new CallSiteArray(Checker.class, names)
        return csa.array[0]
    }


    // TODO: we need an owner class
    public static Object checkedCall(Object receiver, boolean safe, boolean spread, Object method, Object... args) {
        if (safe && receiver==null)     return null;
        if (spread) {
            return receiver.collect { checkedCall(it,true,false,method,args) }
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
                Object call(Object receiver, String method, Object... args) {
                    if (chain.hasNext())
                        return chain.next().onMethodCall(this,receiver,method,args);
                    else
                        return fakeCallSite(method).call(receiver,args);
                }
            }.call(receiver,method.toString(),args);
        }
    }

    public static Object checkedStaticCall(Class receiver, String method, Object... args) {
        return new VarArgInvokerChain() {
            Object call(Object receiver, String method, Object... args) {
                if (chain.hasNext())
                    return chain.next().onStaticCall(this,(Class)receiver,method,args);
                else
                    return fakeCallSite(method).callStatic((Class)receiver,args);
            }
        }.call(receiver,method,args);
    }

    public static Object checkedConstructor(Class type, Object... args) {
        return new VarArgInvokerChain() {
            Object call(Object receiver, String method, Object... args) {
                if (chain.hasNext())
                    return chain.next().onNewInstance(this,(Class)receiver,args);
                else
                    // I believe the name is unused
                    return fakeCallSite("<init>").callConstructor((Class)receiver,args);
            }
        }.call(type,null,args);
    }

    public static Object checkedGetProperty(Object receiver, boolean safe, boolean spread, Object property) {
        if (safe && receiver==null)     return null;
        if (spread) {
            return receiver.collect { checkedGetProperty(it,true,false,property) }
        } else {
// 1st try: do the same call site stuff
//            return fakeCallSite(property.toString()).callGetProperty(receiver);

            return new ZeroArgInvokerChain() {
                Object call(Object receiver, String property) {
                    if (chain.hasNext())
                        return chain.next().onGetProperty(this,receiver,property);
                    else
                        return ScriptBytecodeAdapter.getProperty(null,receiver,property);
                }
            }.call(receiver,property.toString())
        }
    }

    public static Object checkedSetProperty(Object receiver, Object property, boolean safe, boolean spread, Object value) {
        if (safe && receiver==null)     return;
        if (spread) {
            receiver.each { checkedSetProperty(it,property,true,false,value) }
        } else {
            return new SingleArgInvokerChain() {
                Object call(Object receiver, String property, Object value) {
                    if (chain.hasNext())
                        return chain.next().onSetProperty(this,receiver,property,value);
                    else
                        // according to AsmClassGenerator this is how the compiler maps it to
                        return ScriptBytecodeAdapter.setProperty(value,null,receiver,property);
                }
            }.call(receiver,property.toString(),value)
        }
    }

    public static Object checkedGetAttribute(Object receiver, boolean safe, boolean spread, Object property) {
        if (safe && receiver==null)     return null;
        if (spread) {
            return receiver.collect { checkedGetProperty(it,true,false,property) }
        } else {
            return new ZeroArgInvokerChain() {
                Object call(Object receiver, String property) {
                    if (chain.hasNext())
                        return chain.next().onGetProperty(this,receiver,property);
                    else
                        // according to AsmClassGenerator this is how the compiler maps it to
                        return ScriptBytecodeAdapter.getField(null,receiver,property);
                }
            }.call(receiver,property.toString())
        }
    }

    public static Object checkedSetAttribute(Object receiver, Object property, boolean safe, boolean spread, Object value) {
        if (safe && receiver==null)     return;
        if (spread) {
            receiver.each { checkedSetAttribute(it,property,true,false,value) }
        } else {
            return new SingleArgInvokerChain() {
                Object call(Object receiver, String property, Object value) {
                    if (chain.hasNext())
                        return chain.next().onSetAttribute(this,receiver,property,value);
                    else
                        return ScriptBytecodeAdapter.setField(value,null,receiver,property);
                }
            }.call(receiver,property.toString(),value)
        }
        return value;
    }

    public static Object checkedGetArray(Object receiver, Object index) {
        return new SingleArgInvokerChain() {
            Object call(Object receiver, String _, Object value) {
                if (chain.hasNext())
                    return chain.next().onGetArray(this,receiver,index);
                else
                    // BinaryExpressionHelper.eval maps this to "getAt" call
                    return fakeCallSite("getAt").call(receiver,index)
            }
        }.call(receiver,null,index);
    }

    public static Object checkedSetArray(Object receiver, Object index, Object value) {
        return new TwoArgInvokerChain() {
            Object call(Object receiver, String _, Object index, Object value) {
                if (chain.hasNext())
                    return chain.next().onSetArray(this,receiver,index,value);
                else {
                    // BinaryExpressionHelper.assignToArray maps this to "putAt" call
                    fakeCallSite("putAt").call(receiver,index,value);
                    return value;
                }
            }
        }.call(receiver,null,index,value);
    }
}
