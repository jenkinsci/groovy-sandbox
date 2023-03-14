package org.kohsuke.groovy.sandbox;

import groovy.lang.Script;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.kohsuke.groovy.sandbox.impl.Checker;
import org.kohsuke.groovy.sandbox.impl.Ops;
import org.kohsuke.groovy.sandbox.impl.SandboxedMethodClosure;

import static org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.ReturnAdder;
import org.codehaus.groovy.classgen.VariableScopeVisitor;
import org.codehaus.groovy.classgen.Verifier;
import static org.codehaus.groovy.syntax.Types.*;

/**
 * Transforms Groovy code at compile-time to intercept when the script interacts with the outside world.
 *
 * <p>
 * Sometimes you'd like to run Groovy scripts in a sandbox environment, where you only want it to
 * access limited subset of the rest of JVM. This transformation makes that possible by letting you inspect
 * every step of the script execution when it makes method calls and property/field/array access.
 *
 * <p>
 * Once the script is transformed, every intercepted operation results in a call to {@link Checker},
 * which further forwards the call to {@link GroovyInterceptor} for inspection.
 *
 *
 * <p>
 * To use it, add it to the {@link org.codehaus.groovy.control.CompilerConfiguration}, like this:
 *
 * <pre>
 * def cc = new CompilerConfiguration()
 * cc.addCompilationCustomizers(new SandboxTransformer())
 * sh = new GroovyShell(cc)
 * </pre>
 *
 * <p>
 * By default, this code intercepts everything that can be intercepted, which are:
 * <ul>
 *     <li>Method calls (instance method and static method)
 *     <li>Object allocation (that is, a constructor call except of the form "this(...)" and "super(...)")
 *     <li>Property access (e.g., z=foo.bar, z=foo."bar") and assignment (e.g., foo.bar=z, foo."bar"=z)
 *     <li>Attribute access (e.g., z=foo.@bar) and assignments (e.g., foo.@bar=z)
 *     <li>Array access and assignment (z=x[y] and x[y]=z)
 * </ul>
 * <p>
 * You can disable interceptions selectively by setting respective {@code interceptXXX} flags to {@code false}.
 *
 * <p>
 * There'll be a substantial hit to the performance of the execution.
 *
 * @author Kohsuke Kawaguchi
 */
public class SandboxTransformer extends CompilationCustomizer {
    /**
     * Intercept method calls
     */
    boolean interceptMethodCall=true;
    /**
     * Intercept object instantiation by intercepting its constructor call.
     *
     * Note that Java byte code doesn't allow the interception of super(...) and this(...)
     * so the object instantiation by defining and instantiating a subtype cannot be intercepted.
     */
    boolean interceptConstructor=true;
    /**
     * Intercept property access for both read "(...).y" and write "(...).y=..."
     */
    boolean interceptProperty=true;
    /**
     * Intercept array access for both read "y=a[x]" and write "a[x]=y"
     */
    boolean interceptArray=true;
    /**
     * Intercept attribute access for both read "z=x.@y" and write "x.@y=z"
     */
    boolean interceptAttribute=true;

    public SandboxTransformer() {
        super(CompilePhase.CANONICALIZATION);
    }

    @Override
    public void call(final SourceUnit source, GeneratorContext context, ClassNode classNode) {
        if (classNode == null) { // TODO is this even possible? CpsTransformer implies it is not.
            return;
        }

        // Removes all initial expressions for constructors and methods and generates overloads for all variants.
        new InitialExpressionExpander().expandInitialExpressions(source, classNode);

        ClassCodeExpressionTransformer visitor = createVisitor(source, classNode);

        processConstructors(visitor, classNode);
        for (MethodNode m : classNode.getMethods()) {
            forbidIfFinalizer(m);
            visitor.visitMethod(m);
        }
        for (Statement s : classNode.getObjectInitializerStatements()) {
            s.visit(visitor);
        }
        for (FieldNode f : classNode.getFields()) {
            visitor.visitField(f);
        }
    }

    /**
     * {@link Object#finalize} is called by the JVM outside of the sandbox, so overriding it in a
     * sandboxed script is not allowed.
     */
    public void forbidIfFinalizer(MethodNode m) {
        if (m.getName().equals("finalize") && m.isVoidMethod() && !m.isPrivate() && !m.isStatic()) {
            boolean safe = false;
            /*
            Groovy allows method definitions to specify default arguments for parameters. Parameters with default
            arguments may be omitted when calling the method. Groovy implements this by generating additional
            overloaded methods in bytecode for each variation of the method being omitted.
            For example, given the following method:

                public void finalize(int x = 0) { }

            Groovy will generate 2 methods in bytecode:

                public void finalize(int x) { }
                public void finalize() { finalize(0) }

            Our AST transformer will not see the generated no-arg method which overrides Object#finalize, so we need
            to account for it by ensuring that at least one parameter does not have a default argument (AKA initial
            expression) for the method to be acceptable, because parameters without default arguments will exist in all
            generated methods.
            */
            for (Parameter p : m.getParameters()) {
                if (!p.hasInitialExpression()) {
                    safe = true;
                    break;
                }
            }
            if (!safe) {
                throw new SecurityException("Sandboxed code may not override Object.finalize()");
            }
        }
    }

