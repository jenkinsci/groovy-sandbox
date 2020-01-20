groovy-sandbox
==============

**WARNING** This library is only maintained in the context of Jenkins, and should only be used as a dependency of Jenkins plugins such as [Script Security Plugin](https://plugins.jenkins.io/script-security) and [Pipeline: Groovy Plugin](https://plugins.jenkins.io/workflow-cps). It should be considered deprecated and unsafe for all other purposes.

This library provides a compile-time transformer to run Groovy code in an environment in which most operations, such as method calls, are intercepted before being executed. Consumers of the library can hook into the interception to allow or deny specific operations.

This library is **not secure** when used by itself. In particular, you must at least use an additional `CompilationCustomizer` along the lines of [RejectASTTransformsCustomizer](https://github.com/jenkinsci-cert/script-security-plugin/blob/c43e099f2f86425b32b0be492020313644062763/src/main/java/org/jenkinsci/plugins/scriptsecurity/sandbox/groovy/RejectASTTransformsCustomizer.java) to reject AST transformations that can bypass the sandbox, and you need to take special care to ensure untrusted scripts are both parsed and executed inside of the sandbox.
