package decaf.frontend.typecheck;

import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.driver.error.*;
import decaf.frontend.scope.ScopeStack;
import decaf.frontend.symbol.ClassSymbol;
import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.symbol.Symbol;
import decaf.frontend.symbol.VarSymbol;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.*;
import decaf.lowlevel.log.IndentPrinter;
import decaf.printing.PrettyScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Stack;

/**
 * The typer phase: type check abstract syntax tree and annotate nodes with inferred (and checked) types.
 */
public class Typer extends Phase<Tree.TopLevel, Tree.TopLevel> implements TypeLitVisited {

    public Typer(Config config) {
        super("typer", config);
    }

    private IndentPrinter printer = new IndentPrinter(config.output);
    private boolean debugMode = false;

    private void debug(String info) {
        if (debugMode) {
            printer.println(info);
            printer.flush();
        }
    }

    @Override
    public Tree.TopLevel transform(Tree.TopLevel tree) {
        var ctx = new ScopeStack(tree.globalScope);
        tree.accept(this, ctx);
        return tree;
    }

    @Override
    public void onSucceed(Tree.TopLevel tree) {
        if (config.target.equals(Config.Target.PA2)) {
            var printer = new PrettyScope(new IndentPrinter(config.output));
            printer.pretty(tree.globalScope);
            printer.flush();
        }
    }

    @Override
    public void visitTopLevel(Tree.TopLevel program, ScopeStack ctx) {
        for (var clazz : program.classes) {
            clazz.accept(this, ctx);
        }
    }

    @Override
    public void visitClassDef(Tree.ClassDef clazz, ScopeStack ctx) {
        ctx.open(clazz.symbol.scope);
        for (var field : clazz.fields) {
            field.accept(this, ctx);
        }
        ctx.close();
    }

    @Override
    public void visitMethodDef(Tree.MethodDef method, ScopeStack ctx) {
        ctx.open(method.symbol.scope);
        method.body.ifPresent(objects -> {
            objects.accept(this, ctx);
            if (!method.symbol.type.returnType.isVoidType() && !objects.returns) {
                issue(new MissingReturnError(objects.pos));
            }
        });
        ctx.close();
    }

    /**
     * To determine if a break statement is legal or not, we need to know if we are inside a loop, i.e.
     * loopLevel {@literal >} 1?
     * <p>
     * Increase this counter when entering a loop, and decrease it when leaving a loop.
     */
    private int loopLevel = 0;

    @Override
    public void visitBlock(Tree.Block block, ScopeStack ctx) {
        ctx.open(block.scope);
        for (var stmt : block.stmts) {
            stmt.accept(this, ctx);
        }
        ctx.close();
        block.returns = !block.stmts.isEmpty() && block.stmts.get(block.stmts.size() - 1).returns;
        block.returnType = block.stmts.isEmpty() ? Tree.Stmt.ReturnType.NONE : block.stmts.get(block.stmts.size() - 1).returnType;
    }

