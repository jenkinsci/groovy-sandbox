package org.kohsuke.groovy.sandbox

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement

/**
 * Keeps track of in-scope variables.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class ScopeTrackingClassCodeExpressionTransformer extends ClassCodeExpressionTransformer {
    /**
     * As we visit expressions, track variable scopes.
     * This is used to distinguish local variables from property access. See issue #11.
     */
    private StackVariableSet varScope;

    public boolean isLocalVariable(String name) {
        return varScope.has(name);
    }

    @Override
    void visitMethod(MethodNode node) {
        varScope = null;
        withVarScope {
            for (Parameter p : node.parameters) {
                declareVariable(p)
            }
            super.visitMethod(node)
        }
    }

    /**
     * Evaluates the body in a new variable sccope.
     */
    private void withVarScope(Closure body) {
        varScope = new StackVariableSet(varScope);
        try {
            body();
        } finally {
            varScope = varScope.parent;
        }
    }

    @Override
    void visitBlockStatement(BlockStatement block) {
        withVarScope {
            super.visitBlockStatement(block)
        }
    }

    @Override
    void visitDoWhileLoop(DoWhileStatement loop) {
        withVarScope {
            super.visitDoWhileLoop(loop)
        }
    }

    @Override
    void visitForLoop(ForStatement forLoop) {
        withVarScope {
            super.visitForLoop(forLoop)
        }
    }

    @Override
    void visitIfElse(IfStatement ifElse) {
        withVarScope {
            super.visitIfElse(ifElse)
        }
    }

    @Override
    void visitSwitch(SwitchStatement statement) {
        withVarScope {
            super.visitSwitch(statement)
        }
    }

    @Override
    void visitSynchronizedStatement(SynchronizedStatement sync) {
        withVarScope {
            super.visitSynchronizedStatement(sync)
        }
    }

    @Override
    void visitTryCatchFinally(TryCatchStatement statement) {
        withVarScope {
            super.visitTryCatchFinally(statement)
        }
    }

    @Override
    void visitWhileLoop(WhileStatement loop) {
        withVarScope {
            super.visitWhileLoop(loop)
        }
    }

    @Override
    void visitClosureExpression(ClosureExpression expression) {
        withVarScope {
            super.visitClosureExpression(expression)
        }
    }

    /**
     * @see org.codehaus.groovy.classgen.asm.BinaryExpressionHelper#evaluateEqual(org.codehaus.groovy.ast.expr.BinaryExpression, boolean)
     */
    void handleDeclarations(DeclarationExpression exp) {
        if (exp.leftExpression instanceof VariableExpression) {
            declareVariable((VariableExpression)exp.leftExpression);
        }
        if (exp instanceof TupleExpression) {
            TupleExpression te = (TupleExpression) exp;
            for (Expression e : te.expressions) {
                declareVariable((VariableExpression)e);
            }
        }
    }

    def declareVariable(Variable exp) {
        varScope.declare(exp.name)
    }
}
