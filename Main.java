import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.*;
import static org.bytedeco.llvm.global.LLVM.*;

import java.io.*;
import java.nio.file.Path;
import java.util.List;



public class Main
{
//    static int f() {
//        if ( true ) {
//            return 1;
//        }
//        else {
//            return 2;
//        }
//    }

    public static final BytePointer error = new BytePointer();

    public static void main(String[] args) {
        try {
            String source = args[0],
                    ir_dest = "src/_ir.txt",
                    code_dest = args[1];
//            String source = "src/_in.txt",
//                    ir_dest = "src/_ir.txt",
//                    code_dest = "src/_out.txt";
            CharStream input = CharStreams.fromFileName(source);
            SysYLexer sysYLexer = new SysYLexer(input);
            CommonTokenStream tokens = new CommonTokenStream(sysYLexer);
            SysYParser sysYParser = new SysYParser(tokens);

            /*
             *   LAB 1 - Error Listening
             */
            MyErrorListener myErrorListener = new MyErrorListener();
            sysYParser.removeErrorListeners();
            sysYParser.addErrorListener(myErrorListener);
            ParseTree tree = sysYParser.program();
            if ( !myErrorListener.errorList.isEmpty() ) {
                myErrorListener.printLexerErrorInformation();
                System.exit(0);
            }


            /*
             *   LAB 2 & 3 - Syntax Analyzing & Type Checking
             */
//            MyVisitor visitor = new MyVisitor();
//            visitor.visit(tree);
//            if ( visitor.isCorrectProgram() ) {
//                System.err.println("No semantic errors in the program!");
//            }


            /*
             *   LAB 4 & 5 - IR Generation
             */
            LLVMInitializeCore(LLVMGetGlobalPassRegistry());
            LLVMLinkInMCJIT();
            LLVMInitializeNativeAsmPrinter();
            LLVMInitializeNativeAsmParser();
            LLVMInitializeNativeTarget();

            MyLLVMVisitor visitor = new MyLLVMVisitor();
            visitor.visit(tree);

            LLVMModuleRef MODULE = visitor.getModule();
            LLVMPrintModuleToFile(MODULE, ir_dest, error);
            if (LLVMPrintModuleToFile(MODULE, ir_dest, error) != 0) {
                LLVMDisposeMessage(error);
            }


            /*
             *   LAB 6 - Code Generation
             */
            AsmBuilder asmBuilder = new AsmBuilder(MODULE);
            asmBuilder.build();
            asmBuilder.outputBuffer(code_dest);

        }
        catch (IOException ignored) {}



    }

}