public class AsmInterval {
    private String variable;
    private String regBeforeSpill;
    private int start;
    private int end;
    private int spilled;

    AsmInterval(String variable, int start, int end) {
        this.variable = variable;
        this.start = start;
        this.end = end;
        this.spilled = 1 << 29;
        this.regBeforeSpill = null;
    }

    public void setEnd(int end) {
        this.end = end;
    }
    public int getEnd() {
        return this.end;
    }

    public void setStart(int start) {
        this.start = start;
    }
    public int getStart() {
        return this.start;
    }

    public void setVariable(String variable) {
        this.variable = variable;
    }
    public String getVariable() {
        return this.variable;
    }

    public void setSpilled(int spilled) {
        this.spilled = spilled;
    }
    public int getSpilled() {
        return this.spilled;
    }

    public void setRegBeforeSpill(String regBeforeSpill) {
        this.regBeforeSpill = regBeforeSpill;
    }
    public String getRegBeforeSpill() {
        return this.regBeforeSpill;
    }

    @Override
    public String toString() {
        return "["
                + this.variable
                + ", ("
                + this.start + ", " + this.end
                + "), @"
                + this.regBeforeSpill
                + ", "
                + this.spilled
                + "]";
    }

}