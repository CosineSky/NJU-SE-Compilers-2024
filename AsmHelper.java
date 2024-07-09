import java.util.*;

public class AsmHelper {
    private final int STACK_SIZE;
    private int STACK_POINTER;

    private final HashSet<String> SCOPED = new HashSet<>();
    private final HashMap<String, Integer> GLOBAL = new HashMap<>();
    private final HashMap<String, Integer> STACK = new HashMap<>();
    private final HashMap<String, AsmInterval> LIFESPAN = new HashMap<>();
    private final List<AsmRegister> REGS = new ArrayList<>();
    private final StringBuffer BUFFER = new StringBuffer();


    AsmHelper(int stack_pointer) {
        this.STACK_SIZE = stack_pointer;
        this.STACK_POINTER = this.STACK_SIZE;
    }

    /*
        STACK TOOLS
     */
    public void pushStack(String name) {
        this.STACK_POINTER -= 4;
        this.STACK.put(name, this.STACK_POINTER);
//        System.out.println("[Stack] " + this.STACK.toString());
    }
    public int resolveStack(String name) {
//        System.out.println("[Stack] " + this.STACK.toString());
        if ( !this.STACK.containsKey(name) ) {
            System.err.println("Resolve failed!");
        }
        return this.STACK.getOrDefault(name, 0);
    }
    public boolean isInStack(String name) {
        return this.STACK.containsKey(name);
    }
    public int getStackPointer() {
        return this.STACK_POINTER;
    }

    /*
        GLOBAL TOOLS
     */
    public void defineGlobal(String name, int val) {
        this.GLOBAL.put(name, val);
    }
    public void defineScoped(String name) {
        this.SCOPED.add(name);
    }
    public boolean isGlobal(String name) {
//        System.out.println(name + ", Scoped >>> " + this.SCOPED);
        return !this.SCOPED.contains(name) && this.GLOBAL.containsKey(name);
    }
    public void shadowGlobal(String name) {
        this.GLOBAL.remove(name);
    }

    /*
        LIFESPAN TOOLS
     */
    public void appear(int opcode, int no, String var, int line, String type) {
//        System.err.println(String.format("%s %s %s %s %s", opcode, no, var, line, type));
        if ( !this.LIFESPAN.containsKey(var) ) {
            AsmInterval lifespan = new AsmInterval(var, line, line);
            this.LIFESPAN.put(var, lifespan);
        }
        else {
//            if ( !(Objects.equals(type, "def") || (opcode == 28 && no == 1)) ) {
                AsmInterval lifespan = this.LIFESPAN.get(var);
                lifespan.setEnd(line);
//            }
        }
    }
    public void allocVars() {
//        String[] Regs = {
//                "t2",
//        };
        String[] Regs = {
                "t2", "t3", "t4", "t5", "t6",
                "a0", "a1", "a2", "a3", "a4", "a5", "a6",
                "s0", "s1", "s2", "s3", "s4", "s5",
                "s6", "s7", "s8", "s9", "s10", "s11",
        };
        for ( String reg : Regs ) {
            this.REGS.add(new AsmRegister(reg));
        }
        allocRegs(new ArrayList<>(this.LIFESPAN.values()), this.REGS);
    }

