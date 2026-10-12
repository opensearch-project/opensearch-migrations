/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.NonNull;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/** Captures formatted Log4j events from one production logger for behavior assertions. */
public final class TestLogAppender extends AbstractAppender implements AutoCloseable {
    private final org.apache.logging.log4j.core.Logger logger;
    private final Level previousLevel;
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    public TestLogAppender(@NonNull Class<?> loggerClass) {
        this(loggerClass.getName());
    }

    public TestLogAppender(@NonNull String loggerName) {
        super(
            "test-log-appender-" + loggerName,
            null,
            PatternLayout.createDefaultLayout(),
            false,
            Property.EMPTY_ARRAY
        );
        logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        start();
        logger.addAppender(this);
    }

    @Override
    public void append(LogEvent event) {
        events.add(event.toImmutable());
    }

    public List<LogEvent> events() {
        return List.copyOf(events);
    }

    public boolean contains(String eventName, String... requiredFields) {
        var prefix = "event=" + eventName;
        return events.stream()
            .map(event -> event.getMessage().getFormattedMessage())
            .anyMatch(message -> {
                if (!message.startsWith(prefix)) {
                    return false;
                }
                for (var field : requiredFields) {
                    if (!message.contains(field)) {
                        return false;
                    }
                }
                return true;
            });
    }

    public long count(String eventName, Level level) {
        var prefix = "event=" + eventName;
        return events.stream()
            .filter(event -> event.getLevel() == level)
            .map(event -> event.getMessage().getFormattedMessage())
            .filter(message -> message.startsWith(prefix))
            .count();
    }

    @Override
    public void close() {
        logger.removeAppender(this);
        logger.setLevel(previousLevel);
        stop();
    }
}
