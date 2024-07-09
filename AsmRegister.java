import java.util.HashMap;

public class AsmRegister {
    String name;
    AsmInterval interval;

    AsmRegister(String name) {
        this.name = name;
        this.interval = null;
    }

    public String getName() {
        return this.name;
    }

    @Override
    public String toString() {
        return "{" + this.name + ", " + this.interval + "}";
    }
}