    /** Do not care about {@code super} or {@code this} calls for classes extending these types. */
    private static final Set<String> TRIVIAL_CONSTRUCTORS = new HashSet<>(Arrays.asList(
        Object.class.getName(),
        Script.class.getName(),
        "com.cloudbees.groovy.cps.SerializableScript",
        "org.jenkinsci.plugins.workflow.cps.CpsScript"));
    /**
     * Apply SECURITY-582 (and part of SECURITY-1754) fix to constructors.
     *
     * For example, given code like this:
     * <pre>{@code
     * class B { }
     * class A extends B {
     *     A(T1 p1, ..., TM pM) {
     *         super(U1 a1, ..., UN aN) // or `this(...)`
     *         ...
     *     }
     * }
     * }</pre>
     *
     * {@link #processConstructors} will transform it into something like this:
     *
     * <pre>{@code
     * class B { }
     * class A extends B {
     *     A(T1 p1, ..., TM pM) {
     *         this(Checker.checkedSuperConstructor( // or `Checker.checkedThisConstructor`
     *                 B.class,
     *                 new Object[]{a1, ..., aN},
     *                 new Object[]{p1, ..., pM},
     *                 new Class<?>[]{SuperConstructorWrapper.class, T1.class, ..., TM.class}), // or `ThisConstructorWrapper.class`
     *             p1, ..., pM)
     *     }
     *     A(Checker.SuperConstructorWrapper $cw, T1 p1, ..., TM pM) { // Or `Checker.ThisConstructorWrapper $cw`
     *         super($cw.arg(1), ... cw.arg(N)) // or `this(...)`
     *         ...
     *     }
     * }
     * }</pre>
     */
    public void processConstructors(final ClassCodeExpressionTransformer visitor, ClassNode classNode) {
        ClassNode superClass = classNode.getSuperClass();
        List<ConstructorNode> declaredConstructors = classNode.getDeclaredConstructors();
        if (TRIVIAL_CONSTRUCTORS.contains(superClass.getName())) {
            for (ConstructorNode c : declaredConstructors) {
                visitor.visitMethod(c);
            }
        } else {
            if (declaredConstructors.isEmpty()) {
                // Default constructor should have already been added by InitialExpressionExpander
                throw new AssertionError("No constructors for " + classNode);
            } else {
                declaredConstructors = new ArrayList<>(declaredConstructors);
            }
            for (ConstructorNode c : declaredConstructors) {
                for (Parameter p : c.getParameters()) {
                    if (p.hasInitialExpression()) {
                        // All initial expressions should have already been removed by InitialExpressionExpander
                        throw new AssertionError("Found unexpected initial expression: " + p.getInitialExpression());
                    }
                }
                Statement code = c.getCode();
                List<Statement> body;
                if (code instanceof BlockStatement) {
                    body = ((BlockStatement) code).getStatements();
                } else {
                    body = Collections.singletonList(code);
                }
                ClassNode constructorCallType = ClassNode.SUPER;
                TupleExpression constructorCallArgs = new TupleExpression();
                if (!body.isEmpty() && body.get(0) instanceof ExpressionStatement && ((ExpressionStatement) body.get(0)).getExpression() instanceof ConstructorCallExpression) {
                    ConstructorCallExpression cce = (ConstructorCallExpression) ((ExpressionStatement) body.get(0)).getExpression();
                    if (cce.isThisCall()) {
                        constructorCallType = ClassNode.THIS;
                        body = body.subList(1, body.size());
                        constructorCallArgs = ((TupleExpression) cce.getArguments());
                    } else if (cce.isSuperCall()) {
                        body = body.subList(1, body.size());
                        constructorCallArgs = ((TupleExpression) cce.getArguments());
                    }
                }
                final TupleExpression _constructorCallArgs = constructorCallArgs;
                final AtomicReference<Expression> constructorCallArgsTransformed = new AtomicReference<>();
                ((ScopeTrackingClassCodeExpressionTransformer) visitor).withMethod(c, new Runnable() {
                    @Override
                    public void run() {
                        constructorCallArgsTransformed.set(((VisitorImpl) visitor).transformArguments(_constructorCallArgs));
                    }
                });
                // Create parameters for new constructor.
                Parameter[] origParams = c.getParameters();
                Parameter[] params = new Parameter[origParams.length + 1];
                params[0] = new Parameter(new ClassNode(constructorCallType == ClassNode.THIS ? Checker.ThisConstructorWrapper.class : Checker.SuperConstructorWrapper.class), "$cw");
                System.arraycopy(origParams, 0, params, 1, origParams.length);
                List<Expression> paramTypes = new ArrayList<>(params.length);
                for (Parameter p : params) {
                    paramTypes.add(new ClassExpression(p.getType()));
                }
                // Create arguments for call to synthetic constructor.
                List<Expression> thisArgs = new ArrayList<>(origParams.length + 1);
                thisArgs.add(null); // Placeholder
                List<Expression> thisArgsWithoutWrapper = new ArrayList<>(origParams.length);
                for (Parameter p : origParams) {
                    thisArgs.add(new VariableExpression(p));
                    thisArgsWithoutWrapper.add(new VariableExpression(p));
                }
                if (constructorCallType == ClassNode.THIS) {
                    thisArgs.set(0, ((VisitorImpl) visitor).makeCheckedCall("checkedThisConstructor",
                            new ClassExpression(classNode),
                            constructorCallArgsTransformed.get(),
                            new ArrayExpression(new ClassNode(Object.class), thisArgsWithoutWrapper),
                            new ArrayExpression(new ClassNode(Class.class), paramTypes)));
                } else {
                    thisArgs.set(0, ((VisitorImpl) visitor).makeCheckedCall("checkedSuperConstructor",
                            new ClassExpression(classNode),
                            new ClassExpression(superClass),
                            constructorCallArgsTransformed.get(),
                            new ArrayExpression(new ClassNode(Object.class), thisArgsWithoutWrapper),
                            new ArrayExpression(new ClassNode(Class.class), paramTypes)));
                }
                c.setCode(new BlockStatement(new Statement[] {new ExpressionStatement(new ConstructorCallExpression(ClassNode.THIS, new TupleExpression(thisArgs)))}, c.getVariableScope()));
                List<Expression> cwArgs = new ArrayList<>();
                int x = 0;
                for (Expression constructorCallArg : constructorCallArgs) {
                    cwArgs.add(/*new CastExpression(superArg.getType(), */new MethodCallExpression(new VariableExpression("$cw"), "arg", new ConstantExpression(x++))/*)*/);
                }
                List<Statement> body2 = new ArrayList<>(body.size() + 1);
                body2.add(0, new ExpressionStatement(new ConstructorCallExpression(constructorCallType, new ArgumentListExpression(cwArgs))));
                body2.addAll(body);
                ((ScopeTrackingClassCodeExpressionTransformer) visitor).withMethod(c, () -> {
                    for (int i = 1; i < body2.size(); i++) { // Skip the first statement, which is the constructor call.
                        body2.get(i).visit(visitor);
                    }
                });
                final int SYNTHETIC = 0x00001000; // Not public in Modifier
                ConstructorNode c2 = new ConstructorNode(Modifier.PRIVATE | SYNTHETIC, params, c.getExceptions(), new BlockStatement(body2, c.getVariableScope()));
                // perhaps more misleading than helpful: c2.setSourcePosition(c);
                classNode.addConstructor(c2);
            }
        }
    }

