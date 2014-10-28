package org.kohsuke.groovy.sandbox

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.GroovyClassVisitor
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.MethodPointerExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.ast.expr.FieldExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.kohsuke.groovy.sandbox.impl.Checker
import org.kohsuke.groovy.sandbox.impl.Ops
import org.kohsuke.groovy.sandbox.impl.SandboxedMethodClosure

import static org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS
import static org.codehaus.groovy.syntax.Types.*

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
class SandboxTransformer extends CompilationCustomizer {
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

    SandboxTransformer() {
        super(CompilePhase.CANONICALIZATION)
    }

    @Override
    void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        def visitor = createVisitor(source);

        classNode?.declaredConstructors?.each { visitor.visitMethod(it) }
        classNode?.methods?.each { visitor.visitMethod(it) }
        classNode?.objectInitializerStatements?.each { it.visit(visitor) }
        classNode?.fields?.each { visitor.visitField(it) }
    }

    public ClassCodeExpressionTransformer createVisitor(SourceUnit source) {
        return new VisitorImpl(source);
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

        VisitorImpl(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit
        }

        /**
         * Push/pop the flag while we evaluate the body.
         */
        private void visitClosure(boolean flag, Closure body) {
            boolean old = visitingClosureBody;
            visitingClosureBody = flag;
            try {
                body.call();
            } finally {
                visitingClosureBody = old;
            }
        }


        /**
         * Transforms the arguments of a call.
         * Groovy primarily uses {@link ArgumentListExpression} for this,
         * but the signature doesn't guarantee that. So this method takes care of that.
         */
        Expression transformArguments(Expression e) {
            List<Expression> l;
            if (e instanceof TupleExpression)
                l = e.expressions.collect { transform(it) };
            else
                l = [transform(e)];

            // checkdCall expects an array
            return withLoc(e,new MethodCallExpression(new ListExpression(l),"toArray",new ArgumentListExpression()));
        }
        
        Expression makeCheckedCall(String name, Collection<Expression> arguments) {
            return new StaticMethodCallExpression(checkerClass,name,
                new ArgumentListExpression(arguments as Expression[]))
        }
    
        @Override
        Expression transform(Expression exp) {
            Expression o = innerTransform(exp);
            if (o!=exp) {
                o.sourcePosition = exp;
            }
            return o;
        }

        private Expression innerTransform(Expression exp) {
            if (exp instanceof ClosureExpression) {
                // ClosureExpression.transformExpression doesn't visit the code inside
                ClosureExpression ce = (ClosureExpression)exp;
                withVarScope {
                    for (Parameter p : ce.parameters) {
                        declareVariable(p);
                    }
                    visitClosure(true) {
                        ce.code.visit(this)
                    }
                }
            }

            if (exp instanceof MethodCallExpression && interceptMethodCall) {
                // lhs.foo(arg1,arg2) => checkedCall(lhs,"foo",arg1,arg2)
                // lhs+rhs => lhs.plus(rhs)
                // Integer.plus(Integer) => DefaultGroovyMethods.plus
                // lhs || rhs => lhs.or(rhs)
                MethodCallExpression call = exp;

                def objExp;
                if (exp.isImplicitThis() && visitingClosureBody)
                    objExp = CLOSURE_THIS;
                else
                    objExp = transform(call.objectExpression)

                return makeCheckedCall("checkedCall",[
                        objExp,
                        boolExp(call.safe),
                        boolExp(call.spreadSafe),
                        transform(call.method),
                        transformArguments(call.arguments)
                    ])
            }
            
            if (exp instanceof StaticMethodCallExpression && interceptMethodCall) {
                /*
                    Groovy doesn't use StaticMethodCallExpression as much as it could in compilation.
                    For example, "Math.max(1,2)" results in a regular MethodCallExpression.

                    Static import handling uses StaticMethodCallExpression, and so are some
                    ASTTransformations like ToString,EqualsAndHashCode, etc.
                 */
                StaticMethodCallExpression call = exp;
                return makeCheckedCall("checkedStaticCall", [
                            new ClassExpression(call.ownerType),
                            new ConstantExpression(call.method),
                            transformArguments(call.arguments)
                    ])
            }

            if (exp instanceof MethodPointerExpression && interceptMethodCall) {
                MethodPointerExpression mpe = exp;
                return new ConstructorCallExpression(
                        new ClassNode(SandboxedMethodClosure.class),
                        new ArgumentListExpression(mpe.expression, mpe.methodName)
                )
            }

            if (exp instanceof ConstructorCallExpression && interceptConstructor) {
                if (!exp.isSpecialCall()) {
                    // creating a new instance, like "new Foo(...)"
                    return makeCheckedCall("checkedConstructor", [
                            new ClassExpression(exp.type),
                            transformArguments(exp.arguments)
                    ])
                } else {
                    // we can't really intercept constructor calling super(...) or this(...),
                    // since it has to be the first method call in a constructor.
                }
            }

            if (exp instanceof AttributeExpression && interceptAttribute) {
                return makeCheckedCall("checkedGetAttribute", [
                    transform(exp.objectExpression),
                    boolExp(exp.safe),
                    boolExp(exp.spreadSafe),
                    transform(exp.property)
                ])
            }

            if (exp instanceof PropertyExpression && interceptProperty) {
                return makeCheckedCall("checkedGetProperty", [
                    transformObjectExpression(exp),
                    boolExp(exp.safe),
                    boolExp(exp.spreadSafe),
                    transform(exp.property)
                ])
            }

            if (exp instanceof VariableExpression && interceptProperty) {
                VariableExpression vexp = exp;
                if (isLocalVariable(vexp.name) || vexp.name=="this" || vexp.name=="super") {
                    // We don't care what sandboxed code does to itself until it starts interacting with outside world
                    return super.transform(exp);
                } else {
                    // if the variable is not in-scope local variable, it gets treated as a property access with implicit this.
                    // see AsmClassGenerator.visitVariableExpression and processClassVariable.
                    PropertyExpression pexp = new PropertyExpression(VariableExpression.THIS_EXPRESSION, vexp.name);
                    pexp.implicitThis = true;
                    withLoc(exp,pexp);
                    return transform(pexp);
                }
            }

            if (exp instanceof DeclarationExpression) {
                handleDeclarations(exp);
            }

            if (exp instanceof BinaryExpression) {
                // this covers everything from a+b to a=b
                if (ofType(exp.operation.type,ASSIGNMENT_OPERATOR)) {
                    // simple assignment like '=' as well as compound assignments like "+=","-=", etc.

                    // How we dispatch this depends on the type of left expression.
                    // 
                    // What can be LHS?
                    // according to AsmClassGenerator, PropertyExpression, AttributeExpression, FieldExpression, VariableExpression

                    Expression lhs = exp.leftExpression;
                    if (lhs instanceof VariableExpression) {
                        VariableExpression vexp = lhs;
                        if (isLocalVariable(vexp.name) || vexp.name=="this" || vexp.name=="super") {
                            // We don't care what sandboxed code does to itself until it starts interacting with outside world
                            return super.transform(exp);
                        } else {
                            // if the variable is not in-scope local variable, it gets treated as a property access with implicit this.
                            // see AsmClassGenerator.visitVariableExpression and processClassVariable.
                            PropertyExpression pexp = new PropertyExpression(VariableExpression.THIS_EXPRESSION, vexp.name);
                            pexp.implicitThis = true;
                            pexp.sourcePosition = vexp;

                            lhs = pexp;
                        }
                    } // no else here
                    if (lhs instanceof PropertyExpression) {
                        def name = null;
                        if (lhs instanceof AttributeExpression) {
                            if (interceptAttribute)
                                name = "checkedSetAttribute";
                        } else {
                            if (interceptProperty)
                                name = "checkedSetProperty";
                        }
                        if (name==null) // not intercepting?
                            return super.transform(exp);

                        return makeCheckedCall(name, [
                                transformObjectExpression(lhs),
                                lhs.property,
                                boolExp(lhs.safe),
                                boolExp(lhs.spreadSafe),
                                intExp(exp.operation.type),
                                transform(exp.rightExpression),
                        ])
                    } else
                    if (lhs instanceof FieldExpression) {
                        // while javadoc of FieldExpression isn't very clear,
                        // AsmClassGenerator maps this to GETSTATIC/SETSTATIC/GETFIELD/SETFIELD access.
                        // not sure how we can intercept this, so skipping this for now
                        return super.transform(exp);
                    } else
                    if (lhs instanceof BinaryExpression) {
                        if (lhs.operation.type==Types.LEFT_SQUARE_BRACKET && interceptArray) {// expression of the form "x[y] = z"
                            return makeCheckedCall("checkedSetArray", [
                                    transform(lhs.leftExpression),
                                    transform(lhs.rightExpression),
                                    intExp(exp.operation.type),
                                    transform(exp.rightExpression)
                            ])
                        }
                    } else
                        throw new AssertionError("Unexpected LHS of an assignment: ${lhs.class}")
                }
                if (exp.operation.type==Types.LEFT_SQUARE_BRACKET) {// array reference
                    if (interceptArray)
                        return makeCheckedCall("checkedGetArray", [
                                transform(exp.leftExpression),
                                transform(exp.rightExpression)
                        ])
                } else
                if (exp.operation.type==Types.KEYWORD_INSTANCEOF) {// instanceof operator
                    return super.transform(exp);
                } else
                if (Ops.isLogicalOperator(exp.operation.type)) {
                    return super.transform(exp);
                } else
                if (Ops.isRegexpComparisonOperator(exp.operation.type)) {
                    if (interceptMethodCall)
                        return makeCheckedCall("checkedStaticCall", [
                                classExp(ScriptBytecodeAdapterClass),
                                stringExp(Ops.binaryOperatorMethods(exp.operation.type)),
                                transform(exp.leftExpression),
                                transform(exp.rightExpression)
                        ])
                } else
                if (Ops.isComparisionOperator(exp.operation.type)) {
                    if (interceptMethodCall) {
                        return makeCheckedCall("checkedComparison", [
                                transform(exp.leftExpression),
                                intExp(exp.operation.type),
                                transform(exp.rightExpression)
                        ])
                    }
                } else
                if (interceptMethodCall) {
                    // normally binary operators like a+b
                    // TODO: check what other weird binary operators land here
                    return makeCheckedCall("checkedBinaryOp",[
                            transform(exp.leftExpression),
                            intExp(exp.operation.type),
                            transform(exp.rightExpression)
                    ])
                }
            }

            if (exp instanceof PostfixExpression) {
                return prefixPostfixExp(exp, exp.expression, exp.operation, "Postfix");
            }
            if (exp instanceof PrefixExpression) {
                return prefixPostfixExp(exp, exp.expression, exp.operation, "Prefix");
            }

            return super.transform(exp)
        }

        private Expression prefixPostfixExp(Expression whole, Expression atom, Token opToken, String mode) {
            String op = opToken.text=="++" ? "next" : "previous";

            // a[b]++
            if (atom instanceof BinaryExpression && atom.operation.type==Types.LEFT_SQUARE_BRACKET && interceptArray) {
                return makeCheckedCall("checked${mode}Array", [
                        transform(atom.leftExpression),
                        transform(atom.rightExpression),
                        stringExp(op)
                ])
            }

            // a++
            if (atom instanceof VariableExpression) {
                if (isLocalVariable(atom.name)) {
                    if (mode=="Postfix") {
                        // a trick to rewrite a++ without introducing a new local variable
                        //     a++ -> [a,a=a.next()][0]
                        return transform(withLoc(whole,new BinaryExpression(
                                new ListExpression([
                                    atom,
                                    new BinaryExpression(atom, ASSIGNMENT_OP,
                                        withLoc(atom,new MethodCallExpression(atom,op,EMPTY_ARGUMENTS)))
                                ]),
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
                    PropertyExpression pexp = new PropertyExpression(VariableExpression.THIS_EXPRESSION, atom.name);
                    pexp.implicitThis = true;
                    pexp.sourcePosition = atom;

                    atom = pexp;
                    // fall through to the "a.b++" case below
                }
            }

            // a.b++
            if (atom instanceof PropertyExpression && interceptProperty) {
                return makeCheckedCall("checked${mode}Property", [
                        transformObjectExpression(atom),
                        atom.property,
                        boolExp(atom.safe),
                        boolExp(atom.spreadSafe),
                        stringExp(op)
                ]);
            }

            return whole;
        }

        /**
         * Decorates an {@link ASTNode} by copying source location from another node.
         */
        private <T extends ASTNode> T withLoc(ASTNode src, T t) {
            t.sourcePosition = src;
            return t;
        }

        /**
         * See {@link #visitingClosureBody} for the details of what this method is about.
         */
        private Expression transformObjectExpression(PropertyExpression exp) {
            def objExp;
            if (exp.isImplicitThis() && visitingClosureBody)
                objExp = CLOSURE_THIS;
            else
                objExp = transform(exp.objectExpression)
            objExp
        }

        ConstantExpression boolExp(boolean v) {
            return v ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE
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

    static final Token ASSIGNMENT_OP = new Token(Types.ASSIGN, '=', -1, -1)

    static final def checkerClass = new ClassNode(Checker.class)
    static final def ScriptBytecodeAdapterClass = new ClassNode(ScriptBytecodeAdapter.class)

    /**
     * Expression that accesses the closure object itself from within the closure.
     *
     * Currently a hacky "asWritable().getOwner()"
     */
    static final Expression CLOSURE_THIS;

    static {
        def aw = new MethodCallExpression(new VariableExpression("this"),"asWritable",EMPTY_ARGUMENTS)
        aw.implicitThis = true;

        CLOSURE_THIS = new MethodCallExpression(aw,"getOwner",EMPTY_ARGUMENTS);
    }
}
