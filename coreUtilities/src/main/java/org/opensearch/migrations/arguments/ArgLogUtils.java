package org.opensearch.migrations.arguments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ArgLogUtils {

    private ArgLogUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static final String CENSORED_VALUE = "******";

    public static List<String> getRedactedArgs(String[] args, Collection<String> censoredArgs) {
        List<String> redactedArgs = new ArrayList<>();
        boolean shouldCensorNext = false;

        for (String arg : args) {
            if (shouldCensorNext) {
                redactedArgs.add(CENSORED_VALUE);
                shouldCensorNext = false;
            } else if (censoredArgs.contains(arg)) {
                redactedArgs.add(arg);
                shouldCensorNext = true;
            } else {
                redactedArgs.add(arg);
            }
        }

        return redactedArgs;
    }
}
