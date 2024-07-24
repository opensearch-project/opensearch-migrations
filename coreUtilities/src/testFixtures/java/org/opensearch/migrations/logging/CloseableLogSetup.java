package org.opensearch.migrations.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.slf4j.LoggerFactory;

import lombok.Getter;

public class CloseableLogSetup implements AutoCloseable {
    @Getter
    List <String> logEvents = Collections.synchronizedList(new ArrayList<>());
    AbstractAppender testAppender;

    @Getter
    org.slf4j.Logger testLogger;
    org.apache.logging.log4j.core.Logger internalLogger;

    String loggerName;

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

    @Override
    public void close() {
        internalLogger.removeAppender(testAppender);
        testAppender.stop();
    }
}
