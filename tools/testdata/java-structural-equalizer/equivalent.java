package example;

import java.util.Map;
import java.util.List;

// Declaration order and comments are intentionally different.
class Sample {
    private int first;
    private int second;
    private static int initialized = 4;

    static {
        initialized++;
    }

    static class Nested {
        String value() {
            return "nested";
        }
    }

    void beta() {
        first = second;
    }

    int alpha(int input) {
        return input + initialized;
    }
}