    @Override
    public void visitAssign(Tree.Assign stmt, ScopeStack ctx) {
        stmt.lhs.accept(this, ctx);
        stmt.rhs.accept(this, ctx);

        var lt = stmt.lhs.type;
        var rt = stmt.rhs.type;

        if (lt.noError() && lt.isFuncType()) {
            if (stmt.lhs instanceof Tree.VarSel) {
                var lhsVar = (Tree.VarSel) stmt.lhs;
                if (lhsVar.isMethodName) {
                    issue(new AssignToMethodError(stmt.pos, lhsVar.name));
                }
            }
        }

        if (!lambdaBeingDefined.empty() && lt.noError()) {
            if (stmt.lhs instanceof Tree.VarSel) {
                var lhsVar = (Tree.VarSel) stmt.lhs;
                var varSymbol = lhsVar.getReceiverVar();

                if (varSymbol.isPresent()) {
                    if ((varSymbol.get().type.isClassType() && lhsVar.receiver.isPresent()) || varSymbol.get().type.isArrayType()) {

                    } else {
                        String name = varSymbol.get().name;
                        boolean isInLambda = ctx.lookupBeforeInLambda(name, localVarDefPos.orElse(varSymbol.get().pos)).isPresent();

                        if (!isInLambda) {
                            boolean isInFormal = ctx.lookupOutOfLambdaInFormalScope(name, localVarDefPos.orElse(varSymbol.get().pos)).isPresent();

                            if (isInFormal) {
                                issue(new LambdaCaptureError(stmt.pos));
                                return;
                            } else {
                                boolean isInClass = ctx.currentClass().scope.lookup(lhsVar.name).isPresent();
                                if (!isInClass) {
                                    issue(new LambdaCaptureError(stmt.pos));
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (lt.noError() && !rt.subtypeOf(lt)) {
            issue(new IncompatBinOpError(stmt.pos, lt.toString(), "=", rt.toString()));
        }

    }

    @Override
    public void visitExprEval(Tree.ExprEval stmt, ScopeStack ctx) {
        stmt.expr.accept(this, ctx);
    }

    @Override
    public void visitIf(Tree.If stmt, ScopeStack ctx) {
        checkTestExpr(stmt.cond, ctx);
        stmt.trueBranch.accept(this, ctx);
        stmt.falseBranch.ifPresent(b -> b.accept(this, ctx));
        // if-stmt returns a value iff both branches return

        Tree.Stmt.ReturnType ifReturnType = stmt.trueBranch.returnType;
        Tree.Stmt.ReturnType elseReturnType = stmt.falseBranch.isPresent() ? stmt.falseBranch.get().returnType : Tree.Stmt.ReturnType.NONE;
        stmt.returnType = ifReturnType.compareWith(elseReturnType);

        stmt.returns = stmt.trueBranch.returns && stmt.falseBranch.isPresent() && stmt.falseBranch.get().returns;

    }

    @Override
    public void visitWhile(Tree.While loop, ScopeStack ctx) {
        checkTestExpr(loop.cond, ctx);
        loopLevel++;
        loop.body.accept(this, ctx);
        loopLevel--;
    }

    @Override
    public void visitFor(Tree.For loop, ScopeStack ctx) {
        ctx.open(loop.scope);
        loop.init.accept(this, ctx);
        checkTestExpr(loop.cond, ctx);
        loop.update.accept(this, ctx);
        loopLevel++;
        for (var stmt : loop.body.stmts) {
            stmt.accept(this, ctx);
        }
        loopLevel--;
        ctx.close();
    }

    @Override
    public void visitBreak(Tree.Break stmt, ScopeStack ctx) {
        if (loopLevel == 0) {
            issue(new BreakOutOfLoopError(stmt.pos));
        }
    }

    Stack<ArrayList<Type>> lambdaReturnTypes = new Stack<>();

    @Override
    public void visitReturn(Tree.Return stmt, ScopeStack ctx) {
        if (!lambdaBeingDefined.empty()) {
            if (stmt.expr.isPresent()) {
                var expr = stmt.expr.get();
                expr.accept(this, ctx);
                if (expr.type.isVoidType()) {
                    stmt.returnType = Tree.Stmt.ReturnType.VOID;
                } else {
                    stmt.returnType = Tree.Stmt.ReturnType.EXPR;
                }
                lambdaReturnTypes.peek().add(expr.type);

            } else {
                stmt.returnType = Tree.Stmt.ReturnType.NONE;
                lambdaReturnTypes.peek().add(BuiltInType.VOID);

            }
            stmt.returns = stmt.expr.isPresent();
        } else {
            var expected = ctx.currentMethod().type.returnType;
            if (stmt.expr.isPresent()) {
                var expr = stmt.expr.get();
                expr.accept(this, ctx);
                if (expr.type.isVoidType()) {
                    stmt.returnType = Tree.Stmt.ReturnType.VOID;
                } else {
                    stmt.returnType = Tree.Stmt.ReturnType.EXPR;
                }
            } else {
                stmt.returnType = Tree.Stmt.ReturnType.NONE;
            }
            var actual = stmt.expr.map(e -> e.type).orElse(BuiltInType.VOID);
            if (actual.noError() && !actual.subtypeOf(expected)) {
                issue(new BadReturnTypeError(stmt.pos, expected.toString(), actual.toString()));
            }
            stmt.returns = stmt.expr.isPresent();
        }
    }

    @Override
    public void visitPrint(Tree.Print stmt, ScopeStack ctx) {
        int i = 0;
        for (var expr : stmt.exprs) {
            expr.accept(this, ctx);
            i++;
            if (expr.type.noError() && !expr.type.isBaseType()) {
                issue(new BadPrintArgError(expr.pos, Integer.toString(i), expr.type.toString()));
            }
        }
    }

    private void checkTestExpr(Tree.Expr expr, ScopeStack ctx) {
        expr.accept(this, ctx);
        if (expr.type.noError() && !expr.type.eq(BuiltInType.BOOL)) {
            issue(new BadTestExpr(expr.pos));
        }
    }

    // Expressions

    @Override
    public void visitIntLit(Tree.IntLit that, ScopeStack ctx) {
        that.type = BuiltInType.INT;
    }

    @Override
    public void visitBoolLit(Tree.BoolLit that, ScopeStack ctx) {
        that.type = BuiltInType.BOOL;
    }

    @Override
    public void visitStringLit(Tree.StringLit that, ScopeStack ctx) {
        that.type = BuiltInType.STRING;
    }

    @Override
    public void visitNullLit(Tree.NullLit that, ScopeStack ctx) {
        that.type = BuiltInType.NULL;
    }

    @Override
    public void visitReadInt(Tree.ReadInt readInt, ScopeStack ctx) {
        readInt.type = BuiltInType.INT;
    }

    @Override
    public void visitReadLine(Tree.ReadLine readStringExpr, ScopeStack ctx) {
        readStringExpr.type = BuiltInType.STRING;
    }

    @Override
    public void visitUnary(Tree.Unary expr, ScopeStack ctx) {
        expr.operand.accept(this, ctx);
        var t = expr.operand.type;
        if (t.noError() && !compatible(expr.op, t)) {
            // Only report this error when the operand has no error, to avoid nested errors flushing.
            issue(new IncompatUnOpError(expr.pos, Tree.opStr(expr.op), t.toString()));
        }

        // Even when it doesn't type check, we could make a fair guess based on the operator kind.
        // Let's say the operator is `-`, then one possibly wants an integer as the operand.
        // Once he/she fixes the operand, according to our type inference rule, the whole unary expression
        // must have type int! Thus, we simply _assume_ it has type int, rather than `NoType`.
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.UnaryOp op, Type operand) {
        return switch (op) {
            case NEG -> operand.eq(BuiltInType.INT); // if e : int, then -e : int
            case NOT -> operand.eq(BuiltInType.BOOL); // if e : bool, then !e : bool
        };
    }

    public Type resultTypeOf(Tree.UnaryOp op) {
        return switch (op) {
            case NEG -> BuiltInType.INT;
            case NOT -> BuiltInType.BOOL;
        };
    }

    @Override
    public void visitBinary(Tree.Binary expr, ScopeStack ctx) {
        expr.lhs.accept(this, ctx);
        expr.rhs.accept(this, ctx);

        var t1 = expr.lhs.type;
        var t2 = expr.rhs.type;
        if (t1.noError() && t2.noError() && !compatible(expr.op, t1, t2)) {
            issue(new IncompatBinOpError(expr.pos, t1.toString(), Tree.opStr(expr.op), t2.toString()));
        }
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.BinaryOp op, Type lhs, Type rhs) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            // if e1, e2 : int, then e1 + e2 : int
            return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
        }

        if (op.equals(Tree.BinaryOp.AND) || op.equals(Tree.BinaryOp.OR)) { // logic
            // if e1, e2 : bool, then e1 && e2 : bool
            return lhs.eq(BuiltInType.BOOL) && rhs.eq(BuiltInType.BOOL);
        }

        if (op.equals(Tree.BinaryOp.EQ) || op.equals(Tree.BinaryOp.NE)) { // eq
            // if e1 : T1, e2 : T2, T1 <: T2 or T2 <: T1, then e1 == e2 : bool
            return lhs.subtypeOf(rhs) || rhs.subtypeOf(lhs);
        }

        // compare
        // if e1, e2 : int, then e1 > e2 : bool
        return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
    }

    public Type resultTypeOf(Tree.BinaryOp op) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            return BuiltInType.INT;
        }
        return BuiltInType.BOOL;
    }

    @Override
    public void visitNewArray(Tree.NewArray expr, ScopeStack ctx) {
        expr.elemType.accept(this, ctx);
        expr.length.accept(this, ctx);
        var et = expr.elemType.type;
        var lt = expr.length.type;

        if (et.isVoidType()) {
            issue(new BadArrElementError(expr.elemType.pos));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.type = new ArrayType(et);
        }

        if (lt.noError() && !lt.eq(BuiltInType.INT)) {
            issue(new BadNewArrayLength(expr.length.pos));
        }
    }

    @Override
    public void visitNewClass(Tree.NewClass expr, ScopeStack ctx) {
        var clazz = ctx.lookupClass(expr.clazz.name);
        if (clazz.isPresent()) {
            if (clazz.get().isAbstract()) {
                issue((new InstantiateAbsClassError(expr.pos, expr.clazz.name)));
                expr.type = BuiltInType.ERROR;
            } else {
                expr.symbol = clazz.get();
                expr.type = expr.symbol.type;
            }

        } else {
            issue(new ClassNotFoundError(expr.pos, expr.clazz.name));
            expr.type = BuiltInType.ERROR;
        }
    }

    @Override
    public void visitThis(Tree.This expr, ScopeStack ctx) {
        if (ctx.currentMethod().isStatic()) {
            issue(new ThisInStaticFuncError(expr.pos));
        }
        expr.type = ctx.currentClass().type;
    }

    private boolean allowClassNameVar = false;
    private Stack<Tree.Lambda> lambdaBeingDefined = new Stack<>();

    @Override
    public void visitVarSel(Tree.VarSel expr, ScopeStack ctx) {
        expr.type = BuiltInType.ERROR;
        Type rt = null;
        boolean thisClass = false;
        boolean isMethod = false;

        if (expr.receiver.isEmpty()) {
            // Variable, which should be complicated since a legal variable could refer to a local var,
            // a visible member var, and a class name.

//            var symbol = ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos));
            var symbol = !lambdaBeingDefined.empty() ? ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos), symbolBeingDefined)
                    : ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos));
            if (symbol.isPresent()) {
                if (symbol.get().isVarSymbol()) {
                    var var = (VarSymbol) symbol.get();
                    expr.symbol = var;
                    expr.type = var.type;
                    expr.thisClass = true;
                    expr.className = ctx.currentClass().name;
                    if (var.isMemberVar()) {
                        if (ctx.currentMethod().isStatic()) {
                            issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                        } else {
                            expr.setThis();
                        }
                    }
                    return;
                }

                if (symbol.get().isMethodSymbol()) {
                    thisClass = true;
                    expr.setThis();
                    rt = ctx.currentClass().type;
                    isMethod = true;
                    expr.thisClass = true;
                    expr.className = ctx.currentClass().name;
                    expr.isMethodName = true;
                }

                if (symbol.get().isClassSymbol() && allowClassNameVar) { // special case: a class name
                    var clazz = (ClassSymbol) symbol.get();
                    expr.type = clazz.type;
                    expr.isClassName = true;
                    expr.className = clazz.name;
                    return;
                }
            }

            if (!isMethod) {
                expr.type = BuiltInType.ERROR;
                issue(new UndeclVarError(expr.pos, expr.name));
                return;
            }

        } else {
            // has receiver
            var receiver = expr.receiver.get();
            allowClassNameVar = true;
            receiver.accept(this, ctx);
            allowClassNameVar = false;
            rt = receiver.type;
            expr.type = BuiltInType.ERROR;

            if (receiver instanceof Tree.VarSel) {
                var v1 = (Tree.VarSel) receiver;
                if (v1.isClassName) {
                    // here is a typecall
                    expr.thisClass = false;
                    expr.className = v1.name;
                    expr.parentIsClassName = true;
                    typeVarSel(expr, false, v1.name, ctx, true);
                    return;
                }
            }
        }

