import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;

import java.util.Stack;

import static org.bytedeco.llvm.global.LLVM.*;
import static org.bytedeco.llvm.global.LLVM.LLVMFunctionType;

public class MyLLVMVisitor extends SysYParserBaseVisitor<LLVMValueRef> {

    LLVMModuleRef module = LLVMModuleCreateWithName("module");
    LLVMBuilderRef builder = LLVMCreateBuilder();
    LLVMTypeRef i32Type = LLVMInt32Type();
    LLVMTypeRef i1Type = LLVMInt1Type();
    LLVMTypeRef voidType = LLVMVoidType();
    LLVMValueRef zero = LLVMConstInt(i32Type, 0, /* signExtend */ 0);

    public LLVMModuleRef getModule() {
        return module;
    }

    public LLVMTypeRef getLLVMType(String typeName) {
        return typeName.equals("int") ? i32Type : voidType;
    }

    private LLVMGlobalScope globalScope = null;
    private int localScopeCounter = 0;
    private LLVMScope currentScope = null;
    private LLVMBasicBlockRef currentBlock = null;
    private LLVMValueRef currentFunction = null;


    private final Stack<LLVMBasicBlockRef> labelContinue = new Stack<>();
    private final Stack<LLVMBasicBlockRef> labelBreak = new Stack<>();

    private boolean labelReturn = false;


    @Override
    public LLVMValueRef visit(ParseTree tree) {
        return super.visit(tree);
    }

    @Override
    public LLVMValueRef visitTerminal(TerminalNode node) {
        return super.visitTerminal(node);
    }

    @Override
    public LLVMValueRef visitProgram(SysYParser.ProgramContext ctx) {
        globalScope = new LLVMGlobalScope(null);
        currentScope = globalScope;
        LLVMValueRef ret = super.visitProgram(ctx);
        currentScope = currentScope.getEnclosingScope();
        return ret;
    }

    @Override
    public LLVMValueRef visitCompUnit(SysYParser.CompUnitContext ctx) {
        return super.visitCompUnit(ctx);
    }

    @Override
    public LLVMValueRef visitBlock(SysYParser.BlockContext ctx) {
        LLVMLocalScope localScope = new LLVMLocalScope(currentScope);
        localScopeCounter++;
        String localScopeName = localScope.getName() + localScopeCounter;
        localScope.setName(localScopeName);
        currentScope = localScope;
        LLVMValueRef ret = super.visitBlock(ctx);
        currentScope = currentScope.getEnclosingScope();
        return ret;
    }

    @Override
    public LLVMValueRef visitFuncDef(SysYParser.FuncDefContext ctx) {
        // Generate arguments
        int paramCount = (ctx.funcFParams() != null) ? ctx.funcFParams().funcFParam().size() : 0;
        PointerPointer<Pointer> argumentTypes = new PointerPointer<>(paramCount);
        for ( int i = 0; i < paramCount; i++ ) {
            argumentTypes.put(i, getLLVMType(ctx.funcFParams().funcFParam(i).bType().getText()));
        }

        // Generate the function
        String functionName = ctx.funcName().getText();
        LLVMTypeRef functionType = LLVMFunctionType(
                getLLVMType(ctx.funcType().getText()),
                argumentTypes, paramCount, 0);
        LLVMValueRef function = LLVMAddFunction(module, functionName, functionType);
        LLVMBasicBlockRef entryBlock = LLVMAppendBasicBlock(function, functionName + "Entry");
        LLVMPositionBuilderAtEnd(builder, entryBlock);
        currentFunction = function;
        currentBlock = entryBlock;
        currentScope.define(functionName, new LLVMVar(function, functionType));
        currentScope = new LLVMLocalScope(currentScope);

        // Load params as local vars
        for ( int i = 0; i < paramCount; i++ ) {
            String paramName = ctx.funcFParams().funcFParam(i).IDENT().getText();
            LLVMTypeRef paramType = getLLVMType(ctx.funcFParams().funcFParam(i).bType().getText());
            LLVMValueRef paramValue = LLVMBuildAlloca(builder, paramType, paramName);
            currentScope.define(paramName, new LLVMVar(paramValue, paramType));
            LLVMBuildStore(builder, LLVMGetParam(function, i), paramValue);
        }

        this.labelReturn = false;
        super.visitFuncDef(ctx);
        if ( !labelReturn ) {
            LLVMBuildRet(builder,
                    getLLVMType(ctx.funcType().getText()).equals(voidType)
                            ? null : zero);
        }
        currentScope = currentScope.getEnclosingScope();
        this.labelReturn = false;
        return function;
    }

