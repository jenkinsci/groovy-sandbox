groovy-sandbox
==============

Compile-time transformer to run Groovy code in a restrictive sandbox 


#### Maven dependency

    <dependency>
        <groupId>org.kohsuke</groupId>
        <artifactId>groovy-sandbox</artifactId>
        <version>1.5</version>
    </dependency>

#### Usage

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

