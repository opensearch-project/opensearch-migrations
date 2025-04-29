package org.opensearch.migrations.bulkload.common;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Utility class for capturing log4j logs to validate in testing
 */
public class LoggingListAppender extends AbstractAppender {
    private final List<LogEvent> events = new ArrayList<>();

    public LoggingListAppender(String name) {
        super(name, null, null, true, null);
    }

    @Override
    public void append(LogEvent event) {
        events.add(event.toImmutable());
    }
    public List<LogEvent> getEvents() {
        return events;
    }

    public void removeFromLogger(String className) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig(className);
        loggerConfig.removeAppender(this.getName());
        ctx.updateLoggers();
    }

    public static LoggingListAppender createAndRegister(String className) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        LoggingListAppender appender = new LoggingListAppender("TestLoggingListAppender-" + className);
        appender.start();

        LoggerConfig loggerConfig = config.getLoggerConfig(className);
        loggerConfig.addAppender(appender, null, null);

        ctx.updateLoggers();

        return appender;
    }
}
