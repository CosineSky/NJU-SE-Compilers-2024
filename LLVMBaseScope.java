import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.util.LinkedHashMap;
import java.util.Map;

public class LLVMBaseScope implements LLVMScope {
    private final LLVMScope enclosingScope;
    public final Map<String, LLVMVar> vars = new LinkedHashMap<>();
    private String name;

    public LLVMBaseScope(String name, LLVMScope enclosingScope) {
        this.name = name;
        this.enclosingScope = enclosingScope;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public LLVMScope getEnclosingScope() {
        return this.enclosingScope;
    }

    @Override
    public void define(String name, LLVMVar var) {
        vars.put(name, var);
    }

    @Override
    public LLVMVar resolve(String name) {
        LLVMVar var = vars.get(name);
        return (var != null) ?
                var :
                ((enclosingScope != null) ?
                        enclosingScope.resolve(name) :
                        null);
    }


}
