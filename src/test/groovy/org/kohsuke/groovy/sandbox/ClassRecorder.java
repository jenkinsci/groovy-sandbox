package org.kohsuke.groovy.sandbox;

/**
 * Records the interception in a short form.
 *
 * @author Kohsuke Kawaguchi
 */
public class ClassRecorder extends GroovyInterceptor {
    private final StringBuilder buf = new StringBuilder();

    @Override
    public String toString() {
        return buf.toString();
    }

    public void reset() {
        buf.setLength(0);
    }
    
    private void format(String fmt, Object... args) {
        buf.append(String.format(fmt,args)).append('\n');
    }
    
    private String type(Object o) {
        return o==null ? "null" : type(o.getClass());
    }
    
    private String type(Class c) {
        if (c.isArray())
            return type(c.getComponentType())+"[]";
        String n = c.getName();
        return n.substring(n.lastIndexOf('.')+1);
    }
    
    private String arguments(Object... args) {
        StringBuilder b = new StringBuilder();
        for (Object o : args) {
            if (b.length()>0)   b.append(',');
            b.append(type(o));
        }
        return b.toString();
    }

    @Override
    public Object onMethodCall(Invoker invoker, Object receiver, String method, Object... args) throws Throwable {
        format("%s.%s(%s)",type(receiver),method,arguments(args));
        return super.onMethodCall(invoker, receiver, method, args);
    }

    @Override
    public Object onStaticCall(Invoker invoker, Class receiver, String method, Object... args) throws Throwable {
        format("%s:%s(%s)",type(receiver),method,arguments(args));
        return super.onStaticCall(invoker, receiver, method, args);
    }

    @Override
    public Object onNewInstance(Invoker invoker, Class receiver, Object... args) throws Throwable {
        format("new %s(%s)",type(receiver),arguments(args));
        return super.onNewInstance(invoker, receiver, args);
    }

    @Override
    public Object onGetProperty(Invoker invoker, Object receiver, String property) throws Throwable {
        format("%s.%s",type(receiver),property);
        return super.onGetProperty(invoker, receiver, property);
    }

    @Override
    public Object onSetProperty(Invoker invoker, Object receiver, String property, Object value) throws Throwable {
        format("%s.%s=%s",type(receiver),property,type(value));
        return super.onSetProperty(invoker, receiver, property, value);
    }

    @Override
    public Object onGetAttribute(Invoker invoker, Object receiver, String attribute) throws Throwable {
        format("%s.@%s",type(receiver),attribute);
        return super.onGetAttribute(invoker, receiver, attribute);
    }

    @Override
    public Object onSetAttribute(Invoker invoker, Object receiver, String attribute, Object value) throws Throwable {
        format("%s.@%s=%s",type(receiver),attribute,type(value));
        return super.onSetAttribute(invoker, receiver, attribute, value);
    }

    @Override
    public Object onGetArray(Invoker invoker, Object receiver, Object index) throws Throwable {
        format("%s[%s]",type(receiver),type(index));
        return super.onGetArray(invoker, receiver, index);
    }

    @Override
    public Object onSetArray(Invoker invoker, Object receiver, Object index, Object value) throws Throwable {
        format("%s[%s]=%s",type(receiver),type(index),type(value));
        return super.onSetArray(invoker, receiver, index, value);
    }
}