    @Deprecated
    public ClassCodeExpressionTransformer createVisitor(SourceUnit source) {
        return createVisitor(source, null);
    }
    
    public ClassCodeExpressionTransformer createVisitor(SourceUnit source, ClassNode clazz) {
        return new VisitorImpl(source, clazz);
    }

    class VisitorImpl extends ScopeTrackingClassCodeExpressionTransformer {
        private final SourceUnit sourceUnit;
        /**
         * Invocation/property access without the left-hand side expression (for example {@code foo()}
         * as opposed to {@code something.foo()} means {@code this.foo()} in Java, but this is not
         * so in Groovy.
         *
         * In Groovy, {@code foo()} inside a closure uses the closure object itself as the lhs value,
         * whereas {@code this} in closure refers to a nearest enclosing non-closure object.
         *
         * So we cannot always expand {@code foo()} to {@code this.foo()}.
         *
         * To keep track of when we can expand {@code foo()} to {@code this.foo()} and when we can't,
         * we maintain this flag as we visit the expression tree. This flag is set to true
         * while we are visiting the body of the closure (the part between { ... }), and switched
         * back to false as we visit inner classes.
         *
         * To correctly expand {@code foo()} in the closure requires an access to the closure object itself,
         * and unfortunately Groovy doesn't seem to have any reliable way to do this. The hack I came up
         * with is {@code asWritable().getOwner()}, but even that is subject to the method resolution rule.
         *
         */
        private boolean visitingClosureBody;

        /**
         * Current class we are traversing.
         */
        private ClassNode clazz;

        /**
         * Return type of the current method or closure body that we are traversing.
         */
        private ClassNode methodReturnType;

        VisitorImpl(SourceUnit sourceUnit, ClassNode clazz) {
            this.sourceUnit = sourceUnit;
            this.clazz = clazz;
        }

        @Override
        public void visitMethod(MethodNode node) {
            if (clazz == null) { // compatibility
                clazz = node.getDeclaringClass();
            }
            methodReturnType = node.getReturnType();
            try {
                // Add explicit return statements so we can insert casts as needed.
                ReturnAdder adder = new ReturnAdder();
                adder.visitMethod(node);
                super.visitMethod(node);
            } finally {
                methodReturnType = null;
            }
        }

        @Override
        public void visitReturnStatement(ReturnStatement statement) {
            if (statement.isReturningNullOrVoid()) {
                // We must not mutate ReturnStatement.RETURN_NULL_OR_VOID, and we don't care about casting null anyway.
                return;
            }
            super.visitReturnStatement(statement);
            // statement.getExpression has already been transformed by the super call, so we do not transform it twice.
            statement.setExpression(makeCheckedGroovyCast(methodReturnType, statement.getExpression()));
        }

