package example;

import java.util.List;
import java.util.Map;

class Sample {
    private int second;
    private int first;

    static {
        initialized++;
    }

    private static int initialized = 4;

    int alpha(int input) {
        return input + initialized;
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