    @Override
    public LLVMValueRef visitStmt(SysYParser.StmtContext ctx) {
        if ( ctx.returnStmt() != null ) {
            this.labelReturn = true;
            return LLVMBuildRet(builder,
                    ctx.returnStmt() == null ? null : evalExp(ctx.returnStmt().exp()));
        }
        else if ( ctx.assignment() != null ) {
            return LLVMBuildStore(builder,
                    evalExp(ctx.assignment().exp()),
                    currentScope.resolve(ctx.assignment().lVal().getText()).getValue());
        }
        else if ( ctx.ifStmt() != null ) {
            LLVMBasicBlockRef
                    trueBlock = LLVMAppendBasicBlock(currentFunction, "true"),
                    falseBlock = LLVMAppendBasicBlock(currentFunction, "false"),
                    finallyBlock = LLVMAppendBasicBlock(currentFunction, "entry");
            LLVMBuildCondBr(builder,
                    LLVMBuildICmp(builder,
                            LLVMIntNE, zero, evalCond(ctx.ifStmt().cond()), "tmp_"),
                    trueBlock, falseBlock);

            // True Block
            LLVMPositionBuilderAtEnd(builder, trueBlock);
            this.visit(ctx.ifStmt().stmt(0));
            LLVMBuildBr(builder, finallyBlock);

            // False Block
            LLVMPositionBuilderAtEnd(builder, falseBlock);
            if ( ctx.ifStmt().ELSE() != null )
                this.visit(ctx.ifStmt().stmt(1));
            LLVMBuildBr(builder, finallyBlock);

            // Finally Block
            LLVMPositionBuilderAtEnd(builder, finallyBlock);
            this.labelReturn = false;
            return null;
        }
        else if ( ctx.whileStmt() != null ) {
            LLVMBasicBlockRef
                    condBlock = LLVMAppendBasicBlock(currentFunction, "whileCondition"),
                    bodyBlock = LLVMAppendBasicBlock(currentFunction, "whileBody"),
                    finallyBlock = LLVMAppendBasicBlock(currentFunction, "entry");

            // Cond Block
            LLVMBuildBr(builder, condBlock);
            LLVMPositionBuilderAtEnd(builder, condBlock);

            // Body Block
            LLVMBuildCondBr(builder,
                    LLVMBuildICmp(builder,
                            LLVMIntNE, zero,
                            evalCond(ctx.whileStmt().cond()), "tmp_"),
                    bodyBlock,
                    finallyBlock);
            LLVMPositionBuilderAtEnd(builder, bodyBlock);

            // Save Labels
            labelBreak.push(finallyBlock);
            labelContinue.push(condBlock);
            this.visitStmt(ctx.whileStmt().stmt());
            LLVMBuildBr(builder, condBlock);
            labelBreak.pop();
            labelContinue.pop();

            // Finally Block
            LLVMPositionBuilderAtEnd(builder, finallyBlock);
            return null;
        }
        else if ( ctx.breakStmt() != null ) {
            return LLVMBuildBr(builder, labelBreak.peek());
        }
        else if ( ctx.continueStmt() != null ) {
            return LLVMBuildBr(builder, labelContinue.peek());
        }
        return super.visitStmt(ctx);
    }

