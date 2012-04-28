package test

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class Checker {
    public static Object checkedCall(Object receiver, boolean safe, boolean spread, Object method, Object... args) {
        if (safe && receiver==null)     return null;
        if (spread) {
            // TODO: does the safe flag transfer to the individual call?
            return receiver.collect { checkedCall(it,safe,false,method,args) }
        } else {
            System.out.println("Calling ${method} on ${receiver}");
            def m = receiver.&"${method}"
            return m(args);
        }
    }
}