    public void allocRegs(List<AsmInterval> intervals, List<AsmRegister> registers) {
        // Sort intervals by start time
        intervals.sort((i1, i2) -> Integer.compare(i1.getStart(), i2.getStart()));
        PriorityQueue<AsmRegister> active = new PriorityQueue<>
                ((r1, r2) -> Integer.compare(r1.interval.getEnd(), r2.interval.getEnd()));

        for ( AsmInterval interval : intervals ) {
            expireOldIntervals(interval, active);
            // There are available registers now.
//            System.out.println(">>>>>>>> " + active.size() + " " + registers.size());
            if ( active.size() < registers.size() ) {
                AsmRegister reg = getFreeRegister(registers, active);
//                System.out.println(active + " <- " + reg);
                reg.interval = interval;
                active.add(reg);
                interval.setRegBeforeSpill(reg.getName());
//                System.out.println("Variable " + interval.getVariable() + " assigned to " + reg.name + " at " + interval.getStart());
//                System.out.println(interval.getVariable() + ": ("  + interval.getStart() + " " + interval.getEnd() + ")\n");
            }
            else {
                AsmRegister toSpill = active.peek();
                for ( AsmRegister reg : active ) {
                    if ( reg.interval.getEnd() > toSpill.interval.getEnd() ) {
                        toSpill = reg;
                    }
                }
                toSpill.interval.setSpilled(interval.getStart());




//                System.out.println("Variable " + toSpill.interval.getVariable() + " spilled to memory at " + interval.getStart());
//                System.out.println(interval.getVariable() + ": ("  + interval.getStart() + " " + interval.getEnd() + ")");

                interval.setRegBeforeSpill(toSpill.interval.getRegBeforeSpill());
                toSpill.interval = interval; // ?
//                System.out.println("Variable " + interval.getVariable() + " assigned to " + toSpill.name + " at " + interval.getStart());
//                System.out.println(interval.getVariable() + ": ("  + interval.getStart() + " " + interval.getEnd() + ")\n");
            }
        }

    }
    public void expireOldIntervals(AsmInterval current, PriorityQueue<AsmRegister> active) {

        while ( !active.isEmpty() && active.peek().interval.getEnd() < current.getStart() ) {
            active.poll().interval = null;
        }
    }
    public AsmRegister getFreeRegister(List<AsmRegister> registers, PriorityQueue<AsmRegister> active) {
        for ( AsmRegister reg : registers ) {
            if ( !active.contains(reg) ) {
                return reg;
            }
        }
        // This shouldn't happen.
        return null;
    }

    public AsmInterval getVarSpilled(int line) {
//        System.out.println("---------- " + line);
        for ( AsmInterval interval : this.LIFESPAN.values() ) {
            if ( interval.getSpilled() == line ) {
                return interval;
            }
        }
        // No var is spilled at this line.
        return null;
    }
    public AsmInterval getVarLifespan(String var) {
        return this.LIFESPAN.getOrDefault(var, null);
    }
    public void printLifespan() {
//        System.err.println(this.LIFESPAN.values());
    }

    /*
        BUILD TOOLS
     */
    public void buildSeg(String seg_name) {
        BUFFER.append(String.format("  .%s\n", seg_name));
    }
    public void buildSeg(String seg_name, int param) {
        BUFFER.append(String.format("  .%s %s\n", seg_name, param));
    }
    public void buildSeg(String seg_name, String param) {
        BUFFER.append(String.format("  .%s %s\n", seg_name, param));
    }

    public void buildLabel(String label_name) {
        BUFFER.append(String.format("%s:\n", label_name));
    }
    public void buildPrologue() {
        BUFFER.append(String.format("  addi sp, sp, -%s\n", this.STACK_SIZE));
    }
    public void buildEpilogue() {
        BUFFER.append(String.format("  addi sp, sp, %s\n", this.STACK_SIZE))
                .append("  li a7, 93\n")
                .append("  ecall\n");
    }
    public void buildLI(String reg, int val) {
        BUFFER.append(String.format("  li %s, %s\n", reg, val));
    }
    public void buildSW(String reg, int stack_pointer, String base) {
        BUFFER.append(String.format("  sw %s, %s(%s)\n", reg, stack_pointer, base));
    }
    public void buildLW(String reg, int stack_pointer, String base) {
        BUFFER.append(String.format("  lw %s, %s(%s)\n", reg, stack_pointer, base));
    }
    public void buildLA(String reg, String global) {
        BUFFER.append(String.format("  la %s, %s\n", reg, global));
    }
    public void buildMV(String dest, String src, String... comments) {
        BUFFER.append(String.format("  mv %s, %s %s\n", dest, src,
                comments.length > 0 ? comments[0] : ""));
    }
    public void buildADD(String dest, String src1, String src2) {
        BUFFER.append(String.format("  add %s, %s, %s\n", dest, src1, src2));
    }
    public void buildSUB(String dest, String src1, String src2) {
        BUFFER.append(String.format("  sub %s, %s, %s\n", dest, src1, src2));
    }
    public void buildMUL(String dest, String src1, String src2) {
        BUFFER.append(String.format("  mul %s, %s, %s\n", dest, src1, src2));
    }
    public void buildDIV(String dest, String src1, String src2) {
        BUFFER.append(String.format("  div %s, %s, %s\n", dest, src1, src2));
    }
    public void buildREM(String dest, String src1, String src2) {
        BUFFER.append(String.format("  rem %s, %s, %s\n", dest, src1, src2));
    }

    /*
        BUFFER TOOLS
     */
    public StringBuffer getBuffer() {
        return this.BUFFER;
    }

}
