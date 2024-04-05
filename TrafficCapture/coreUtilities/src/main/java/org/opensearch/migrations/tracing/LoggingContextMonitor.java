package org.opensearch.migrations.tracing;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

public class LoggingContextMonitor implements AutoCloseable {
    ExecutorService executorService;
    public LoggingContextMonitor(Logger logger, Duration period) {

    }

    @Override
    public void close() throws Exception {

    }
}