        @Override
        public void visitField(FieldNode node) {
            super.visitField(node);
            // When using @Field with a declaration that has no default value, node.getInitialExpression is an
            // EmptyExpression rather than null, so we must ignore it to avoid breaking things.
            if (node.hasInitialExpression() && !(node.getInitialValueExpression() instanceof EmptyExpression)) {
                // node.getInitialValueExpression has already been transformed by the super call, so we do not transform it twice.
                node.setInitialValueExpression(makeCheckedGroovyCast(node.getType(), node.getInitialValueExpression()));
            }
        }

        /**
         * Transforms the arguments of a call.
         * Groovy primarily uses {@link ArgumentListExpression} for this,
         * but the signature doesn't guarantee that. So this method takes care of that.
         */
        Expression transformArguments(Expression e) {
            List<Expression> l;
            if (e instanceof TupleExpression) {
                List<Expression> expressions = ((TupleExpression) e).getExpressions();
                l = new ArrayList<>(expressions.size());
                for (Expression expression : expressions) {
                    l.add(transform(expression));
                }
            } else {
                l = Collections.singletonList(transform(e));
            }

            // checkdCall expects an array
            return withLoc(e,new MethodCallExpression(new ListExpression(l),"toArray",new ArgumentListExpression()));
        }
        
        Expression makeCheckedCall(String name, Expression... arguments) {
            return new StaticMethodCallExpression(checkerClass,name,
                new ArgumentListExpression(arguments));
        }

        /**
         * Groovy implicitly casts some expressions at runtime, so we manually insert explicit casts as needed to
         * intercept potentially dangerous calls.
         */
        Expression makeCheckedGroovyCast(ClassNode clazz, Expression value) {
            if (isKnownSafeCast(clazz, value)) {
                return value;
            }
            return makeCheckedCall("checkedCast",
                    classExp(clazz),
                    value,
                    boolExp(false),
                    boolExp(false), // Groovy evaluates implicit casts using ScriptByteCodeAdapter.castToType, so coerce must be false.
                    boolExp(false));
        }

        @Override
        public Expression transform(Expression exp) {
            Expression o = innerTransform(exp);
            if (o!=exp) {
                o.setSourcePosition(exp);
            }
            return o;
        }