        assert rt != null;
        if (!rt.noError()) {
            return;
        }

        if (!rt.isClassType()) {
            if (rt.isArrayType() && expr.name.equals("length")) {
                expr.callsLength = true;
                expr.type = new FunType(BuiltInType.INT, new ArrayList<>());
                return;
            } else {
                issue(new NotClassFieldError(expr.pos, expr.name, rt.toString()));
                expr.isMethodName = false;
                return;
            }
        }

        // here is a typecall
        var ct = (ClassType) rt;
        expr.thisClass = thisClass;
        expr.className = ct.name;
        typeVarSel(expr, thisClass, ct.name, ctx, false);
    }

    private void typeVarSel(Tree.VarSel expr, boolean thisClass, String className, ScopeStack ctx, boolean requireStatic) {
        var clazz = thisClass ? ctx.currentClass() : ctx.getClass(className);
//        var symbol = clazz.scope.lookup(expr.name);
        var symbol = !lambdaBeingDefined.empty() ? ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos), symbolBeingDefined)
//                    :ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos));
                : clazz.scope.lookup(expr.name);
        if (symbol.isPresent()) {
            if (symbol.get().isMethodSymbol()) {
                var method = (MethodSymbol) symbol.get();
                expr.isMethodName = true;
                if (requireStatic && !method.isStatic()) {
                    issue(new NotClassFieldError(expr.pos, expr.name, clazz.type.toString()));
                    expr.isMethodName = false;
                }

                if (thisClass && ctx.currentMethod().isStatic() && !method.isStatic()) {
                    issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, method.name));
                    expr.isMethodName = false;
                }
                expr.type = method.type;


            } else if (symbol.get().isVarSymbol()) {
                if (requireStatic) {
                    // special case like MyClass.foo: report error cannot access field 'foo' from 'class : MyClass'
                    issue(new NotClassFieldError(expr.pos, expr.name, clazz.type.toString()));
                } else {
                    var var = (VarSymbol) symbol.get();
                    if (var.isMemberVar()) {
                        expr.symbol = var;
                        expr.type = var.type;
                        if (!ctx.currentClass().type.subtypeOf(var.getOwner().type)) {
                            // member vars are protected
                            issue(new FieldNotAccessError(expr.pos, expr.name, clazz.type.toString()));
                        }
                    }
                }
            } else {
                issue(new NotClassFieldError(expr.pos, expr.name, clazz.type.toString()));
            }
        } else {
            issue(new FieldNotFoundError(expr.pos, expr.name, clazz.type.toString()));
        }
    }

    @Override
    public void visitIndexSel(Tree.IndexSel expr, ScopeStack ctx) {
        expr.array.accept(this, ctx);
        expr.index.accept(this, ctx);
        var at = expr.array.type;
        var it = expr.index.type;

        if (!at.isArrayType()) {
            debug("Not ArrayType: " + at);
            if (at.noError()) {
                issue(new NotArrayError(expr.array.pos));
            }

            expr.type = BuiltInType.ERROR;
            return;
        }

        expr.type = ((ArrayType) at).elementType;
        if (!it.eq(BuiltInType.INT)) {
            issue(new SubNotIntError(expr.pos));
        }
    }

    @Override
    public void visitCall(Tree.Call expr, ScopeStack ctx) {

        expr.type = BuiltInType.ERROR;
        Type rt;
        boolean thisClass = false;

        var receiver = expr.receiver;
        allowClassNameVar = true;
        receiver.accept(this, ctx);
        allowClassNameVar = false;
        rt = receiver.type; // should be funtype

        if (receiver instanceof Tree.VarSel) {
            var v1 = (Tree.VarSel) receiver;
            expr.methodName = v1.name;

            if (v1.parentIsClassName) {
                // Special case: invoking a static method, like MyClass.foo()
                typeCall(expr, false, v1.className, ctx);
                return;
            }

            if (rt.noError()) {
                if (v1.callsLength) { // Special case: array.length()
                    if (!expr.args.isEmpty()) {
                        issue(new BadLengthArgError(expr.pos, expr.args.size()));
                    }
                    expr.isArrayLength = true;
                    expr.type = BuiltInType.INT;
                    return;
                }

                if (rt.isFuncType()) {
                    typeCall(expr, v1.thisClass, v1.className, ctx);
                } else {
                    issue(new NotCallableTypeError(rt.toString(), expr.pos));
                }

//            else {
//                issue(new NotClassFieldError(expr.pos, expr.methodName, rt.toString()));
//            }
            }
        } else if (rt.noError() && rt.isFuncType()) {
            var fun = (FunType) rt;
            if (receiver instanceof Tree.Lambda) {
                expr.methodName = "lambda expression";
                expr.type = ((Tree.Lambda) receiver).symbol.type.returnType;
            } else if (receiver instanceof Tree.Call) {
                expr.methodName = "lambda expression";
                expr.type = fun.returnType;
            } else {
                expr.type = fun.returnType;
            }

            // typing args
            var args = expr.args;
            for (var arg : args) {
                arg.accept(this, ctx);
            }

            // check signature compatibility
            if (fun.arity() != args.size()) {
                issue(new BadArgCountError(expr.pos, expr.methodName, fun.arity(), args.size()));
            }
            var iter1 = fun.argTypes.iterator();
            var iter2 = expr.args.iterator();
            for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
                Type t1 = iter1.next();
                Tree.Expr e = iter2.next();
                Type t2 = e.type;
                if (t2.noError() && !t2.subtypeOf(t1)) {
                    issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
                }
            }

        } else if (rt.noError()) {
            issue(new NotCallableTypeError(rt.toString(), expr.pos));
        }

    }

    private void typeCall(Tree.Call call, boolean thisClass, String className, ScopeStack ctx) {

        ClassSymbol clazz;
        Optional<Symbol> symbol;

        if (thisClass) {
            clazz = ctx.currentClass();
            symbol = !lambdaBeingDefined.empty() ? ctx.lookupBefore(call.methodName, call.pos, symbolBeingDefined)
                    : ctx.lookupBefore(call.methodName, call.pos);
        } else {
            clazz = ctx.getClass(className);
            symbol = !lambdaBeingDefined.empty() ? ctx.lookupBefore(call.methodName, call.pos, symbolBeingDefined)
                    : clazz.scope.lookup(call.methodName);
        }

        debug("In Typer: Calling func @" + call.pos);
        debug("In Typer: class Name: " + clazz.name);

        //var symbol = clazz.scope.lookup(call.methodName);

//                     : clazz.scope.lookup(call.methodName);

        debug("In Typer: find symbol " + call.methodName + ": " + symbol.toString());

        if (symbol.isPresent()) {
            if (symbol.get().isMethodSymbol()) {
                var method = (MethodSymbol) symbol.get();
                call.methodSymbol = method;
                call.type = method.type.returnType;
//                if (requireStatic && !method.isStatic()) {
//                    issue(new NotClassFieldError(call.pos, call.methodName, clazz.type.toString()));
//                    return;
//                }

                // Cannot call this's member methods in a static method
//                if (thisClass && ctx.currentMethod().isStatic() && !method.isStatic()) {
//                    issue(new RefNonStaticError(call.pos, ctx.currentMethod().name, method.name));
//                }

                // typing args
                var args = call.args;
                for (var arg : args) {
                    arg.accept(this, ctx);
                }

                // check signature compatibility
                if (method.type.arity() != args.size()) {
                    issue(new BadArgCountError(call.pos, method.name, method.type.arity(), args.size()));
                }
                var iter1 = method.type.argTypes.iterator();
                var iter2 = call.args.iterator();
                for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
                    Type t1 = iter1.next();
                    Tree.Expr e = iter2.next();
                    Type t2 = e.type;
                    if (t2.noError() && !t2.subtypeOf(t1)) {
                        issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
                    }
                }

            } else if (symbol.get().isVarSymbol() && symbol.get().type.isFuncType()) {
                var var = (VarSymbol) symbol.get();
                call.varSymbol = var;
                var fun = (FunType) var.type;
                call.type = fun.returnType;

                // typing args
                var args = call.args;
                for (var arg : args) {
                    arg.accept(this, ctx);
                }

                // check signature compatibility
                if (fun.arity() != args.size()) {
                    issue(new BadArgCountError(call.pos, var.name, fun.arity(), args.size()));
                }
                var iter1 = fun.argTypes.iterator();
                var iter2 = call.args.iterator();
                for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
                    Type t1 = iter1.next();
                    Tree.Expr e = iter2.next();
                    Type t2 = e.type;
                    if (t2.noError() && !t2.subtypeOf(t1)) {
                        issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
                    }
                }

            } else {
                if (symbol.get().type.noError() && !symbol.get().type.isFuncType()) {
                    issue(new NotCallableTypeError(symbol.get().type.toString(), symbol.get().pos));
                }
            }
