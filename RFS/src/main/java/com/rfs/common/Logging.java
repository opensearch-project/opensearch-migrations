package com.rfs.common;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.ParameterException;

public class Logging {
    private Logging() {}

    public static void setLevel(Level level) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME).setLevel(level);
        ctx.updateLoggers();
    }

    public static class ArgsConverter implements IStringConverter<Level> {
        @Override
        public Level convert(String value) {
            switch (value) {
                case "debug":
                    return Level.DEBUG;
                case "info":
                    return Level.INFO;
                case "warn":
                    return Level.WARN;
                case "error":
                    return Level.ERROR;
                default:
                    throw new ParameterException("Invalid source version: " + value);
            }
        }
    }
}