        private Expression innerTransform(Expression exp) {
            if (exp instanceof ClosureExpression) {
                // ClosureExpression.transformExpression doesn't visit the code inside
                ClosureExpression ce = (ClosureExpression)exp;
                try (StackVariableSet scope = new StackVariableSet(this)) {
                    Parameter[] parameters = ce.getParameters();
                    if (parameters != null) {
                        // Explicitly defined parameters, i.e., ".findAll { i -> i == 'bar' }"
                        if (parameters.length > 0) {
                            for (Parameter p : parameters) {
                                if (p.hasInitialExpression()) {
                                    Expression init = p.getInitialExpression();
                                    p.setInitialExpression(makeCheckedGroovyCast(p.getType(), transform(init)));
                                }
                            }
                            for (Parameter p : parameters) {
                                declareVariable(p);
                            }
                        } else {
                            // Implicit parameter - i.e., ".findAll { it == 'bar' }"
                            declareVariable(new Parameter(ClassHelper.DYNAMIC_TYPE, "it"));
                        }
                    }
                    boolean old = visitingClosureBody;
                    visitingClosureBody = true;
                    ClassNode oldMethodReturnType = methodReturnType;
                    methodReturnType = ClassHelper.OBJECT_TYPE;
                    try {
                        ce.getCode().visit(this);
                    } finally {
                        visitingClosureBody = old;
                        methodReturnType = oldMethodReturnType;
                    }
                }
            }

            if (exp instanceof MethodCallExpression && interceptMethodCall) {
                // lhs.foo(arg1,arg2) => checkedCall(lhs,"foo",arg1,arg2)
                // lhs+rhs => lhs.plus(rhs)
                // Integer.plus(Integer) => DefaultGroovyMethods.plus
                // lhs || rhs => lhs.or(rhs)
                MethodCallExpression call = (MethodCallExpression) exp;

                Expression objExp;
                if (call.isImplicitThis() && visitingClosureBody && !isLocalVariableExpression(call.getObjectExpression()))
                    objExp = CLOSURE_THIS;
                else
                    objExp = transform(call.getObjectExpression());

                Expression arg1 = transform(call.getMethod());
                Expression arg2 = transformArguments(call.getArguments());

                if (call.getObjectExpression() instanceof VariableExpression && ((VariableExpression) call.getObjectExpression()).getName().equals("super")) {
                    if (clazz == null) {
                        throw new IllegalStateException("owning class not defined");
                    }
                    return makeCheckedCall("checkedSuperCall", new ClassExpression(clazz), objExp, arg1, arg2);
                } else {
                    return makeCheckedCall("checkedCall",
                            objExp,
                            boolExp(call.isSafe()),
                            boolExp(call.isSpreadSafe()),
                            arg1,
                            arg2);
                }
            }
            
            if (exp instanceof StaticMethodCallExpression && interceptMethodCall) {
                /*
                    Groovy doesn't use StaticMethodCallExpression as much as it could in compilation.
                    For example, "Math.max(1,2)" results in a regular MethodCallExpression.

                    Static import handling uses StaticMethodCallExpression, and so are some
                    ASTTransformations like ToString,EqualsAndHashCode, etc.
                 */
                StaticMethodCallExpression call = (StaticMethodCallExpression) exp;
                return makeCheckedCall("checkedStaticCall",
                            new ClassExpression(call.getOwnerType()),
                            new ConstantExpression(call.getMethod()),
                            transformArguments(call.getArguments())
                    );
            }

            if (exp instanceof MethodPointerExpression && interceptMethodCall) {
                MethodPointerExpression mpe = (MethodPointerExpression) exp;
                return new ConstructorCallExpression(
                        new ClassNode(SandboxedMethodClosure.class),
                        new ArgumentListExpression(
                                transform(mpe.getExpression()),
                                transform(mpe.getMethodName()))
                );
            }

            if (exp instanceof ConstructorCallExpression && interceptConstructor) {
                if (!((ConstructorCallExpression) exp).isSpecialCall()) {
                    // creating a new instance, like "new Foo(...)"
                    return makeCheckedCall("checkedConstructor",
                            new ClassExpression(exp.getType()),
                            transformArguments(((ConstructorCallExpression) exp).getArguments())
                    );
                } else {
                    // we can't really intercept constructor calling super(...) or this(...),
                    // since it has to be the first method call in a constructor.
                    // but see SECURITY-582 fix above
                }
            }

            if (exp instanceof AttributeExpression && interceptAttribute) {
                AttributeExpression ae = (AttributeExpression) exp;
                return makeCheckedCall("checkedGetAttribute",
                    transform(ae.getObjectExpression()),
                    boolExp(ae.isSafe()),
                    boolExp(ae.isSpreadSafe()),
                    transform(ae.getProperty())
                );
            }

            if (exp instanceof PropertyExpression && interceptProperty) {
                PropertyExpression pe = (PropertyExpression) exp;
                return makeCheckedCall("checkedGetProperty",
                    transformObjectExpression(pe),
                    boolExp(pe.isSafe()),
                    boolExp(pe.isSpreadSafe()),
                    transform(pe.getProperty())
                );
            }

            if (exp instanceof FieldExpression && interceptProperty) {
                // I am not sure whether this is reachable. See note below regarding the only known case of FieldExpression in the AST.
                FieldExpression fe = (FieldExpression) exp;
                return makeCheckedCall("checkedGetAttribute",
                    new VariableExpression("this"),
                    boolExp(false),
                    boolExp(false),
                    stringExp(fe.getFieldName())
                );
            }

            if (exp instanceof VariableExpression && interceptProperty) {
                VariableExpression vexp = (VariableExpression) exp;
                if (isLocalVariable(vexp.getName()) || vexp.getName().equals("this") || vexp.getName().equals("super")) {
                    // We don't care what sandboxed code does to itself until it starts interacting with outside world
                    return super.transform(exp);
                } else {
                    // if the variable is not in-scope local variable, it gets treated as a property access with implicit this.
                    // see AsmClassGenerator.visitVariableExpression and processClassVariable.
                    PropertyExpression pexp = new PropertyExpression(VariableExpression.THIS_EXPRESSION, vexp.getName());
                    pexp.setImplicitThis(true);
                    withLoc(exp,pexp);
                    return transform(pexp);
                }
            }

            if (exp instanceof DeclarationExpression) {
                handleDeclarations((DeclarationExpression) exp);
                // We handle DeclarationExpression here to simplify handling of BinaryExpression for non-declaration assignments.
                DeclarationExpression de = (DeclarationExpression) exp;
                Expression rhs = de.getRightExpression();
                if (rhs instanceof EmptyExpression) {
                    // Declaration without initialization.
                    return exp;
                } else if (de.isMultipleAssignmentDeclaration()) {
                    throw new UnsupportedOperationException("The sandbox does not currently support multiple assignment");
                }
                return withLoc(de, new DeclarationExpression(de.getVariableExpression(), de.getOperation(),
                        makeCheckedGroovyCast(de.getVariableExpression().getType(), transform(rhs))));
            }

            if (exp instanceof BinaryExpression) {
                BinaryExpression be = (BinaryExpression) exp;
                // this covers everything from a+b to a=b
                if (ofType(be.getOperation().getType(),ASSIGNMENT_OPERATOR)) {
                    // simple assignment like '=' as well as compound assignments like "+=","-=", etc.

                    // How we dispatch this depends on the type of left expression.
                    // 
                    // What can be LHS?
                    // according to AsmClassGenerator, PropertyExpression, AttributeExpression, FieldExpression, VariableExpression
                    // Can also be TupleExpression, but we do not currently handle that.

                    Expression lhs = be.getLeftExpression();
                    if (lhs instanceof VariableExpression) {
                        VariableExpression vexp = (VariableExpression) lhs;
                        if (isLocalVariable(vexp.getName()) || vexp.getName().equals("this") || vexp.getName().equals("super")) {
                            return withLoc(be, new BinaryExpression(lhs, be.getOperation(),
                                    makeCheckedGroovyCast(vexp.getType(), transform(be.getRightExpression()))));
                        } else {
                            // if the variable is not in-scope local variable, it gets treated as a property access with implicit this.
                            // see AsmClassGenerator.visitVariableExpression and processClassVariable.
                            PropertyExpression pexp = new PropertyExpression(VariableExpression.THIS_EXPRESSION, vexp.getName());
                            pexp.setImplicitThis(true);
                            pexp.setSourcePosition(vexp);

                            lhs = pexp;
                        }
                    } // no else here
                    if (lhs instanceof PropertyExpression) {
                        PropertyExpression pe = (PropertyExpression) lhs;
                        String name = null;
                        if (lhs instanceof AttributeExpression) {
                            if (interceptAttribute)
                                name = "checkedSetAttribute";
                        } else {
                            Expression receiver = pe.getObjectExpression();
                            if (receiver instanceof VariableExpression && ((VariableExpression) receiver).isThisExpression()) {
                                FieldNode field = clazz != null ? clazz.getField(pe.getPropertyAsString()) : null;
                                if (field != null) {
                                    // "this.x = y" must be handled specially to prevent the sandbox from using
                                    // reflection to assign values to final fields in constructors and initializers
                                    // and to prevent infinite loops in setter methods.
                                    return new BinaryExpression(new FieldExpression(field), be.getOperation(),
                                            makeCheckedGroovyCast(field.getType(), transform(be.getRightExpression())));
                                } // else this is a property which we need to check
                            }
                            if (interceptProperty)
                                name = "checkedSetProperty";
                        }
                        if (name==null) // not intercepting?
                            return super.transform(exp);

                        return makeCheckedCall(name,
                                transformObjectExpression(pe),
                                transform(pe.getProperty()),
                                boolExp(pe.isSafe()),
                                boolExp(pe.isSpreadSafe()),
                                intExp(be.getOperation().getType()),
                                transform(be.getRightExpression())
                        );
                    } else
                    if (lhs instanceof FieldExpression) {
                        // The only known occurrences of this expression in the AST are for the `this$0` field that is
                        // added to anonymous and inner classes to allow them to access their outer class and for
                        // assigning the values of static enum constant fields in synthetically generated enum constructors.
                        FieldExpression fe = (FieldExpression) lhs;
                        if (fe.getField().isFinal()) {
                            // Assignments to final fields cannot be done reflectively, so we leave FieldExpression untransformed.
                            return withLoc(be, new BinaryExpression(fe, be.getOperation(),
                                    makeCheckedGroovyCast(fe.getType(), transform(be.getRightExpression()))));
                        }
                        return withLoc(be, makeCheckedCall("checkedSetAttribute",
                                new VariableExpression("this"),
                                stringExp(fe.getFieldName()),
                                boolExp(false),
                                boolExp(false),
                                intExp(be.getOperation().getType()),
                                makeCheckedGroovyCast(fe.getType(), transform(be.getRightExpression()))));
                    } else
                    if (lhs instanceof BinaryExpression) {
                        BinaryExpression lbe = (BinaryExpression) lhs;
                        if (lbe.getOperation().getType()==Types.LEFT_SQUARE_BRACKET && interceptArray) {// expression of the form "x[y] = z"
                            return makeCheckedCall("checkedSetArray",
                                    transform(lbe.getLeftExpression()),
                                    transform(lbe.getRightExpression()),
                                    intExp(be.getOperation().getType()),
                                    transform(be.getRightExpression())
                            );
                        }
                    } else if (lhs instanceof TupleExpression) {
                        throw new UnsupportedOperationException("The sandbox does not support multiple assignment");
                    }
                    throw new AssertionError("Unexpected LHS of an assignment: " + lhs.getClass());
                }
                if (be.getOperation().getType()==Types.LEFT_SQUARE_BRACKET) {// array reference
                    if (interceptArray)
                        return makeCheckedCall("checkedGetArray",
                                transform(be.getLeftExpression()),
                                transform(be.getRightExpression())
                        );
                } else
                if (be.getOperation().getType()==Types.KEYWORD_INSTANCEOF) {// instanceof operator
                    return super.transform(exp);
                } else
                if (Ops.isLogicalOperator(be.getOperation().getType())) {
                    return super.transform(exp);
                } else
                if (be.getOperation().getType()==Types.KEYWORD_IN) {// membership operator: JENKINS-28154
                    // This requires inverted operand order:
                    // "a in [...]" -> "[...].isCase(a)"
                    if (interceptMethodCall)
                        return makeCheckedCall("checkedCall",
                                transform(be.getRightExpression()),
                                boolExp(false),
                                boolExp(false),
                                stringExp("isCase"),
                                transform(be.getLeftExpression())

                        );
                } else
                if (Ops.isRegexpComparisonOperator(be.getOperation().getType())) {
                    if (interceptMethodCall)
                        return makeCheckedCall("checkedStaticCall",
                                classExp(ScriptBytecodeAdapterClass),
                                stringExp(Ops.binaryOperatorMethods(be.getOperation().getType())),
                                transform(be.getLeftExpression()),
                                transform(be.getRightExpression())
                        );
                } else
                if (Ops.isComparisionOperator(be.getOperation().getType())) {
                    if (interceptMethodCall) {
                        return makeCheckedCall("checkedComparison",
                                transform(be.getLeftExpression()),
                                intExp(be.getOperation().getType()),
                                transform(be.getRightExpression())
                        );
                    }
                } else
                if (interceptMethodCall) {
                    // normally binary operators like a+b
                    // TODO: check what other weird binary operators land here
                    return makeCheckedCall("checkedBinaryOp",
                            transform(be.getLeftExpression()),
                            intExp(be.getOperation().getType()),
                            transform(be.getRightExpression())
                    );
                }
            }

            if (exp instanceof PostfixExpression) {
                PostfixExpression pe = (PostfixExpression) exp;
                return prefixPostfixExp(exp, pe.getExpression(), pe.getOperation(), "Postfix");
            }
            if (exp instanceof PrefixExpression) {
                PrefixExpression pe = (PrefixExpression) exp;
                return prefixPostfixExp(exp, pe.getExpression(), pe.getOperation(), "Prefix");
            }

            if (exp instanceof CastExpression) {
                CastExpression ce = (CastExpression) exp;
                return makeCheckedCall("checkedCast",
                        classExp(exp.getType()),
                        transform(ce.getExpression()),
                        boolExp(ce.isIgnoringAutoboxing()),
                        boolExp(ce.isCoerce()),
                        boolExp(ce.isStrict())
                );
            }

            if (exp instanceof BitwiseNegationExpression) {
                BitwiseNegationExpression bne = (BitwiseNegationExpression) exp;
                return makeCheckedCall("checkedBitwiseNegate", transform(bne.getExpression()));
            }

            if (exp instanceof RangeExpression) {
                RangeExpression re = (RangeExpression) exp;
                return makeCheckedCall("checkedCreateRange",
                        transform(re.getFrom()),
                        transform(re.getTo()),
                        boolExp(re.isInclusive()));
            }

            if (exp instanceof UnaryMinusExpression) {
                UnaryMinusExpression ume = (UnaryMinusExpression) exp;
                return makeCheckedCall("checkedUnaryMinus", transform(ume.getExpression()));
            }

            if (exp instanceof UnaryPlusExpression) {
                UnaryPlusExpression upe = (UnaryPlusExpression) exp;
                return makeCheckedCall("checkedUnaryPlus", transform(upe.getExpression()));
            }

            return super.transform(exp);
        }

