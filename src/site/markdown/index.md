This project defines a [Groovy](http://groovy.codehaus.org/) [`CompilationCustomizer`](http://groovy.codehaus.org/api/org/codehaus/groovy/control/customizers/CompilationCustomizer.html),
which allows a program to execute Groovy script in a restricted sandbox environment.
It is useful for applications that want to provide some degree of scriptability to users, without allowing them
to execute `System.exit(0)` or any other undesirable operations.

This compile-time transformation modifies untrusted Groovy script in such a way that every operation that
can cause interactions with the external world gets intercepted. This allows your code to examine and deny
executions. This inclueds every method call, object allocations, property/attribute access, array access, and so on.

See [my blog post](http://kohsuke.org/2012/04/27/groovy-secureastcustomizer-is-harmful/) that got this project started.

# Example

You have to add `SandboxTransformer` to your `CompilerConfiguration` first:

    def cc = new CompilerConfiguration()
    cc.addCompilationCustomizers(new SandboxTransformer())
    def binding = new Binding();
    binding.robot = robot = new Robot();
    sh = new GroovyShell(binding,cc)

Any script compiled via the resulting shell object will now be sandboxed.

When a sandboxed script executes, all the operations mentioned above will get intercepted.
To examine those calls and reject some of them, create your own implementation of `GroovyInterceptor`
and registers it to the thread before you start executing the script:

    def sandbox = new RobotSandbox()
    sandbox.register()
    try {
        sh.evaluate("robot.leftArm.move()")
    } finally {
        sandbox.unregister()
    }

See the [robot example](https://github.com/jenkinsci/groovy-sandbox/tree/master/src/test/groovy/org/kohsuke/groovy/sandbox/robot)
for a complete example.

# Considerations
## Interceptors are thread specific
You can register multiple interceptors, but they are all local to a thread.
This allows multi-threaded applications to isolate execution properly, but it also means you cannot
let the sandbox script create threads, or else it'll escape the sandbox.

The easiest way to do this
is to ensure you prohibit the use of `Thread` as the receiver, and prevent the sandboxed script from
accessing `Executor`-like services that let closures executed on different threads.

## No blacklisting
Unlikes a sandbox provided by Java `SecurityManager`, this sandboxing is only a skin deep.
In other words, even if you prohibit a script from executing an operation X, if an attacker finds another method Y
that calls into X, he can execute X.

This in practice means you have to whitelist what's OK, as opposed to blacklist things that are problematic,
because you'll never know all the static methods that are available to the script in the JVM!

## Reflection
Access to reflection almost always need to be blocked, or else a script can escape a sandbox by invoking
arbitrary methods on arbitrary objects, or remove the interceptor that you have installed.

The easiest way to do this is to prohibit the use of `Class` as a receiver. If that is too strong a restriction,
you can prohibit the use of `java.lang.reflect.*` classes as a receiver, and reject `Class.newInstance(...)` calls.
