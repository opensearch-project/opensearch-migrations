package org.opensearch.migrations.coreutils;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

@Slf4j
public
class MetricsLogger {
    private static Logger logger;

    /**
     * Creates a MetricLogger instance. Under the hood this creates a slf4j logger instance.
     *
     * @param source Generally the name of the class where the logger is being instantiated, this
     *               will be combined with `MetricsLogger.` to form the logger name.
     * @return A MetricsLogger instance.
     */
    public MetricsLogger(String source) {
        logger = LoggerFactory.getLogger(String.format("MetricsLogger.%s", source));
    }

    /**
     * To indicate a successful event (e.g. data received or data sent) that may be a helpful
     * metric, this method can be used to return a LoggingEventBuilder. The LoggingEventBuilder
     * object can be supplemented with key-value pairs (used for diagnostic information and
     * dimensions for the metric) and a log message, and then logged. As an example,
     *  metricsLogger.atSuccess().addKeyValue("key", "value").setMessage("Task succeeded").log();
     */
    public LoggingEventBuilder atSuccess() {
        return logger.makeLoggingEventBuilder(Level.INFO);
    }

    /**
     * Indicates a failed event that may be a helpful metric. This method returns a LoggingEventBuilder
     * which should be supplemented with diagnostic information or dimensions for the metric (as
     * key-value pairs) and a log message.
     * @param cause The exception thrown in the failure, this will be set as the cause for the log message.
     */
    public LoggingEventBuilder atError(Throwable cause) {
        return logger.makeLoggingEventBuilder(Level.ERROR).setCause(cause);
    }

    /**
     * This also indicates a failed event that may be a helpful metric. It can be used in cases where
     * there is a failure that isn't indicated by an Exception being thrown.
     */
    public LoggingEventBuilder atError() {
        return logger.makeLoggingEventBuilder(Level.ERROR);
    }
}