        // Handles the cases mentioned in BinaryExpressionHelper.execMethodAndStoreForSubscriptOperator: https://github.com/apache/groovy/blob/GROOVY_2_4_7/src/main/org/codehaus/groovy/classgen/asm/BinaryExpressionHelper.java#L695
        private Expression prefixPostfixExp(Expression whole, Expression atom, Token opToken, String mode) {
            String op = opToken.getText().equals("++") ? "next" : "previous";

            // a[b]++
            if (atom instanceof BinaryExpression && ((BinaryExpression) atom).getOperation().getType()==Types.LEFT_SQUARE_BRACKET && interceptArray) {
                return makeCheckedCall("checked" + mode + "Array",
                        transform(((BinaryExpression) atom).getLeftExpression()),
                        transform(((BinaryExpression) atom).getRightExpression()),
                        stringExp(op)
                );
            }

            // a++
            if (atom instanceof VariableExpression) {
                VariableExpression ve = (VariableExpression) atom;
                if (isLocalVariable(ve.getName())) {
                    if (mode.equals("Postfix")) {
                        // a trick to rewrite a++ without introducing a new local variable
                        //     a++ -> [a,a=a.next()][0]
                        return transform(withLoc(whole,new BinaryExpression(
                                new ListExpression(Arrays.asList(
                                    atom,
                                    new BinaryExpression(atom, ASSIGNMENT_OP,
                                        withLoc(atom,new MethodCallExpression(atom,op,EMPTY_ARGUMENTS)))
                                )),
                                new Token(Types.LEFT_SQUARE_BRACKET, "[", -1,-1),
                                new ConstantExpression(0)
                        )));
                    } else {
                        // ++a -> a=a.next()
                        return transform(withLoc(whole,new BinaryExpression(atom,ASSIGNMENT_OP,
                                withLoc(atom,new MethodCallExpression(atom,op,EMPTY_ARGUMENTS)))
                        ));
                    }
                } else {
                    // if the variable is not in-scope local variable, it gets treated as a property access with implicit this.
                    // see AsmClassGenerator.visitVariableExpression and processClassVariable.
                    PropertyExpression pexp = new PropertyExpression(VariableExpression.THIS_EXPRESSION, ve.getName());
                    pexp.setImplicitThis(true);
                    pexp.setSourcePosition(atom);

                    atom = pexp;
                    // fall through to the "a.b++" case below
                }
            }

            // a.@b++
            if (atom instanceof AttributeExpression && interceptProperty) {
                AttributeExpression ae = (AttributeExpression) atom;
                return makeCheckedCall("checked" + mode + "Attribute",
                        transformObjectExpression(ae),
                        transform(ae.getProperty()),
                        boolExp(ae.isSafe()),
                        boolExp(ae.isSpreadSafe()),
                        stringExp(op)
                );
            }

            // a.b++
            if (atom instanceof PropertyExpression && interceptProperty) {
                PropertyExpression pe = (PropertyExpression) atom;
                return makeCheckedCall("checked" + mode + "Property",
                        transformObjectExpression(pe),
                        transform(pe.getProperty()),
                        boolExp(pe.isSafe()),
                        boolExp(pe.isSpreadSafe()),
                        stringExp(op)
                );
            }

            // this.b++ where this.b is a FieldExpression.
            // It is unclear if this is actually reachable. I think that syntax like `this.b` will always be a
            // PropertyExpression in this context. We handle it explicitly as a precaution, since the catch-all
            // below does not store the result, which would definitely be wrong for FieldExpression.
            if (atom instanceof FieldExpression) {
                FieldExpression fe = (FieldExpression) atom;
                return makeCheckedCall("checked" + mode + "Attribute",
                        new VariableExpression("this"),
                        stringExp(fe.getFieldName()),
                        boolExp(false),
                        boolExp(false),
                        stringExp(op)
                );
            }

            // method()++, 1++, any other cases where "atom" is not valid as the LHS of an assignment expression, so no
            // store is performed, see https://github.com/apache/groovy/blob/GROOVY_2_4_7/src/main/org/codehaus/groovy/classgen/asm/BinaryExpressionHelper.java#L724.
            if (mode.equals("Postfix")) {
                // We need to intercept the call to x.next() while making sure that x is not evaluated more than once.
                //     x++ -> (temp -> { temp.next(); temp }(x))
                VariableScope closureScope = new VariableScope();
                ClosureExpression closure = withLoc(whole, new ClosureExpression(
                        new Parameter[] { new Parameter(ClassHelper.DYNAMIC_TYPE, "temp") },
                        new BlockStatement(
                                Arrays.asList(
                                        new ExpressionStatement(new MethodCallExpression(
                                                new VariableExpression("temp"), op, EMPTY_ARGUMENTS)),
                                        new ExpressionStatement(new VariableExpression("temp"))),
                                new VariableScope(closureScope))));
                closure.setVariableScope(closureScope);
                return transform(withLoc(whole,
                        new MethodCallExpression(closure, "call", new ArgumentListExpression(atom))));
            } else {
                // ++x -> x.next()
                return transform(withLoc(whole, new MethodCallExpression(atom, op, EMPTY_ARGUMENTS)));
            }
        }