    @Override
    public LLVMValueRef visitVarDecl(SysYParser.VarDeclContext ctx) {
        String typeName = ctx.bType().getText();
        if ( currentScope == globalScope ) {
            for ( SysYParser.VarDefContext varDefContext : ctx.varDef() ) {
                LLVMValueRef globalVar = LLVMAddGlobal(module,
                        getLLVMType(typeName),
                        varDefContext.IDENT().getText());
                if ( varDefContext.ASSIGN() != null ) {
                    LLVMSetInitializer(globalVar, evalExp(varDefContext.initVal().exp()));
                }
                else {
                    LLVMSetInitializer(globalVar, zero);
                }
                currentScope.define(varDefContext.IDENT().getText(),
                        new LLVMVar(globalVar, getLLVMType(typeName)));
            }
        }
        else {
            for ( SysYParser.VarDefContext varDefContext : ctx.varDef() ) {
                LLVMValueRef localVar = LLVMBuildAlloca(builder,
                        getLLVMType(typeName),
                        varDefContext.IDENT().getText());
                if ( varDefContext.ASSIGN() != null ) {
                    LLVMBuildStore(builder,
                            evalExp(varDefContext.initVal().exp()),
                            localVar);
                }
                currentScope.define(varDefContext.IDENT().getText(),
                        new LLVMVar(localVar, getLLVMType(typeName)));
            }
        }
        return super.visitVarDecl(ctx);
    }

    @Override
    public LLVMValueRef visitConstDecl(SysYParser.ConstDeclContext ctx) {
        String typeName = ctx.bType().getText();
        if ( currentScope == globalScope ) {
            for ( SysYParser.ConstDefContext constDefContext : ctx.constDef() ) {
                LLVMValueRef globalVar = LLVMAddGlobal(module,
                        getLLVMType(typeName),
                        constDefContext.IDENT().getText());
                if ( constDefContext.ASSIGN() != null ) {
                    LLVMSetInitializer(globalVar, evalExp(constDefContext.constInitVal().constExp().exp()));
                }
                currentScope.define(constDefContext.IDENT().getText(),
                        new LLVMVar(globalVar, getLLVMType(typeName)));
            }
        }
        else {
            for ( SysYParser.ConstDefContext constDefContext : ctx.constDef() ) {
                LLVMValueRef localVar = LLVMBuildAlloca(builder,
                        getLLVMType(typeName),
                        constDefContext.IDENT().getText());
                LLVMBuildStore(builder,
                        evalExp(constDefContext.constInitVal().constExp().exp()),
                        localVar);
                currentScope.define(constDefContext.IDENT().getText(),
                        new LLVMVar(localVar, getLLVMType(typeName)));
            }
        }
        return super.visitConstDecl(ctx);
    }

    public Integer parseDecimal(String number) {
        return (number.startsWith("0x") || number.startsWith("0X"))
                ? Integer.parseInt(number.substring(2), 16)
                : ((number.startsWith("0") && number.length() != 1)
                    ? Integer.parseInt(number.substring(1), 8)
                    : Integer.parseInt(number, 10)
                    );
    }

