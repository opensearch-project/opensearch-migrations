package example;

import java.util.List;
import java.util.Map;

class Sample {
    private int second;
    private int first;
    private static int initialized = 4;

    static {
        initialized++;
    }

    int alpha(int input) {
        return input + initialized + 1;
    }

    void beta() {
        first = second;
    }

    static class Nested {
        String value() {
            return "nested";
        }
    }
}