        /**
         * Decorates an {@link ASTNode} by copying source location from another node.
         */
        private <T extends ASTNode> T withLoc(ASTNode src, T t) {
            t.setSourcePosition(src);
            return t;
        }

        /**
         * See {@link #visitingClosureBody} for the details of what this method is about.
         */
        private Expression transformObjectExpression(PropertyExpression exp) {
            if (exp.isImplicitThis() && visitingClosureBody && !isLocalVariableExpression(exp.getObjectExpression())) {
                return CLOSURE_THIS;
            } else {
                return transform(exp.getObjectExpression());
            }
        }

        private boolean isLocalVariableExpression(Expression exp) {
            if (exp != null && exp instanceof VariableExpression) {
                return isLocalVariable(((VariableExpression) exp).getName());
            }

            return false;
        }

        ConstantExpression boolExp(boolean v) {
            return v ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE;
        }

        ConstantExpression intExp(int v) {
            return new ConstantExpression(v,true);
        }

        ClassExpression classExp(ClassNode c) {
            return new ClassExpression(c);
        }

        ConstantExpression stringExp(String v) {
            return new ConstantExpression(v);
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }
    }

    // Subclassing is required because the methods we need have protected visibility in Verifier.
    public static class InitialExpressionExpander extends Verifier {
        public void expandInitialExpressions(SourceUnit source, ClassNode node) {
            super.setClassNode(node);
            if (node.isInterface()) {
                return;
            }
            super.addDefaultParameterMethods(node);
            super.addDefaultParameterConstructors(node);
            super.addDefaultConstructor(node);
            // addDefaultParameterMethods introduces VariablesExpressions with a null getAccessedVariable(), so we
            // rerun VariableScopeVisitor to prevent issues when this is used by groovy-cps.
            new VariableScopeVisitor(source).visitClass(node);
        }
    }

