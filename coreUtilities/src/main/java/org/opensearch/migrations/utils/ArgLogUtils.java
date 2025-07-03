package org.opensearch.migrations.utils;

import java.util.ArrayList;
import java.util.List;

public class ArgLogUtils {

    private ArgLogUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static final String CENSORED_VALUE = "******";

    public static List<String> getRedactedArgs(String[] args) {
        List<String> redactedArgs = new ArrayList<>();
        boolean shouldCensorNext = false;

        for (String arg : args) {
            if (shouldCensorNext) {
                redactedArgs.add(CENSORED_VALUE);
                shouldCensorNext = false;
            } else if (arg.equals("--target-password") || arg.equals("--targetPassword")) {
                redactedArgs.add(arg);
                shouldCensorNext = true;
            } else {
                redactedArgs.add(arg);
            }
        }

        return redactedArgs;
    }
}
