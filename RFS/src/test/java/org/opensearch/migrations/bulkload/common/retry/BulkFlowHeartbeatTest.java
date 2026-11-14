package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.bulkload.common.retry.BulkFlowMetrics.AttemptOutcome;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.test.scheduler.VirtualTimeScheduler;

class BulkFlowHeartbeatTest {
    private static final Duration INTERVAL = Duration.ofMinutes(1);

    @Test
    void logsEachIntervalsActivityAndTheCurrentState() throws Exception {
        var scheduler = VirtualTimeScheduler.create();
        var limit = new AdaptiveLimit(10, () -> 0.0);
        var budget = new RetryBudget(500);
        try (var logs = new CloseableLogSetup(BulkFlowHeartbeat.class.getName())) {
            var heartbeat = new BulkFlowHeartbeat(budget, limit, INTERVAL, scheduler);
            heartbeat.onAttempt(AttemptOutcome.OK, false, 10);
            heartbeat.onAttempt(AttemptOutcome.OK, false, 10);
            heartbeat.onAttempt(AttemptOutcome.THROTTLED_429, false, 10);
            heartbeat.onRetry(BulkErrorClass.THROTTLE, 100, false);
            heartbeat.onLimitCut(AdaptiveLimit.Signal.THROTTLED_429, 4, 2);
            heartbeat.onDocumentsResent(1000);
            heartbeat.onRequestBody(100, 4000, 1000);
            heartbeat.onRequestBody(300, 8000, 3000);
            scheduler.advanceTimeBy(INTERVAL);

            List<String> lines = logs.getLogEvents();
            Assertions.assertEquals(1, lines.size(), lines.toString());
            var line = lines.get(0);
            Assertions.assertTrue(line.contains("3 attempts (ok 2, throttled_429 1)"), line);
            Assertions.assertTrue(line.contains("2 requests sent, averaging 200 documents and 6000 bytes "
                + "(2000 bytes on the wire)"), line);
            Assertions.assertTrue(line.contains("1 retries (1 throttled, 0 unavailable, 0 transient)"), line);
            Assertions.assertTrue(line.contains("0 documents waiting on the target"), line);
            Assertions.assertTrue(line.contains("1 limit cuts"), line);
            Assertions.assertTrue(line.contains("1000 documents re-sent"), line);
            Assertions.assertTrue(line.contains("limit 1.00 of 10"), line);
            Assertions.assertTrue(line.contains("retry budget 500 of 500 tokens"), line);

            // Counts are per interval, and an interval with nothing happening logs nothing.
            scheduler.advanceTimeBy(INTERVAL);
            Assertions.assertEquals(1, logs.getLogEvents().size(), logs.getLogEvents().toString());

            heartbeat.onAttempt(AttemptOutcome.TIMEOUT, true, 10);
            scheduler.advanceTimeBy(INTERVAL);
            Assertions.assertEquals(2, logs.getLogEvents().size());
            Assertions.assertTrue(logs.getLogEvents().get(1).contains("1 attempts (timeout 1)"),
                logs.getLogEvents().get(1));

            heartbeat.dispose();
            heartbeat.onAttempt(AttemptOutcome.OK, false, 10);
            scheduler.advanceTimeBy(INTERVAL);
            Assertions.assertEquals(2, logs.getLogEvents().size());
        }
    }

    @Test
    void reportsRequestsInFlightEvenWithoutFinishedAttempts() throws Exception {
        var scheduler = VirtualTimeScheduler.create();
        var limit = new AdaptiveLimit(10, () -> 0.0);
        try (var logs = new CloseableLogSetup(BulkFlowHeartbeat.class.getName())) {
            var heartbeat = new BulkFlowHeartbeat(new RetryBudget(500), limit, INTERVAL, scheduler);
            var slot = limit.acquire().block();
            scheduler.advanceTimeBy(INTERVAL);
            Assertions.assertEquals(1, logs.getLogEvents().size());
            Assertions.assertTrue(logs.getLogEvents().get(0).contains("0 attempts (none finished)"),
                logs.getLogEvents().get(0));
            Assertions.assertTrue(logs.getLogEvents().get(0).contains("1 in flight"), logs.getLogEvents().get(0));
            slot.release(AdaptiveLimit.Signal.SUCCESS);
            heartbeat.dispose();
        }
    }
}
