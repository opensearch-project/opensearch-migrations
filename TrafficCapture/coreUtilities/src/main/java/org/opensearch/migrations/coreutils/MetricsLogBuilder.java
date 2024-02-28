package org.opensearch.migrations.coreutils;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.Optional;


@Slf4j
public class MetricsLogBuilder {
    private Logger logger;
    private LoggingEventBuilder loggingEventBuilder;

    public MetricsLogBuilder(Logger logger) {
        this.logger = logger;
    }

    public static MetricsLogBuilder addMetricIfPresent(MetricsLogBuilder metricBuilder,
                                                       MetricsAttributeKey key, Optional<Object> value) {
        return value.map(v -> metricBuilder.setAttribute(key, v)).orElse(metricBuilder);
    }

    public MetricsLogBuilder setAttribute(MetricsAttributeKey key, Object value) {
        loggingEventBuilder = loggingEventBuilder.addKeyValue(key.getKeyName(), value);
        return this;
    }

    public MetricsLogBuilder atSuccess(MetricsEvent event) {
        loggingEventBuilder = logger.makeLoggingEventBuilder(Level.INFO);
        setAttribute(MetricsAttributeKey.EVENT, event.toString());
        return this;
    }

    public MetricsLogBuilder atError(MetricsEvent event) {
        loggingEventBuilder = logger.makeLoggingEventBuilder(Level.ERROR);
        setAttribute(MetricsAttributeKey.EVENT, event.toString());
        return this;
    }

    public MetricsLogBuilder atTrace(MetricsEvent event) {
        loggingEventBuilder = logger.makeLoggingEventBuilder(Level.TRACE);
        setAttribute(MetricsAttributeKey.EVENT, event.toString());
        return this;
    }

    public void emit() {
        loggingEventBuilder.log();
    }
}
