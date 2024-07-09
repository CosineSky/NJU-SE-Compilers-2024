import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

public class LLVMVar {
    private LLVMValueRef value;
    private LLVMTypeRef type;

    LLVMVar(LLVMValueRef value, LLVMTypeRef type) {
        this.value = value;
        this.type = type;
    }

    public LLVMValueRef getValue() {
        return this.value;
    }
    public LLVMTypeRef getType() {
        return this.type;
    }

    public void setValue(LLVMValueRef value) {
        this.value = value;
    }
    public void setType(LLVMTypeRef type) {
        this.type = type;
    }

}