    public LLVMValueRef evalExp(SysYParser.ExpContext ctx) {
        if ( ctx instanceof SysYParser.ParenExpContext ) {
            return evalExp(((SysYParser.ParenExpContext) ctx).exp());
        }
        if ( ctx instanceof SysYParser.NumExpContext ) {
            return LLVMConstInt(i32Type,
                    parseDecimal(((SysYParser.NumExpContext) ctx).number().getText()),
                    0);
        }
        if ( ctx instanceof SysYParser.LValExpContext ) {
            return LLVMBuildLoad(builder,
                    currentScope.resolve(((SysYParser.LValExpContext) ctx).lVal().getText()).getValue(),
                    ((SysYParser.LValExpContext) ctx).lVal().getText());
        }
        if ( ctx instanceof SysYParser.FuncCallExpContext ) {
            SysYParser.FuncCallExpContext ctx_ = ((SysYParser.FuncCallExpContext) ctx);
            String funcName = ctx_.funcName().IDENT().getText();
            int paramCount = ctx_.funcRParams() == null
                    ? 0 : ctx_.funcRParams().param().size();
            PointerPointer<Pointer> params = new PointerPointer<>(paramCount);
            for ( int i = 0; i < paramCount; i++ ) {
                params.put(i, this.evalExp(ctx_.funcRParams().param(i).exp()));
            }
            return LLVMBuildCall(builder,
                    currentScope.resolve(funcName).getValue(),
                    params, paramCount, funcName);
        }
        if ( ctx instanceof SysYParser.UnaryExpContext ) {
            if ( ((SysYParser.UnaryExpContext) ctx).unaryOp().PLUS() != null ) {
                return evalExp(((SysYParser.UnaryExpContext) ctx).exp());
            }
            else if ( ((SysYParser.UnaryExpContext) ctx).unaryOp().MINUS() != null ) {
                return LLVMBuildNeg(builder,
                        evalExp(((SysYParser.UnaryExpContext) ctx).exp()),
                        "tmp_");
            }
            else if ( ((SysYParser.UnaryExpContext) ctx).unaryOp().NOT() != null ) {
                LLVMValueRef tmp_ = evalExp(((SysYParser.UnaryExpContext) ctx).exp());
                tmp_ = LLVMBuildICmp(builder, LLVMIntNE, LLVMConstInt(i32Type, 0, 0), tmp_, "tmp_");
                tmp_ = LLVMBuildXor(builder, tmp_, LLVMConstInt(LLVMInt1Type(), 1, 0), "tmp_");
                tmp_ = LLVMBuildZExt(builder, tmp_, i32Type, "tmp_");
                return tmp_;
            }
        }
        if ( ctx instanceof SysYParser.AddExpContext ) {
            if ( ((SysYParser.AddExpContext) ctx).PLUS() != null ) {
                return LLVMBuildAdd(builder,
                        evalExp(((SysYParser.AddExpContext) ctx).exp(0)),
                        evalExp(((SysYParser.AddExpContext) ctx).exp(1)),
                        "tmp_");
            }
            if ( ((SysYParser.AddExpContext) ctx).MINUS() != null ) {
                return LLVMBuildSub(builder,
                        evalExp(((SysYParser.AddExpContext) ctx).exp(0)),
                        evalExp(((SysYParser.AddExpContext) ctx).exp(1)),
                        "tmp_");
            }
        }
        if ( ctx instanceof SysYParser.MulExpContext ) {
            if ( ((SysYParser.MulExpContext) ctx).MUL() != null ) {
                return LLVMBuildMul(builder,
                        evalExp(((SysYParser.MulExpContext) ctx).exp(0)),
                        evalExp(((SysYParser.MulExpContext) ctx).exp(1)),
                        "tmp_");
            }
            else if ( ((SysYParser.MulExpContext) ctx).DIV() != null ) {
                return LLVMBuildSDiv(builder,
                        evalExp(((SysYParser.MulExpContext) ctx).exp(0)),
                        evalExp(((SysYParser.MulExpContext) ctx).exp(1)),
                        "tmp_");
            }
            else if ( ((SysYParser.MulExpContext) ctx).MOD() != null ) {
                return LLVMBuildSRem(builder,
                        evalExp(((SysYParser.MulExpContext) ctx).exp(0)),
                        evalExp(((SysYParser.MulExpContext) ctx).exp(1)),
                        "tmp_");
            }
        }
        return null;
    }