    /**
     * Return true if this cast is statically known to be safe and does not need to be checked at runtime.
     *
     * @see Checker#preCheckedCast
     */
    public static boolean isKnownSafeCast(ClassNode type, Expression exp) {
        if (exp.getType().isDerivedFrom(type) || exp.getType().implementsInterface(type)) {
            return true;
        } else if (exp instanceof ConstantExpression && ((ConstantExpression)exp).isNullExpression()) {
            return true;
        } else if (exp instanceof EmptyExpression) {
            // If we get here, something has already gone wrong, and inserting a checked cast would make things even worse.
            return true;
        }
        return false;
    }

    static final Token ASSIGNMENT_OP = new Token(Types.ASSIGN, "=", -1, -1);

    static final ClassNode checkerClass = new ClassNode(Checker.class);
    static final ClassNode ScriptBytecodeAdapterClass = new ClassNode(ScriptBytecodeAdapter.class);

    /**
     * Expression that accesses the closure object itself from within the closure.
     *
     * Currently a hacky "asWritable().getOwner()"
     */
    static final Expression CLOSURE_THIS;

    static {
        MethodCallExpression aw = new MethodCallExpression(new VariableExpression("this"),"asWritable",EMPTY_ARGUMENTS);
        aw.setImplicitThis(true);

        CLOSURE_THIS = new MethodCallExpression(aw,"getOwner",EMPTY_ARGUMENTS);
    }
}
