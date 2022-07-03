package decaf.frontend.tacgen;

import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.symbol.VarSymbol;
import decaf.frontend.tree.Tree;
import decaf.frontend.tree.Visitor;
import decaf.frontend.type.BuiltInType;
import decaf.lowlevel.instr.Temp;
import decaf.lowlevel.label.Label;
import decaf.lowlevel.tac.FuncVisitor;
import decaf.lowlevel.tac.Intrinsic;
import decaf.lowlevel.tac.RuntimeError;
import decaf.lowlevel.tac.TacInstr;

import java.util.ArrayList;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * TAC emitter. Traverse the tree and emit TAC.
 * <p>
 * When emitting TAC, we use utility methods from {@link FuncVisitor}, so that we don't bother
 * ourselves understanding the underlying format of TAC instructions.
 * <p>
 * See {@link #emitIfThen} for the usage of {@link Consumer}.
 */
public interface TacEmitter extends Visitor<FuncVisitor> {

    /**
     * Record the exit labels of loops entered so far. In this way, when we encounter a break statement, we know the
     * exact label we will jump to.
     * <p>
     * Push a label when entering a loop, and pop when leaving a loop.
     */
    Stack<Label> loopExits = new Stack<>();

    boolean debugMode = false;

    static void debug(Tree.Expr expr, FuncVisitor mv, String info) {
        if (!debugMode) return;
        if (expr.val!=null) {
            mv.visitPrint(info+": Expr@"+expr.pos.toString()+" is " + expr.val.toString()+", val is ");
            mv.visitIntrinsicCall(Intrinsic.PRINT_INT, expr.val);
            mv.visitPrint("\n");
        } else {
            mv.visitPrint(info+": Expr@"+expr.pos.toString()+" is null ");
            mv.visitPrint("\n");
        }
    }

    static void debug(Temp temp, FuncVisitor mv, String info) {
        if (!debugMode) return;

        mv.visitPrint(info+" ");
        if (temp == null) mv.visitPrint("null");
        else mv.visitIntrinsicCall(Intrinsic.PRINT_INT, temp);
        mv.visitPrint("\n");
    }

    static void debug(FuncVisitor mv, String info) {
        if (!debugMode) return;
        mv.visitPrint(info);
        mv.visitPrint("\n");
    }

    @Override
    default void visitBlock(Tree.Block block, FuncVisitor mv) {
        for (var stmt : block.stmts) {
            stmt.accept(this, mv);
        }
    }

    @Override
    default void visitLocalVarDef(Tree.LocalVarDef def, FuncVisitor mv) {
        def.symbol.temp = mv.freshTemp();
        if (def.initVal.isEmpty()) return;
        var initVal = def.initVal.get();

        initVal.accept(this, mv);
        mv.visitAssign(def.symbol.temp, initVal.val);

        debug(def.symbol.temp, mv, "VarDef: "+def.name+" @"+def.pos+" is ");
    }

    @Override
    default void visitAssign(Tree.Assign assign, FuncVisitor mv) {
        if (assign.lhs instanceof Tree.IndexSel) {
            var indexSel = (Tree.IndexSel) assign.lhs;
            indexSel.array.accept(this, mv);
            indexSel.index.accept(this, mv);
            var addr = emitArrayElementAddress(indexSel.array.val, indexSel.index.val, mv);
            assign.rhs.accept(this, mv);
            mv.visitStoreTo(addr, assign.rhs.val);
        } else if (assign.lhs instanceof Tree.VarSel) {
            var v = (Tree.VarSel) assign.lhs;
            if (v.symbol.isVarSymbol()) {
                VarSymbol varSymbol = (VarSymbol)v.symbol;
                if (varSymbol.isMemberVar()) {
                    var object = v.receiver.get();
                    object.accept(this, mv);
                    assign.rhs.accept(this, mv);
                    mv.visitMemberWrite(object.val, varSymbol.getOwner().name, v.name, assign.rhs.val);
                } else { // local or param
                    assign.rhs.accept(this, mv);
                    mv.visitAssign(varSymbol.temp, assign.rhs.val);
                }
            }
        }
    }

    @Override
    default void visitExprEval(Tree.ExprEval eval, FuncVisitor mv) {
        eval.expr.accept(this, mv);
    }

    @Override
    default void visitIf(Tree.If stmt, FuncVisitor mv) {
        stmt.cond.accept(this, mv);
        Consumer<FuncVisitor> trueBranch = v -> stmt.trueBranch.accept(this, v);

        if (stmt.falseBranch.isEmpty()) {
            emitIfThen(stmt.cond.val, trueBranch, mv);
        } else {
            Consumer<FuncVisitor> falseBranch = v -> stmt.falseBranch.get().accept(this, v);
            emitIfThenElse(stmt.cond.val, trueBranch, falseBranch, mv);
        }
    }

    @Override
    default void visitWhile(Tree.While loop, FuncVisitor mv) {
        var exit = mv.freshLabel();
        Function<FuncVisitor, Temp> test = v -> {
            loop.cond.accept(this, v);
            return loop.cond.val;
        };
        Consumer<FuncVisitor> body = v -> {
            loopExits.push(exit);
            loop.body.accept(this, v);
            loopExits.pop();
        };
        emitWhile(test, body, exit, mv);
    }

    @Override
    default void visitFor(Tree.For loop, FuncVisitor mv) {
        var exit = mv.freshLabel();
        loop.init.accept(this, mv);
        Function<FuncVisitor, Temp> test = v -> {
            loop.cond.accept(this, v);
            return loop.cond.val;
        };
        Consumer<FuncVisitor> body = v -> {
            loopExits.push(exit);
            loop.body.accept(this, v);
            loopExits.pop();
            loop.update.accept(this, v);
        };
        emitWhile(test, body, exit, mv);
    }

    @Override
    default void visitBreak(Tree.Break stmt, FuncVisitor mv) {
        mv.visitBranch(loopExits.peek());
    }

    @Override
    default void visitReturn(Tree.Return stmt, FuncVisitor mv) {
        if (stmt.expr.isEmpty()) {
            mv.visitReturn();
        } else {
            var expr = stmt.expr.get();
            expr.accept(this, mv);
            mv.visitReturn(expr.val);
        }
    }

    @Override
    default void visitPrint(Tree.Print stmt, FuncVisitor mv) {
        for (var expr : stmt.exprs) {
            expr.accept(this, mv);
            if (expr.type.eq(BuiltInType.INT)) {
                mv.visitIntrinsicCall(Intrinsic.PRINT_INT, expr.val);
            } else if (expr.type.eq(BuiltInType.BOOL)) {
                mv.visitIntrinsicCall(Intrinsic.PRINT_BOOL, expr.val);
            } else if (expr.type.eq(BuiltInType.STRING)) {
                mv.visitIntrinsicCall(Intrinsic.PRINT_STRING, expr.val);
            }
        }
    }

    // Expressions

    @Override
    default void visitIntLit(Tree.IntLit expr, FuncVisitor mv) {
        expr.val = mv.visitLoad(expr.value);
    }

    @Override
    default void visitBoolLit(Tree.BoolLit expr, FuncVisitor mv) {
        expr.val = mv.visitLoad(expr.value);
    }

    @Override
    default void visitStringLit(Tree.StringLit expr, FuncVisitor mv) {
        // Remember to unquote the string literal
        var unquoted = expr.value.substring(1, expr.value.length() - 1)
                .replaceAll("\\\\r", "\r")
                .replaceAll("\\\\n", "\n")
                .replaceAll("\\\\t", "\t")
                .replaceAll("\\\\\\\\", "\\")
                .replaceAll("\\\\\"", "\"");
        expr.val = mv.visitLoad(unquoted);
    }

    @Override
    default void visitNullLit(Tree.NullLit expr, FuncVisitor mv) {
        expr.val = mv.visitLoad(0);
    }

    @Override
    default void visitReadInt(Tree.ReadInt expr, FuncVisitor mv) {
        expr.val = mv.visitIntrinsicCall(Intrinsic.READ_INT, true);
    }

    @Override
    default void visitReadLine(Tree.ReadLine expr, FuncVisitor mv) {
        expr.val = mv.visitIntrinsicCall(Intrinsic.READ_LINE, true);
    }

    @Override
    default void visitUnary(Tree.Unary expr, FuncVisitor mv) {
        var op = switch (expr.op) {
            case NEG -> TacInstr.Unary.Op.NEG;
            case NOT -> TacInstr.Unary.Op.LNOT;
        };

        expr.operand.accept(this, mv);
        expr.val = mv.visitUnary(op, expr.operand.val);
    }

    @Override
    default void visitBinary(Tree.Binary expr, FuncVisitor mv) {
        if ((expr.op.equals(Tree.BinaryOp.EQ) || expr.op.equals(Tree.BinaryOp.NE)) &&
                expr.lhs.type.eq(BuiltInType.STRING)) { // string eq/ne
            expr.lhs.accept(this, mv);
            expr.rhs.accept(this, mv);
            expr.val = mv.visitIntrinsicCall(Intrinsic.STRING_EQUAL, true, expr.lhs.val, expr.rhs.val);
            if (expr.op.equals(Tree.BinaryOp.NE)) {
                mv.visitUnarySelf(TacInstr.Unary.Op.LNOT, expr.val);
            }
            return;
        }

        var op = switch (expr.op) {
            case ADD -> TacInstr.Binary.Op.ADD;
            case SUB -> TacInstr.Binary.Op.SUB;
            case MUL -> TacInstr.Binary.Op.MUL;
            case DIV -> TacInstr.Binary.Op.DIV;
            case MOD -> TacInstr.Binary.Op.MOD;
            case EQ -> TacInstr.Binary.Op.EQU;
            case NE -> TacInstr.Binary.Op.NEQ;
            case LT -> TacInstr.Binary.Op.LES;
            case LE -> TacInstr.Binary.Op.LEQ;
            case GT -> TacInstr.Binary.Op.GTR;
            case GE -> TacInstr.Binary.Op.GEQ;
            case AND -> TacInstr.Binary.Op.LAND;
            case OR -> TacInstr.Binary.Op.LOR;
        };
        expr.lhs.accept(this, mv);
        expr.rhs.accept(this, mv);

        if (op.equals(TacInstr.Binary.Op.MOD) || op.equals(TacInstr.Binary.Op.DIV)) {
            var zero = mv.visitLoad(0);
            var error = mv.visitBinary(TacInstr.Binary.Op.EQU, expr.rhs.val, zero);
            var handler = new Consumer<FuncVisitor>() {
                @Override
                public void accept(FuncVisitor v) {
                    v.visitPrint(RuntimeError.DIVISION_BY_ZERO);
                    v.visitIntrinsicCall(Intrinsic.HALT);
                }
            };
            emitIfThen(error, handler, mv);
        }
        expr.val = mv.visitBinary(op, expr.lhs.val, expr.rhs.val);
    }

    @Override
    default void visitVarSel(Tree.VarSel expr, FuncVisitor mv) {
        if (expr.symbol.isVarSymbol()) {
            VarSymbol varSymbol = (VarSymbol)expr.symbol;
            if (varSymbol.isMemberVar()) {
                if (FuncVisitor.lambdaBeingDefined.empty()) {
                    var object = expr.receiver.get();
                    object.accept(this, mv);
                    expr.val = mv.visitMemberAccess(object.val, varSymbol.getOwner().name, expr.name);
                } else {
                    expr.val = mv.visitMemberAccess(mv.getArgTemp(0), varSymbol.getOwner().name, expr.name);
                }

            } else { // local or param
                if (FuncVisitor.lambdaBeingDefined.empty()) {
                    expr.val = varSymbol.temp;
                    debug(expr.val, mv, "no lambda loading var @"+expr.pos);
                } else {
                    if (FuncVisitor.lambdaBeingDefined.peek().capturedVars.containsKey(varSymbol)) {
                        int index = FuncVisitor.lambdaBeingDefined.peek().capturedVars.get(varSymbol);
                        if (FuncVisitor.lambdaBeingDefined.peek().captureThis) {
                            index += 1;
                        }
                        expr.val = mv.getArgTemp(index);
                        debug(expr.val, mv, "in lambda loading cap var @"+expr.pos);
                    } else {
                        expr.val = varSymbol.temp;
                        debug(expr.val, mv, "in lambda loading var @"+expr.pos);
                    }

                }
            }
            debug(expr, mv, "VarSel var");
        }

        else if (expr.symbol.isMethodSymbol()){
            MethodSymbol methodSymbol = (MethodSymbol)expr.symbol;
            //TODO: method symbol
            if (methodSymbol.isStatic()) {
                var size = mv.visitLoad(12);
                var temp = mv.visitIntrinsicCall(Intrinsic.ALLOCATE, true, size);
                Temp funcLabel = mv.getStaticFuncLabelTemp(methodSymbol.owner.name, methodSymbol.name);
                var zero = mv.visitLoad(0);
                mv.visitStoreTo(temp, zero);
                mv.visitStoreTo(temp, 4, funcLabel);
                expr.val = temp;

            } else {
                Temp thisPointer = null;
                if (expr.receiver.isPresent()) {
                    var object = expr.receiver.get();
                    object.accept(this, mv);
                    thisPointer = object.val;
                } else {
                    thisPointer = mv.getArgTemp(0);
                }
                var size = mv.visitLoad(16);
                var temp = mv.visitIntrinsicCall(Intrinsic.ALLOCATE, true, size);
                Temp funcLabel = mv.getFuncLabelTemp(thisPointer, methodSymbol.owner.name, methodSymbol.name);
                var one = mv.visitLoad(1);
                mv.visitStoreTo(temp, one);
                mv.visitStoreTo(temp, 4, funcLabel);
                mv.visitStoreTo(temp, 8, thisPointer);
                expr.val = temp;
            }

            debug(expr, mv, "VarSel method");
        }
    }

    @Override
    default void visitIndexSel(Tree.IndexSel expr, FuncVisitor mv) {
        expr.array.accept(this, mv);
        expr.index.accept(this, mv);
        var addr = emitArrayElementAddress(expr.array.val, expr.index.val, mv);
        expr.val = mv.visitLoadFrom(addr);
    }

    @Override
    default void visitNewArray(Tree.NewArray expr, FuncVisitor mv) {
        expr.length.accept(this, mv);
        expr.val = emitArrayInit(expr.length.val, mv);
    }

    @Override
    default void visitNewClass(Tree.NewClass expr, FuncVisitor mv) {
        expr.val = mv.visitNewClass(expr.symbol.name);
    }

    @Override
    default void visitThis(Tree.This expr, FuncVisitor mv) {
        expr.val = mv.getArgTemp(0);
    }

    @Override
    default void visitCall(Tree.Call expr, FuncVisitor mv) {
        if (expr.isArrayLength) { // special case for array.length()
            var array = expr.receiver;
            array.accept(this, mv);
            expr.val = mv.visitLoadFrom(array.val, -4);
            return;
        }
        debug(mv, "Calling function @"+expr.pos);

        expr.args.forEach(arg -> arg.accept(this, mv));
        var temps = new ArrayList<Temp>();
        expr.args.forEach(arg -> temps.add(arg.val));

        var receiver = expr.receiver;
        receiver.accept(this, mv);

        // Step1: 取出第一个元素，0为静态，1为非静态，2为Lambda
        var flag = mv.visitLoadFrom(receiver.val);
        var one = mv.visitLoad(1);
        var two = mv.visitLoad(2);
        var nonStatic = mv.visitBinary(TacInstr.Binary.Op.EQU, flag, one);
        var isLambda = mv.visitBinary(TacInstr.Binary.Op.EQU, flag, two);

        // Step4: 取出第三个元素，并Param
        var notStaticBranch = new Consumer<FuncVisitor>() {
            @Override
            public void accept(FuncVisitor v) {
                var thisPointer = v.visitLoadFrom(receiver.val, 8);
                v.func.add(new TacInstr.Parm(thisPointer));
            }
        };

        // Step3: 如果T1=1，转Step4
        emitIfThen(nonStatic, notStaticBranch, mv);

        // Step6、7: 取出第三个元素，将Lambda的捕获变量依次声明为Param
        var isLambdaBranch = new Consumer<FuncVisitor>() {
            @Override
            public void accept(FuncVisitor v) {
                // 取出第三个元素T3
                var capturedSum = v.visitLoadFrom(receiver.val, 8);
                var twelve = v.visitLoad(12);
                var base = v.visitBinary(TacInstr.Binary.Op.ADD, receiver.val, twelve);
                var one = v.visitLoad(1);
                var four = v.visitLoad(4);
                // T4 = 0
                var id = v.visitLoad(0);

                Function<FuncVisitor, Temp> test = visitor -> visitor.visitBinary(TacInstr.Binary.Op.NEQ, id, capturedSum);

                // while(T4<T3) 取出下一个元素，param声明，T4=T4+1
                var body = new Consumer<FuncVisitor>() {
                    @Override
                    public void accept(FuncVisitor visitor) {
                        debug(base, visitor, "offset is:");
                        var nextVar = visitor.visitLoadFrom(base);
                        debug(id, visitor, "declaring in lambda@"+expr.pos);
                        debug(nextVar, visitor, "Val is"+expr.pos);
                        visitor.func.add(new TacInstr.Parm(nextVar));
                        debug(nextVar, visitor, "Declared");
                        debug(nextVar, mv, "Declared");
                        visitor.visitBinarySelf(TacInstr.Binary.Op.ADD, id, one);
                        visitor.visitBinarySelf(TacInstr.Binary.Op.ADD, base, four);
                    }
                };
                emitWhile(test, body, v.freshLabel(), v);
            }
        };

        // Step5: 如果T1=2，转Step6，否则转Step8
        emitIfThen(isLambda, isLambdaBranch, mv);

        // Step2、8、9: 声明参数并调用
        if (expr.type.isVoidType()) {
            mv.visitStaticFuncCall(receiver.val, temps);
        } else {
            expr.val = mv.visitStaticFuncCall(receiver.val, temps,true);
        }

        debug(expr, mv, "Finish call");
    }

    @Override
    default void visitLambda(Tree.Lambda expr, FuncVisitor mv) {
        //TODO: lambda
        debug(mv, "enter lambda@"+expr.pos);
        int lambdaSize = (4+expr.symbol.getCapturedVarsSum())*4;
        var size = mv.visitLoad(lambdaSize);
        var lambda = mv.visitIntrinsicCall(Intrinsic.ALLOCATE, true, size);

        var two = mv.visitLoad(2);
        var funcLabel = mv.getLambdaLabelTemp(expr.symbol.owner.name, expr.symbol.name);
        var capturedSum = mv.visitLoad(expr.symbol.getCapturedVarsSum());
        mv.visitStoreTo(lambda, 0, two);
        mv.visitStoreTo(lambda, 4, funcLabel);
        mv.visitStoreTo(lambda, 8, capturedSum);
        debug(mv, "store captured vars");

        var offset = 12;
        if (expr.symbol.captureThis) {
            debug(mv, "storing this");
            mv.visitStoreTo(lambda, offset, mv.getArgTemp(0));
            offset += 4;
        }

        for (var entry : expr.symbol.capturedVars.entrySet()) {
            debug(mv, "storing var "+entry.getKey().name);
            var location = offset + entry.getValue() * 4;
            debug(mv, "next var offset: "+location);
            var targetVar = entry.getKey();
            Temp t = null;
            if (FuncVisitor.lambdaBeingDefined.empty()) {
                mv.visitStoreTo(lambda, location, targetVar.temp);
                t = targetVar.temp;
            } else {
                if (FuncVisitor.lambdaBeingDefined.peek().capturedVars.containsKey(targetVar)) {
                    int index = FuncVisitor.lambdaBeingDefined.peek().capturedVars.get(targetVar);
                    if (FuncVisitor.lambdaBeingDefined.peek().captureThis) {
                        index += 1;
                    }
                    mv.visitStoreTo(lambda, location, mv.getArgTemp(index));
                    t = mv.getArgTemp(index);
                } else {
                    mv.visitStoreTo(lambda, location, targetVar.temp);
                    t = targetVar.temp;
                }
            }
            debug(t, mv, "stored var "+entry.getKey().name+", val is");
        }
        expr.val = lambda;

        debug(mv, "defining lambda@"+expr.pos);

        FuncVisitor.lambdaBeingDefined.push(expr.symbol);
        FuncVisitor.lambdaTempsBeingDefined.push(lambda);

        // 构造lambda函数体
        var entry = mv.ctx.getFuncLabel(expr.symbol.owner.name, expr.symbol.name);
        var numArgs = expr.argsList.size() + expr.symbol.getCapturedVarsSum();
        FuncVisitor funcVisitor = new FuncVisitor(entry, numArgs, mv.ctx);
        var i = expr.symbol.getCapturedVarsSum();
        debug(expr, mv, "Cap Sum: "+i+"; Args: "+expr.argsList.size());

        for (var param : expr.argsList) {
            param.accept(this, funcVisitor);
            param.symbol.temp = funcVisitor.getArgTemp(i);
            i++;
        }

        if (expr.isBlock) {
            expr.block.accept(this, funcVisitor);
        } else {
            expr.expression.accept(this, funcVisitor);
            funcVisitor.visitReturn(expr.expression.val);
        }

        funcVisitor.visitEnd();

        FuncVisitor.lambdaTempsBeingDefined.pop();
        FuncVisitor.lambdaBeingDefined.pop();

        debug(mv, "finish defining lambda@"+expr.pos);
    }

    @Override
    default void visitClassTest(Tree.ClassTest expr, FuncVisitor mv) {
        // Accelerate: when obj.type <: class.type, then the test must be successful!
        if (expr.obj.type.subtypeOf(expr.symbol.type)) {
            expr.val = mv.visitLoad(1);
            return;
        }

        expr.obj.accept(this, mv);
        expr.val = emitClassTest(expr.obj.val, expr.symbol.name, mv);
    }

    @Override
    default void visitClassCast(Tree.ClassCast expr, FuncVisitor mv) {
        expr.obj.accept(this, mv);
        expr.val = expr.obj.val;

        // Accelerate: when obj.type <: class.type, then the test must success!
        if (expr.obj.type.subtypeOf(expr.symbol.type)) {
            return;
        }
        var result = emitClassTest(expr.obj.val, expr.symbol.name, mv);

        /* Pseudo code:
         * <pre>
         *     if (result != 0) branch exit  // cast success
         *     print "Decaf runtime error: " // RuntimeError.CLASS_CAST_ERROR1
         *     vtbl1 = *obj                  // vtable of obj
         *     fromClass = *(vtbl1 + 4)      // name of obj's class
         *     print fromClass
         *     print " cannot be cast to "   // RuntimeError.CLASS_CAST_ERROR2
         *     vtbl2 = load vtbl of the target class
         *     toClass = *(vtbl2 + 4)        // name of target class
         *     print toClass
         *     print "\n"                    // RuntimeError.CLASS_CAST_ERROR3
         *     halt
         * exit:
         * </pre>
         */
        var exit = mv.freshLabel();
        mv.visitBranch(TacInstr.CondBranch.Op.BNEZ, result, exit);
        mv.visitPrint(RuntimeError.CLASS_CAST_ERROR1);
        var vtbl1 = mv.visitLoadFrom(expr.obj.val);
        var fromClass = mv.visitLoadFrom(vtbl1, 4);
        mv.visitIntrinsicCall(Intrinsic.PRINT_STRING, fromClass);
        mv.visitPrint(RuntimeError.CLASS_CAST_ERROR2);
        var vtbl2 = mv.visitLoadVTable(expr.symbol.name);
        var toClass = mv.visitLoadFrom(vtbl2, 4);
        mv.visitIntrinsicCall(Intrinsic.PRINT_STRING, toClass);
        mv.visitPrint(RuntimeError.CLASS_CAST_ERROR3);
        mv.visitIntrinsicCall(Intrinsic.HALT);
        mv.visitLabel(exit);
    }

    /**
     * Emit code for the following conditional statement:
     * <pre>
     *     if (cond) {
     *         action
     *     }
     * </pre>
     * <p>
     * Implementation in pseudo code:
     * <pre>
     *     if (cond == 0) branch skip;
     *     action
     * skip:
     * </pre>
     * <p>
     * Why {@link Consumer} for the true branch? Because the method visitor will append TAC code <em>in order</em>.
     * Since the instructions of the true branch go AFTER the conditional branch instruction, we must first append the
     * conditional branch, and then the true branch. So instead of appending the code first, which is wrong, we must
     * wrap the <em>process</em> which emits the actual code as a function {@link FuncVisitor} {@literal ->} void,
     * expressed by {@link Consumer} in Java. Same story for the helper methods below.
     *
     * @param cond   temp of condition
     * @param action code (to be generated) of the true branch
     * @param mv     current method visitor
     */
    private void emitIfThen(Temp cond, Consumer<FuncVisitor> action, FuncVisitor mv) {
        var skip = mv.freshLabel();
        mv.visitBranch(TacInstr.CondBranch.Op.BEQZ, cond, skip);
        action.accept(mv);
        mv.visitLabel(skip);
    }

    /**
     * Emit code for the following conditional statement:
     * <pre>
     *     if (cond) {
     *         trueBranch
     *     } else {
     *         falseBranch
     *     }
     * </pre>
     * <p>
     * Implementation in pseudo code:
     * <pre>
     *     if (cond == 0) branch skip
     *     trueBranch
     *     branch exit
     * skip:
     *     falseBranch
     * exit:
     * </pre>
     *
     * @param cond        temp of condition
     * @param trueBranch  code (to be generated) of the true branch
     * @param falseBranch code (to be generated) of the false branch
     * @param mv          current method visitor
     */
    private void emitIfThenElse(Temp cond, Consumer<FuncVisitor> trueBranch, Consumer<FuncVisitor> falseBranch,
                                FuncVisitor mv) {
        var skip = mv.freshLabel();
        var exit = mv.freshLabel();
        mv.visitBranch(TacInstr.CondBranch.Op.BEQZ, cond, skip);
        trueBranch.accept(mv);
        mv.visitBranch(exit);
        mv.visitLabel(skip);
        falseBranch.accept(mv);
        mv.visitLabel(exit);
    }

    /**
     * Emit code for the following loop:
     * <pre>
     *     while (cond) {
     *         block
     *     }
     * </pre>
     * <p>
     * Implementation in pseudo code:
     * <pre>
     * entry:
     *     cond = do test
     *     if (cond == 0) branch exit
     *     do block
     *     branch entry
     * exit:
     * </pre>
     *
     * @param test  code (to be generated) of the loop condition
     * @param block code (to be generated) of the loop body
     * @param exit  label of loop exit
     * @param mv    current method visitor
     */
    private void emitWhile(Function<FuncVisitor, Temp> test, Consumer<FuncVisitor> block,
                           Label exit, FuncVisitor mv) {
        var entry = mv.freshLabel();
        mv.visitLabel(entry);
        var cond = test.apply(mv);
        mv.visitBranch(TacInstr.CondBranch.Op.BEQZ, cond, exit);
        block.accept(mv);
        mv.visitBranch(entry);
        mv.visitLabel(exit);
    }

    /**
     * Emit code for initializing a new array.
     * <p>
     * In memory, an array of length {@code n} takes {@code (n + 1) * 4} bytes:
     * - the first 4 bytes: length
     * - the rest bytes: data
     * <p>
     * Pseudo code:
     * <pre>
     *     error = length {@literal <} 0
     *     if (error) {
     *         throw RuntimeError.NEGATIVE_ARR_SIZE
     *     }
     *
     *     units = length + 1
     *     size = units * 4
     *     a = ALLOCATE(size)
     *     *(a + 0) = length
     *     p = a + size
     *     p -= 4
     *     while (p != a) {
     *         *(p + 0) = 0
     *         p -= 4
     *     }
     *     ret = (a + 4)
     * </pre>
     *
     * @param length temp of array length
     * @param mv     current method visitor
     * @return a temp storing the address of the first element of the array
     */
    private Temp emitArrayInit(Temp length, FuncVisitor mv) {
        var zero = mv.visitLoad(0);
        var error = mv.visitBinary(TacInstr.Binary.Op.LES, length, zero);
        var handler = new Consumer<FuncVisitor>() {
            @Override
            public void accept(FuncVisitor v) {
                v.visitPrint(RuntimeError.NEGATIVE_ARR_SIZE);
                v.visitIntrinsicCall(Intrinsic.HALT);
            }
        };
        emitIfThen(error, handler, mv);

        var units = mv.visitBinary(TacInstr.Binary.Op.ADD, length, mv.visitLoad(1));
        var four = mv.visitLoad(4);
        var size = mv.visitBinary(TacInstr.Binary.Op.MUL, units, four);
        var a = mv.visitIntrinsicCall(Intrinsic.ALLOCATE, true, size);
        mv.visitStoreTo(a, length);
        var p = mv.visitBinary(TacInstr.Binary.Op.ADD, a, size);
        mv.visitBinarySelf(TacInstr.Binary.Op.SUB, p, four);
        Function<FuncVisitor, Temp> test = v -> v.visitBinary(TacInstr.Binary.Op.NEQ, p, a);
        var body = new Consumer<FuncVisitor>() {
            @Override
            public void accept(FuncVisitor v) {
                v.visitStoreTo(p, zero);
                v.visitBinarySelf(TacInstr.Binary.Op.SUB, p, four);
            }
        };
        emitWhile(test, body, mv.freshLabel(), mv);
        return mv.visitBinary(TacInstr.Binary.Op.ADD, a, four);
    }

    /**
     * Emit code for computing the address of an array element.
     * <p>
     * Pseudo code:
     * <pre>
     *     length = *(array - 4)
     *     error1 = index {@literal <} 0
     *     error2 = index {@literal >=} length
     *     error = error1 || error2
     *     if (error) {
     *         throw RuntimeError.ARRAY_INDEX_OUT_OF_BOUND
     *     }
     *
     *     offset = index * 4
     *     ret = array + offset
     * </pre>
     *
     * @param array temp of the array
     * @param index temp of the index
     * @return a temp storing the address of the element
     */
    private Temp emitArrayElementAddress(Temp array, Temp index, FuncVisitor mv) {
        var length = mv.visitLoadFrom(array, -4);
        var zero = mv.visitLoad(0);
        var error1 = mv.visitBinary(TacInstr.Binary.Op.LES, index, zero);
        var error2 = mv.visitBinary(TacInstr.Binary.Op.GEQ, index, length);
        var error = mv.visitBinary(TacInstr.Binary.Op.LOR, error1, error2);
        var handler = new Consumer<FuncVisitor>() {
            @Override
            public void accept(FuncVisitor v) {
                v.visitPrint(RuntimeError.ARRAY_INDEX_OUT_OF_BOUND);
                v.visitIntrinsicCall(Intrinsic.HALT);
            }
        };
        emitIfThen(error, handler, mv);

        var four = mv.visitLoad(4);
        var offset = mv.visitBinary(TacInstr.Binary.Op.MUL, index, four);
        return mv.visitBinary(TacInstr.Binary.Op.ADD, array, offset);
    }

    /**
     * Emit code for testing if an object is an instance of class.
     * <p>
     * Pseudo code:
     * <pre>
     *     target = LoadVtbl(clazz)
     *     t = *object
     * loop:
     *     ret = t == target
     *     if (ret != 0) goto exit
     *     t = *t
     *     if (t != 0) goto loop
     *     ret = 0 // t == null
     * exit:
     * </pre>
     *
     * @param object temp of the object/instance
     * @param clazz  name of the class
     * @return a temp storing the result (1 for true, and 0 for false)
     */
    private Temp emitClassTest(Temp object, String clazz, FuncVisitor mv) {
        var target = mv.visitLoadVTable(clazz);
        var t = mv.visitLoadFrom(object);

        var loop = mv.freshLabel();
        var exit = mv.freshLabel();
        mv.visitLabel(loop);
        var ret = mv.visitBinary(TacInstr.Binary.Op.EQU, t, target);
        mv.visitBranch(TacInstr.CondBranch.Op.BNEZ, ret, exit);
        mv.visitRaw(new TacInstr.Memory(TacInstr.Memory.Op.LOAD, t, t, 0));
        mv.visitBranch(TacInstr.CondBranch.Op.BNEZ, t, loop);
        var zero = mv.visitLoad(0);
        mv.visitAssign(ret, zero);
        mv.visitLabel(exit);

        return ret;
    }
}