    public LLVMValueRef evalCond(SysYParser.CondContext ctx) {
        if ( ctx instanceof SysYParser.CondExpContext ) {
            return evalExp(((SysYParser.CondExpContext) ctx).exp());
        }
        if ( ctx instanceof SysYParser.RelationExpContext ) {
            SysYParser.RelationExpContext ctx_ = ((SysYParser.RelationExpContext) ctx);
            return LLVMBuildZExt(builder,
                    LLVMBuildICmp(builder,
                            (ctx_.NEQ() != null) ? LLVMIntNE : LLVMIntEQ,
                            evalCond(ctx_.cond(0)), evalCond(ctx_.cond(1)), "tmp_"),
                    i32Type, "tmp_");
        }
        if ( ctx instanceof SysYParser.CompareExpContext ) {
            SysYParser.CompareExpContext ctx_ = ((SysYParser.CompareExpContext) ctx);
            return LLVMBuildZExt(builder,
                    LLVMBuildICmp(builder,
                            ((ctx_.LT() != null) ? LLVMIntSLT
                                    : ((ctx_.GT() != null) ? LLVMIntSGT
                                    : ((ctx_.LE() != null) ? LLVMIntSLE
                                    : LLVMIntSGE))),
                            evalCond(ctx_.cond(0)), evalCond(ctx_.cond(1)), "tmp_"),
                    i32Type, "tmp_");
        }
        if ( ctx instanceof SysYParser.AndExpContext ) {
            // Init
            SysYParser.AndExpContext ctx_ = ((SysYParser.AndExpContext) ctx);
            LLVMBasicBlockRef
                    leftBlock = LLVMAppendBasicBlock(currentFunction, "andLeft"),
                    rightBlock = LLVMAppendBasicBlock(currentFunction, "andRight"),
                    finallyBlock = LLVMAppendBasicBlock(currentFunction, "andFinally");
            LLVMValueRef result = LLVMBuildAlloca(builder, i32Type, "result");
            LLVMBuildBr(builder, leftBlock);

            // And Left
            LLVMPositionBuilderAtEnd(builder, leftBlock);
            LLVMValueRef leftVal = evalCond(ctx_.cond(0));
            LLVMBuildStore(builder, leftVal, result);
            LLVMBuildCondBr(builder,
                    LLVMBuildICmp(builder, LLVMIntNE, leftVal, zero, "tmp_"),
                    rightBlock, finallyBlock);

            // And Right
            LLVMPositionBuilderAtEnd(builder, rightBlock);
            LLVMValueRef rightVal = evalCond(ctx_.cond(1));
            LLVMBuildStore(builder, rightVal, result);
            LLVMBuildBr(builder, finallyBlock);

            // And Result
            LLVMPositionBuilderAtEnd(builder, finallyBlock);
            return LLVMBuildZExt(builder,
                    LLVMBuildLoad(builder, result, "tmp_"),
                    i32Type, "tmp_");
        }
        if ( ctx instanceof SysYParser.OrExpContext ) {
            // Init
            SysYParser.OrExpContext ctx_ = ((SysYParser.OrExpContext) ctx);
            LLVMBasicBlockRef
                    leftBlock = LLVMAppendBasicBlock(currentFunction, "orLeft"),
                    rightBlock = LLVMAppendBasicBlock(currentFunction, "orRight"),
                    finallyBlock = LLVMAppendBasicBlock(currentFunction, "orFinally");
            LLVMValueRef result = LLVMBuildAlloca(builder, i32Type, "result");
            LLVMBuildBr(builder, leftBlock);

            // Or Left
            LLVMPositionBuilderAtEnd(builder, leftBlock);
            LLVMValueRef leftVal = evalCond(ctx_.cond(0));
            LLVMBuildStore(builder, leftVal, result);
            LLVMBuildCondBr(builder,
                    LLVMBuildICmp(builder, LLVMIntNE, leftVal, zero, "tmp_"),
                    finallyBlock, rightBlock);

            // Or Right
            LLVMPositionBuilderAtEnd(builder, rightBlock);
            LLVMValueRef rightVal = evalCond(ctx_.cond(1));
            LLVMBuildStore(builder, rightVal, result);
            LLVMBuildBr(builder, finallyBlock);

            // Or Result
            LLVMPositionBuilderAtEnd(builder, finallyBlock);
            return LLVMBuildZExt(builder,
                    LLVMBuildLoad(builder, result, "tmp_"),
                    i32Type, "tmp_");
        }
        return zero;
    }

}
