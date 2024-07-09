import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import static org.bytedeco.llvm.global.LLVM.*;
import static org.bytedeco.llvm.global.LLVM.LLVMGetValueName;

public class AsmBuilder {
    private AsmHelper asmHelper;

    private LLVMModuleRef MODULE;

    AsmBuilder(LLVMModuleRef module) {
        this.asmHelper = new AsmHelper(1024);
        this.MODULE = module;
    }

    public void dumpBuffer() {
        System.out.println(asmHelper.getBuffer().toString());
    }
    public void outputBuffer(String path) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(asmHelper.getBuffer().toString());
        } catch (IOException ignored) {}
    }

    void build() {
        for (LLVMValueRef globalVar = LLVMGetFirstGlobal(MODULE);
             globalVar != null; globalVar = LLVMGetNextGlobal(globalVar) ) {
            String name = LLVMGetValueName(globalVar).getString();
            int val = (int) LLVMConstIntGetZExtValue(LLVMGetInitializer(globalVar));
            asmHelper.defineGlobal(name, val);
            asmHelper.buildSeg("data");
            asmHelper.buildLabel(name);
            asmHelper.buildSeg("word", val);
        }

        asmHelper.buildSeg("text");
        asmHelper.buildSeg("globl", "main");

        for ( LLVMValueRef function = LLVMGetFirstFunction(MODULE);
              function != null; function = LLVMGetNextFunction(function) ) {
            asmHelper.buildLabel(LLVMGetValueName(function).getString());
            asmHelper.buildPrologue();

            for ( LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(function);
                 block != null; block = LLVMGetNextBasicBlock(block) ) {
                asmHelper.buildLabel(LLVMGetBasicBlockName(block).getString());

                for ( int round = 1; round <= 2; round++ ) {
                    int instruction_line = 0;
                    if ( round == 2 ) {
                        asmHelper.allocVars();
                    }
                    for ( LLVMValueRef inst = LLVMGetFirstInstruction(block);
                          inst != null; inst = LLVMGetNextInstruction(inst) ) {
                        int opcode = LLVMGetInstructionOpcode(inst);
                        ArrayList<LLVMValueRef> params = new ArrayList<>();
                        for ( int i = 0; i < LLVMGetNumOperands(inst); i++ ) {
                            params.add(LLVMGetOperand(inst, i));
                        }
                        String lVal = LLVMGetValueName(inst).getString();
                        switch ( round ) {
                            case 1:
                                getLifespan(opcode, params, lVal, instruction_line);
                                break;
                            case 2:
                                genInst(opcode, params, lVal, instruction_line);
                                break;
                            default:
                                break;
                        }
                        instruction_line++;
                    }
                }

            }

            asmHelper.buildEpilogue();
        }

        asmHelper.printLifespan();
    }

    void getLifespan(int opcode, ArrayList<LLVMValueRef> params, String lVal, int line) {
        if ( !lVal.isEmpty() ) {
            asmHelper.appear(opcode, 0, lVal, line, "def");
        }
        for ( int i = 0; i < params.size(); i++ ) {
            String var = LLVMGetValueName(params.get(i)).getString();
            if ( LLVMIsAGlobalVariable(params.get(i)) != null ) {
                continue;
            }
            if ( !var.isEmpty() ) {
                asmHelper.appear(opcode, i, var, line, "use");
            }
        }
    }

    void genInst(int opcode, ArrayList<LLVMValueRef> params, String lVal, int line) {
//         check spilling
//        System.out.println("[L" + line + "]");
        AsmInterval varToSpill = asmHelper.getVarSpilled(line);
        if ( varToSpill != null ) {
//            System.err.println("SPILLING");
            asmHelper.pushStack(varToSpill.getVariable());
            asmHelper.buildMV("t0", varToSpill.getRegBeforeSpill());
            asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
        }

        // generate risc-v
        switch ( opcode ) {
            case 1:
                // ret
                String retVar = LLVMGetValueName(params.get(0)).getString();
                AsmInterval retVarLifespan = asmHelper.getVarLifespan(retVar);
                if ( retVar.isEmpty() ) {
                    asmHelper.buildLI("a0", (int) LLVMConstIntGetZExtValue(params.get(0)));
                }
                else {
                    if ( line < retVarLifespan.getSpilled() ) {
                        asmHelper.buildMV("a0", retVarLifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("a0", asmHelper.resolveStack(retVar), "sp");
                    }
                }
                break;
            case 8:
                // add
                String add1 = LLVMGetValueName(params.get(0)).getString();
                String add2 = LLVMGetValueName(params.get(1)).getString();
                AsmInterval add1Lifespan = asmHelper.getVarLifespan(add1);
                AsmInterval add2Lifespan = asmHelper.getVarLifespan(add2);
                AsmInterval addResultLifespan = asmHelper.getVarLifespan(lVal);

                if ( add1.isEmpty() ) {
                    asmHelper.buildLI("t0", (int) LLVMConstIntGetZExtValue(params.get(0)));
                }
                else {
                    if ( line < add1Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t0", add1Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t0", asmHelper.resolveStack(add1), "sp");
                    }
                }
                if ( add2.isEmpty() ) {
                    asmHelper.buildLI("t1", (int) LLVMConstIntGetZExtValue(params.get(1)));
                }
                else {
                    if ( line < add2Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t1", add2Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t1", asmHelper.resolveStack(add2), "sp");
                    }
                }
                asmHelper.buildADD("t0", "t0", "t1");
                if ( line < addResultLifespan.getSpilled() ) {
                    asmHelper.buildMV(addResultLifespan.getRegBeforeSpill(), "t0");
                }
                else {
                    asmHelper.pushStack(lVal);
                    asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                }
                break;

            case 10:
                // sub
                String sub1 = LLVMGetValueName(params.get(0)).getString();
                String sub2 = LLVMGetValueName(params.get(1)).getString();
                AsmInterval sub1Lifespan = asmHelper.getVarLifespan(sub1);
                AsmInterval sub2Lifespan = asmHelper.getVarLifespan(sub2);
                AsmInterval subResultLifespan = asmHelper.getVarLifespan(lVal);

                if ( sub1.isEmpty() ) {
                    asmHelper.buildLI("t0", (int) LLVMConstIntGetZExtValue(params.get(0)));
                }
                else {
                    if ( line < sub1Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t0", sub1Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t0", asmHelper.resolveStack(sub1), "sp");
                    }
                }
                if ( sub2.isEmpty() ) {
                    asmHelper.buildLI("t1", (int) LLVMConstIntGetZExtValue(params.get(1)));
                }
                else {
                    if ( line < sub2Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t1", sub2Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t1", asmHelper.resolveStack(sub2), "sp");
                    }
                }
                asmHelper.buildSUB("t0", "t0", "t1");
                if ( line < subResultLifespan.getSpilled() ) {
                    asmHelper.buildMV(subResultLifespan.getRegBeforeSpill(), "t0");
                }
                else {
                    asmHelper.pushStack(lVal);
                    asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                }
                break;

            case 12:
                // mul
                String mul1 = LLVMGetValueName(params.get(0)).getString();
                String mul2 = LLVMGetValueName(params.get(1)).getString();
                AsmInterval mul1Lifespan = asmHelper.getVarLifespan(mul1);
                AsmInterval mul2Lifespan = asmHelper.getVarLifespan(mul2);
                AsmInterval mulResultLifespan = asmHelper.getVarLifespan(lVal);

                if ( mul1.isEmpty() ) {
                    asmHelper.buildLI("t0", (int) LLVMConstIntGetZExtValue(params.get(0)));
                }
                else {
                    if ( line < mul1Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t0", mul1Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t0", asmHelper.resolveStack(mul1), "sp");
                    }
                }
                if ( mul2.isEmpty() ) {
                    asmHelper.buildLI("t1", (int) LLVMConstIntGetZExtValue(params.get(1)));
                }
                else {
                    if ( line < mul2Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t1", mul2Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t1", asmHelper.resolveStack(mul2), "sp");
                    }
                }
                asmHelper.buildMUL("t0", "t0", "t1");
                if ( line < mulResultLifespan.getSpilled() ) {
                    asmHelper.buildMV(mulResultLifespan.getRegBeforeSpill(), "t0");
                }
                else {
                    asmHelper.pushStack(lVal);
                    asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                }
                break;

            case 15:
                // div
                String div1 = LLVMGetValueName(params.get(0)).getString();
                String div2 = LLVMGetValueName(params.get(1)).getString();
                AsmInterval div1Lifespan = asmHelper.getVarLifespan(div1);
                AsmInterval div2Lifespan = asmHelper.getVarLifespan(div2);
                AsmInterval divResultLifespan = asmHelper.getVarLifespan(lVal);

                if ( div1.isEmpty() ) {
                    asmHelper.buildLI("t0", (int) LLVMConstIntGetZExtValue(params.get(0)));
                }
                else {
                    if ( line < div1Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t0", div1Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t0", asmHelper.resolveStack(div1), "sp");
                    }
                }
                if ( div2.isEmpty() ) {
                    asmHelper.buildLI("t1", (int) LLVMConstIntGetZExtValue(params.get(1)));
                }
                else {
                    if ( line < div2Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t1", div2Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t1", asmHelper.resolveStack(div2), "sp");
                    }
                }
                asmHelper.buildDIV("t0", "t0", "t1");
                if ( line < divResultLifespan.getSpilled() ) {
                    asmHelper.buildMV(divResultLifespan.getRegBeforeSpill(), "t0");
                }
                else {
                    asmHelper.pushStack(lVal);
                    asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                }
                break;

            case 18:
                // rem
                String rem1 = LLVMGetValueName(params.get(0)).getString();
                String rem2 = LLVMGetValueName(params.get(1)).getString();
                AsmInterval rem1Lifespan = asmHelper.getVarLifespan(rem1);
                AsmInterval rem2Lifespan = asmHelper.getVarLifespan(rem2);
                AsmInterval remResultLifespan = asmHelper.getVarLifespan(lVal);

                if ( rem1.isEmpty() ) {
                    asmHelper.buildLI("t0", (int) LLVMConstIntGetZExtValue(params.get(0)));
                }
                else {
                    if ( line < rem1Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t0", rem1Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t0", asmHelper.resolveStack(rem1), "sp");
                    }
                }
                if ( rem2.isEmpty() ) {
                    asmHelper.buildLI("t1", (int) LLVMConstIntGetZExtValue(params.get(1)));
                }
                else {
                    if ( line < rem2Lifespan.getSpilled() ) {
                        asmHelper.buildMV("t1", rem2Lifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t1", asmHelper.resolveStack(rem2), "sp");
                    }
                }
                asmHelper.buildREM("t0", "t0", "t1");
                if ( line < remResultLifespan.getSpilled() ) {
                    asmHelper.buildMV(remResultLifespan.getRegBeforeSpill(), "t0");
                }
                else {
                    asmHelper.pushStack(lVal);
                    asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                }
                break;

            case 26:
                // alloc
                asmHelper.defineScoped(lVal);
                asmHelper.shadowGlobal(lVal);
                break;
            case 27:
                // load
                // is global var
                String loadVar = LLVMGetValueName(params.get(0)).getString();
                AsmInterval loadVarLifespan = asmHelper.getVarLifespan(loadVar);
                AsmInterval lValLifespan = asmHelper.getVarLifespan(lVal);
                if ( asmHelper.isGlobal(loadVar) ) {
//                    System.out.println(loadVar + " is global");
                    asmHelper.buildLA("t0", loadVar);
                    asmHelper.buildLW("t0", 0, "t0");
                    if ( line < lValLifespan.getSpilled() ) {
                        asmHelper.buildMV(lValLifespan.getRegBeforeSpill(), "t0");
                    }
                    else {
                        asmHelper.pushStack(lVal);
                        asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                    }
                }
                else {
                    if ( line < loadVarLifespan.getSpilled() ) {
                        asmHelper.buildMV("t0", loadVarLifespan.getRegBeforeSpill());
                    }
                    else {
                        asmHelper.buildLW("t0", asmHelper.resolveStack(loadVar), "sp");
                    }
                    if ( line < lValLifespan.getSpilled() ) {
                        asmHelper.buildMV(lValLifespan.getRegBeforeSpill(), "t0");
                    }
                    else {
                        asmHelper.pushStack(lVal);
                        asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                    }
                }
                break;
            case 28:
                // store
                String storeSrc = LLVMGetValueName(params.get(0)).getString();
                String storeDest = LLVMGetValueName(params.get(1)).getString();
                AsmInterval storeSrcLifespan = asmHelper.getVarLifespan(storeSrc);
                AsmInterval storeDestLifespan = asmHelper.getVarLifespan(storeDest);


                // TEST
//                if ( line >= storeDestLifespan.getEnd() ) {
//                    break;
//                }


                int val = (int) LLVMConstIntGetZExtValue(params.get(0));
                boolean isConstant = storeSrc.isEmpty();
                if ( asmHelper.isGlobal(storeDest) ) {
                    if ( isConstant ) {
                        asmHelper.buildLI("t0", val);
                        asmHelper.buildLA("t1", storeDest);
                        asmHelper.buildSW("t0", 0, "t1");
                    }
                    else {
                        if ( line < storeSrcLifespan.getSpilled() ) {
                            asmHelper.buildMV(storeSrcLifespan.getRegBeforeSpill(), "t0");
                        }
                        else {
                            asmHelper.buildLW("t0", asmHelper.resolveStack(storeSrc), "sp");
                        }
                        asmHelper.buildLA("t1", storeDest);
                        asmHelper.buildSW("t0", 0, "t1");
                    }

                }
                else {
                    if ( isConstant ) {
                        asmHelper.buildLI("t0", val);
                        if ( line < storeDestLifespan.getSpilled() ) {
                            asmHelper.buildMV(storeDestLifespan.getRegBeforeSpill(), "t0");
                        }
                        else {
                            asmHelper.pushStack(storeDest);
                            asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                        }
                    }
                    else {
                        if ( line < storeSrcLifespan.getSpilled() ) {
                            asmHelper.buildMV("t0", storeSrcLifespan.getRegBeforeSpill());
                        }
                        else {
                            asmHelper.buildLW("t0", asmHelper.resolveStack(storeSrc), "sp");
                        }
                        if ( line < storeDestLifespan.getSpilled() ) {
                            asmHelper.buildMV(storeDestLifespan.getRegBeforeSpill(), "t0");
                        }
                        else {
                            asmHelper.pushStack(storeDest);
                            asmHelper.buildSW("t0", asmHelper.getStackPointer(), "sp");
                        }

                    }

                }
                break;
            default:
                break;
        }
    }
}
