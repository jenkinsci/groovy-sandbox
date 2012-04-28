package test

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import javax.print.AttributeException
import org.codehaus.groovy.ast.expr.AttributeExpression

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class SecureTransformer extends CompilationCustomizer {
    SecureTransformer() {
        super(CompilePhase.CANONICALIZATION)
    }

    @Override
    void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        def ast = source.getAST();
        def visitor = new VisitorImpl(source);
        ast.statementBlock.visit(visitor);

        ast.methods?.each { it.code.visit(visitor) }
    }

    class VisitorImpl extends ClassCodeExpressionTransformer {
        private SourceUnit sourceUnit;
        static final def checkerClass = new ClassNode(Checker.class)

        VisitorImpl(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit
        }

        /**
         * Transforms the arguments of a call.
         * Groovy primarily uses {@link ArgumentListExpression} for this,
         * but the signature doesn't guarantee that. So this method takes care of that.
         */
        List<Expression> transformArguments(Expression e) {
            if (e instanceof TupleExpression)
                return e.expressions*.transformExpression(this)
            return [e.transformExpression(this)];
        }
        
        Expression makeCheckedCall(String name, Collection<Expression> arguments) {
            return new StaticMethodCallExpression(checkerClass,name,
                new ArgumentListExpression(arguments as Expression[]))
        }
    
        @Override
        Expression transform(Expression exp) {
            if (exp instanceof MethodCallExpression) {
                MethodCallExpression call = exp;
                return makeCheckedCall("checkedCall",[
                        transform(call.objectExpression),
                        boolExp(call.safe),
                        boolExp(call.spreadSafe),
                        transform(call.method)
                    ]+transformArguments(call.arguments))
            }
            
            if (exp instanceof StaticMethodCallExpression) {
                /*
                    Groovy doesn't use StaticMethodCallExpression as much as it could in compilation.
                    For example, "Math.max(1,2)" results in a regular MethodCallExpression.

                    Static import handling uses StaticMethodCallExpression, and so are some
                    ASTTransformations like ToString,EqualsAndHashCode, etc.
                 */
                StaticMethodCallExpression call = exp;
                return makeCheckedCall("checkedStaticCall", [
                            new ClassExpression(call.ownerType),
                            new ConstantExpression(call.method)
                    ]+transformArguments(call.arguments))
            }

            // we can't really intercept constructor call, since it has to be the first method
            // call in a constructor.

            // TODO: how does Groovy builds AST from "new String(...)"?

            // TODO: add AttributeExpression
            if (exp instanceof AttributeExpression) {
                return makeCheckedCall("checkedGetAttribute", [
                    transform(exp.objectExpression),
                    boolExp(exp.safe),
                    boolExp(exp.spreadSafe),
                    transform(exp.property)
                ])
            }

            if (exp instanceof PropertyExpression) {
                return makeCheckedCall("checkedGetProperty", [
                    transform(exp.objectExpression),
                    boolExp(exp.safe),
                    boolExp(exp.spreadSafe),
                    transform(exp.property)
                ])
            }

            return super.transform(exp)
        }

        ConstantExpression boolExp(boolean v) {
            return v ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE
        }



        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }
    }
}
