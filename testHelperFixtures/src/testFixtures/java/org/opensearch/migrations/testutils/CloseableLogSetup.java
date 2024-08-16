package org.opensearch.migrations.testutils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.errorprone.annotations.MustBeClosed;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;

import lombok.Getter;
import org.slf4j.LoggerFactory;

/**
 * A utility class to capture log events from a specific logger, usually for testing purposes.  Implements
 * AutoCloseable and enforces it is closed w/ a compile-time check.  Recommened to use in a try-with-resources block.
 */
public class CloseableLogSetup implements AutoCloseable {

    /**
     * A thread-safe list to store captured log events.
     */
    @Getter
    List <String> logEvents = Collections.synchronizedList(new ArrayList<>());

    AbstractAppender testAppender;

    /**
     * The SLF4J logger instance used for logging within this utility.
     */
    @Getter
    org.slf4j.Logger testLogger;

    org.apache.logging.log4j.core.Logger internalLogger;

    String loggerName;

    /**
     * Constructs a new CloseableLogSetup for the specified logger name and sets up a custom Log4j appender to capture
     * all log events on the logger.
     *
     * @param loggerName the name of the logger to capture logs from
     */
    @MustBeClosed
    public CloseableLogSetup(String loggerName) {
        this.loggerName = loggerName;

        testAppender = new AbstractAppender(loggerName, null, null, false, null) {
            @Override
            public void append(LogEvent event) {
                logEvents.add(event.getMessage().getFormattedMessage());
            }
        };

        testAppender.start();

        internalLogger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName);
        testLogger = LoggerFactory.getLogger(loggerName);

        // Cast to core.Logger to access internal methods
        internalLogger.setLevel(Level.ALL);
        internalLogger.setAdditive(false);
        internalLogger.addAppender(testAppender);
    }

    /**
     * Closes the custom Log4j appender and releases resources.
     */
    @Override
    public void close() {
        internalLogger.removeAppender(testAppender);
        testAppender.stop();
    }
}
