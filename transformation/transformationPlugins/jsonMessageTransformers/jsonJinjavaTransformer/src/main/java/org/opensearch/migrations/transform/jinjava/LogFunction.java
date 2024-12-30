package org.opensearch.migrations.transform.jinjava;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;

@Slf4j
public class LogFunction {

    /**
     * Called from templates through the registration in the JinjavaTransformer class
     */
    public static Object logValueAndReturn(String levelStr, Object valueToLog, Object valueToReturn) {
        Level level;
        try {
            level = Level.valueOf(levelStr);
        } catch (IllegalArgumentException e) {
            log.atError().setMessage("Could not parse the level as it was passed in, so using ERROR.  Level={}")
                .addArgument(levelStr).log();
            level = Level.ERROR;
        }
        log.atLevel(level).setMessage("{}").addArgument(valueToLog).log();
        return valueToReturn;
    }

    public static void logValue(String level, Object valueToLog) {
        logValueAndReturn(level, valueToLog, null);
    }
}
