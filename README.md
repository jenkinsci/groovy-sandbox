groovy-sandbox
==============

Compile-time transformer to run Groovy code in a restrictive sandbox. Executes untrusted Groovy script safely.

[Documentation](http://groovy-sandbox.kohsuke.org/).

#### Maven dependency
```xml
<dependency>
    <groupId>org.kohsuke</groupId>
    <artifactId>groovy-sandbox</artifactId>
    <version>1.6</version>
</dependency>
```

#### Usage
A good example can be found [here](https://github.com/jenkinsci/groovy-sandbox/tree/master/src/test/groovy/org/kohsuke/groovy/sandbox/robot).
This is a simple test that always expects a `SecurityException`:

```groovy
class Test {
    static class DenyAll extends GroovyValueFilter {
        Object filter(Object o) { throw new SecurityException('Denied!') }
    }
    @Test(expected = SecurityException)
    void testScript() {
        final sh = new GroovyShell(new CompilerConfiguration()
                .addCompilationCustomizers(new SandboxTransformer()))
        new DenyAll().register()
        sh.evaluate('println hi')
    }
}
```