//            else {
//                issue(new NotClassMethodError(call.pos, call.methodName, clazz.type.toString()));
//            }
        }
//        else {
//            issue(new FieldNotFoundError(call.pos, call.methodName, clazz.type.toString()));
//        }
        debug("In Typer: RetType func @" + call.pos + " is " + call.type);
    }

    @Override
    public void visitClassTest(Tree.ClassTest expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);
        expr.type = BuiltInType.BOOL;

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }
        var clazz = ctx.lookupClass(expr.is.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.is.name));
        } else {
            expr.symbol = clazz.get();
        }
    }

    @Override
    public void visitClassCast(Tree.ClassCast expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }

        var clazz = ctx.lookupClass(expr.to.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.to.name));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
        }
    }

    private ArrayList<String> symbolBeingDefined = new ArrayList<>();

    @Override
    public void visitLocalVarDef(Tree.LocalVarDef stmt, ScopeStack ctx) {

        if (stmt.initVal.isEmpty()) return;

        var initVal = stmt.initVal.get();

        if (initVal instanceof Tree.Lambda) {
            symbolBeingDefined.add(stmt.symbol.name);
        }

        localVarDefPos = Optional.ofNullable(stmt.id.pos);
        initVal.accept(this, ctx);
        localVarDefPos = Optional.empty();
        var lt = stmt.symbol.type;
        var rt = initVal.type;

        if (lt.eq(BuiltInType.UNCERTAIN)) {
            if (rt.eq(BuiltInType.VOID)) {
                stmt.symbol.setDeductiveType(BuiltInType.ERROR);
                issue(new BadVarTypeError(stmt.pos, stmt.name));
                return;
            }

            debug("Initial var of " + stmt.name + " @" + initVal.pos + " is " + initVal.type);
            stmt.symbol.setDeductiveType(rt);

        } else {
            if (lt.noError() && !rt.subtypeOf(lt)) {
                issue(new IncompatBinOpError(stmt.assignPos, lt.toString(), "=", rt.toString()));
            }
        }

        if (initVal instanceof Tree.Lambda) {
            symbolBeingDefined.remove(stmt.symbol.name);
        }


    }

    // Only usage: check if an initializer cyclically refers to the declared variable, e.g. var x = x + 1
    private Optional<Pos> localVarDefPos = Optional.empty();

    @Override
    public void visitLambda(Tree.Lambda lambda, ScopeStack ctx) {
        ctx.open(lambda.symbol.scope);
        lambdaBeingDefined.push(lambda);

        if (lambda.isBlock) {
            lambdaReturnTypes.push(new ArrayList<>());
            lambda.block.accept(this, ctx);

            // deduce return type
            Type type = null;
            if (lambdaReturnTypes.peek().isEmpty()) {
                type = BuiltInType.VOID;
            } else {
                type = getTypeUpperBound(lambdaReturnTypes.peek(), lambda.block.pos);
            }

            if (lambda.block.returnType.missingReturn() || (lambda.block.returnType.isNone() && !type.isVoidType())) {
                issue(new MissingReturnError(lambda.block.pos));
            }

            if (type.hasError()) {
                issue(new IncompatReturnError(lambda.block.pos));
            }

            lambda.type = new FunType(type, lambda.symbol.type.argTypes);
            lambda.symbol.type = (FunType) lambda.type;

            lambdaReturnTypes.pop();

        } else {
            ctx.open(lambda.symbol.scope.nestedLocalScope());
            lambda.expression.accept(this, ctx);
            debug("Lambda Expression Type @" + lambda.pos + " " + lambda.expression.type);
            lambda.type = new FunType(lambda.expression.type, lambda.symbol.type.argTypes);
            lambda.symbol.type = (FunType) lambda.type;
            ctx.close();
        }
        ctx.close();
        lambdaBeingDefined.pop();
        debug("in Typer: Lambda type " + lambda.type);
    }

    private Type getTypeUpperBound(List<Type> argTypes, Pos pos) {
        Type t = null;

        for (var v : argTypes) {
            if (!v.eq(BuiltInType.NULL)) {
                t = v;
                break;
            }
        }

        boolean incompat = false;

        if (t == null) {
            t = BuiltInType.NULL;
        } else if (t.hasError()){
            incompat = true;
        } else if (t.isBaseType() || t.isVoidType() || t.isArrayType()) {

            for (var v : argTypes) {
                if (!v.eq(t)) {
                    incompat = true;
                    break;
                }
            }

        } else if (t.isClassType()) {
            var ct = (ClassType) t;

            while (true) {
                boolean reloop = false;
                for (var v : argTypes) { // 对参数列表中每一个参数的类型

                    if (!v.subtypeOf(ct)) { // 如果不是子类，则置t为t的父类
                        if (ct.superType.isPresent()) { // 如果t还有父类，则重新循环
                            ct = ct.superType.get();
                            t = ct;
                            reloop = true;
                            break;
                        } else { // 如果t没父类了，则不相容
                            incompat = true;
                            break;
                        }
                    }
                }
                if (!reloop) {
                    break;
                }
            }


        } else if (t.isFuncType()) {
            var ft = (FunType) t;
            ArrayList<Type> rt = new ArrayList<>();
            ArrayList<ArrayList<Type>> ithArgs = new ArrayList<>();

            for (int i = 0; i < ft.argTypes.size(); i++) {
                ithArgs.add(new ArrayList<>());
            }

            for (var v : argTypes) {
                if (!v.isFuncType()) {
                    incompat = true;
                    break;
                } else {
                    var fv = (FunType) v;
                    if (ft.argTypes.size() != fv.argTypes.size()) {
                        incompat = true;
                        break;
                    }

                    if (fv.returnType.noError()) {
                        rt.add(fv.returnType);
                        for (int i = 0; i < fv.argTypes.size(); i++) {
                            Type type = fv.argTypes.get(i);
                            if (type.noError()) {
                                ithArgs.get(i).add(type);
                            } else {
                                incompat = true;
                                break;
                            }

                        }
                    } else {
                        incompat = true;
                    }

                    if (incompat) break;
                }
            }

            if (!incompat) {
                // rt 找到上界
                // ithArgs 每个找到下界

                var r = getTypeUpperBound(rt, pos);
                if (r.hasError()) {
                    incompat = true;
                } else {
                    var args = new ArrayList<Type>();

                    for (var v : ithArgs) {
                        var ithArgType = getTypeLowerBound(v, pos);
                        if (ithArgType.hasError()) {
                            incompat = true;
                            break;
                        } else {
                            args.add(ithArgType);
                        }
                    }

                    if (!incompat)
                        t = new FunType(r, args);
                }
            }
        }

        if (incompat) {
            t = BuiltInType.ERROR;
        }
        return t;
    }

    private Type getTypeLowerBound(List<Type> argTypes, Pos pos) {
        Type t = null;

        for (var v : argTypes) {
            if (!v.eq(BuiltInType.NULL)) {
                t = v;
                break;
            }
        }

        boolean incompat = false;

        if (t == null) {
            t = BuiltInType.NULL;
        } else if (t.hasError()){
            incompat = true;
        } else if (t.isBaseType() || t.isVoidType() || t.isArrayType()) {

            for (var v : argTypes) {
                if (!v.eq(t)) {
                    incompat = true;
                    break;
                }
            }

        } else if (t.isClassType()) {
            var ct = (ClassType) t;
            while (true) {
                boolean reloop = false;
                for (var v : argTypes) { // 对参数列表中每个参数类型进行检查
                    if (v.subtypeOf(ct) && !v.eq(ct)) {   // 如果v是t的真子类，则令t=v，重新循环
                        t = v;
                        ct = (ClassType) t;
                        reloop = true;
                        break;
                    } else if (!ct.subtypeOf(v)) { // 如果v不是t的真子类，t也不是v的子类，即!(v<t) && !(t<=v)，则t和v无法比较，不相容
                        incompat = true;
                        break;
                    }
                }
                if (!reloop) {
                    break;
                }
            }

        } else if (t.isFuncType()) {
            var ft = (FunType) t;
            ArrayList<Type> rt = new ArrayList<>();
            ArrayList<ArrayList<Type>> ithArgs = new ArrayList<>();

            for (int i = 0; i < ft.argTypes.size(); i++) {
                ithArgs.add(new ArrayList<>());
            }

            for (var v : argTypes) {
                if (!v.isFuncType()) {
                    incompat = true;
                    break;
                } else {
                    var fv = (FunType) v;
                    if (ft.argTypes.size() != fv.argTypes.size()) {
                        incompat = true;
                        break;
                    }

                    if (fv.returnType.noError()) {
                        rt.add(fv.returnType);
                        for (int i = 0; i < fv.argTypes.size(); i++) {
                            Type type = fv.argTypes.get(i);
                            if (type.noError()) {
                                ithArgs.get(i).add(type);
                            } else {
                                incompat = true;
                                break;
                            }
                        }
                    } else {
                        incompat = true;
                    }

                    if (incompat) break;
                }
            }

            if (!incompat) {
                // rt 找到下界
                // ithArgs 每个找到上界

                var r = getTypeLowerBound(rt, pos);
                if (r.hasError()) {
                    incompat = true;
                } else {
                    var args = new ArrayList<Type>();

                    for (var v : ithArgs) {
                        var ithArgType = getTypeUpperBound(v, pos);
                        if (ithArgType.hasError()) {
                            incompat = true;
                            break;
                        } else {
                            args.add(ithArgType);
                        }
                    }

                    if (!incompat)
                        t = new FunType(r, args);
                }

            }
        }

        if (incompat) {
            t = BuiltInType.ERROR;
        }
        return t;
    }
}
