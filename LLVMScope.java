import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.util.Map;

public interface LLVMScope {
    public String getName();

    public void setName(String name);

    public LLVMScope getEnclosingScope();

    public void define(String name, LLVMVar var);

    public LLVMVar resolve(String name);

}

