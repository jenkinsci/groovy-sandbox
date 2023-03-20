package org.kohsuke.groovy.sandbox;

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.SwitchStatement;
import org.codehaus.groovy.ast.stmt.SynchronizedStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;

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
    StackVariableSet varScope;

    public boolean isLocalVariable(String name) {
        return varScope.has(name);
    }

    @Override
    public void visitMethod(MethodNode node) {
        varScope = null;
        try (StackVariableSet scope = new StackVariableSet(this)) {
            for (Parameter p : node.getParameters()) {
                declareVariable(p);
            }
            super.visitMethod(node);
        }
    }

    void withMethod(MethodNode node, Runnable r) {
        varScope = null;
        try (StackVariableSet scope = new StackVariableSet(this)) {
            for (Parameter p : node.getParameters()) {
                declareVariable(p);
            }
            r.run();
        }
    }

    @Override
    public void visitField(FieldNode node) {
        try (StackVariableSet scope = new StackVariableSet(this)) {
            super.visitField(node);
        }
    }

    @Override
    public void visitBlockStatement(BlockStatement block) {
        try (StackVariableSet scope = new StackVariableSet(this)) {
            super.visitBlockStatement(block);
        }
    }

    @Override
    public void visitDoWhileLoop(DoWhileStatement loop) {
        // Do-while loops are not actually supported by Groovy 2.x.
        try (StackVariableSet scope = new StackVariableSet(this)) {
            loop.getLoopBlock().visit(this);
        }
        try (StackVariableSet scope = new StackVariableSet(this)) {
            loop.setBooleanExpression((BooleanExpression) transform(loop.getBooleanExpression()));
        }
    }

    @Override
    public void visitForLoop(ForStatement forLoop) {
        try (StackVariableSet scope = new StackVariableSet(this)) {
            /*
                Groovy appears to always treat the left-hand side of forLoop as a declaration.
                i.e., the following code is error

                def h() {
                    def x =0;
                    def i = 0;
                    for (i in 0..9 ) {
                        x+= i;
                    }
                    println x;
                }

                script1414457812466.groovy: 18: The current scope already contains a variable of the name i
                 @ line 18, column 5.
                       for (i in 0..9 ) {
                       ^

                1 error

                Also see issue 17.
             */
            if (!ForStatement.FOR_LOOP_DUMMY.equals(forLoop.getVariable())) {
                // When using Java-style for loops, the 3 expressions are a ClosureListExpression and ForStatement.getVariable is a dummy value that we need to ignore.
                declareVariable(forLoop.getVariable());
            }
            forLoop.setCollectionExpression(transform(forLoop.getCollectionExpression()));
            forLoop.getLoopBlock().visit(this);
        }
    }

    @Override
    public void visitIfElse(IfStatement ifElse) {
        try (StackVariableSet scope = new StackVariableSet(this)) {
            ifElse.setBooleanExpression((BooleanExpression)transform(ifElse.getBooleanExpression()));
        }
        try (StackVariableSet scope = new StackVariableSet(this)) {
            ifElse.getIfBlock().visit(this);
        }
        try (StackVariableSet scope = new StackVariableSet(this)) {
            ifElse.getElseBlock().visit(this);
        }
    }

    @Override
    public void visitSwitch(SwitchStatement statement) {
        try (StackVariableSet scope = new StackVariableSet(this)) {
            super.visitSwitch(statement);
        }
    }

    @Override
    public void visitSynchronizedStatement(SynchronizedStatement sync) {
        sync.setExpression(transform(sync.getExpression()));
        try (StackVariableSet scope = new StackVariableSet(this)) {
            sync.getCode().visit(this);
        }
    }

    @Override
    public void visitTryCatchFinally(TryCatchStatement statement) {
        try (StackVariableSet scope = new StackVariableSet(this)) {
            super.visitTryCatchFinally(statement);
        }
    }

    @Override
    public void visitCatchStatement(CatchStatement statement) {
        try (StackVariableSet scope = new StackVariableSet(this)) {
            declareVariable(statement.getVariable());
            super.visitCatchStatement(statement);
        }
    }

    @Override
    public void visitWhileLoop(WhileStatement loop) {
        loop.setBooleanExpression((BooleanExpression) transform(loop.getBooleanExpression()));
        try (StackVariableSet scope = new StackVariableSet(this)) {
            loop.getLoopBlock().visit(this);
        }
    }

    /**
     * @see org.codehaus.groovy.classgen.asm.BinaryExpressionHelper#evaluateEqual(org.codehaus.groovy.ast.expr.BinaryExpression, boolean)
     */
    void handleDeclarations(DeclarationExpression exp) {
        Expression leftExpression = exp.getLeftExpression();
        if (leftExpression instanceof VariableExpression) {
            declareVariable((VariableExpression) leftExpression);
        } else if (leftExpression instanceof TupleExpression) {
            TupleExpression te = (TupleExpression) leftExpression;
            for (Expression e : te.getExpressions()) {
                declareVariable((VariableExpression)e);
            }
        }
    }

    void declareVariable(Variable exp) {
        varScope.declare(exp.getName());
    }
}
