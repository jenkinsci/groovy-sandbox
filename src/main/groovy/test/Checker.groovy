package test

import org.codehaus.groovy.runtime.callsite.CallSiteArray
import org.codehaus.groovy.runtime.callsite.CallSite
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class Checker {
    // TODO: we need an owner class
    public static Object checkedCall(Object receiver, boolean safe, boolean spread, Object method, Object... args) {
        if (safe && receiver==null)     return null;
        if (spread) {
            return receiver.collect { checkedCall(it,true,false,method,args) }
        } else {
            System.out.println("Calling ${method} on ${receiver}");
            
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
            return fakeCallSite(method.toString()).call(receiver,args)
        }
    }

    public static Object checkedStaticCall(Class receiver, String method, Object... args) {
        System.out.println("Static calling ${method} on ${receiver}");

        return fakeCallSite(method).callStatic(receiver,args)
    }

    /*TODO: specify the proper owner value*/
    private static CallSite fakeCallSite(String method) {
        def names = new String[1]
        names[0] = method
        CallSiteArray csa = new CallSiteArray(Checker.class, names)
        return csa.array[0]
    }

    public static Object checkedGetProperty(Object receiver, boolean safe, boolean spread, Object property) {
        if (safe && receiver==null)     return null;
        if (spread) {
            return receiver.collect { checkedGetProperty(it,true,false,property) }
        } else {
            System.out.println("Get property ${property} on ${receiver}")
// 1st try: do the same call site stuff
//            return fakeCallSite(property.toString()).callGetProperty(receiver);

            return ScriptBytecodeAdapter.getProperty(null,receiver,property.toString());
        }
    }

    public static Object checkedGetAttribute(Object receiver, boolean safe, boolean spread, Object property) {
        if (safe && receiver==null)     return null;
        if (spread) {
            return receiver.collect { checkedGetProperty(it,true,false,property) }
        } else {
            System.out.println("Get attribute ${property} on ${receiver}")
            // according to AsmClassGenerator this is how the compiler maps it to
            return ScriptBytecodeAdapter.getField(null,receiver,property.toString());
        }
    }

    public static Object checkedConstructor(Class type, Object... args) {
        System.out.println("Creating a new instance of ${type}")
        // I believe the name is unused
        return fakeCallSite("<init>").callConstructor(type,args);
    }
}
