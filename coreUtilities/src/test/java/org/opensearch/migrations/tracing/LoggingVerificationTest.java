package org.opensearch.migrations.tracing;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingVerificationTest {
    @Test
    void verifyTraceLogSupplersExecuted() {
        var traceArgExecuted = new AtomicInteger(0);
        log.atTrace().setMessage("TraceArgExecuted {} times").addArgument(traceArgExecuted::incrementAndGet).log();
        Assertions.assertEquals(1, traceArgExecuted.get());
    }
}
