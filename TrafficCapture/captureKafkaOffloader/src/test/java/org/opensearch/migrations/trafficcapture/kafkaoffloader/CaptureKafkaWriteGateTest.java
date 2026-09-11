package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureKafkaWriteGateTest {
    @Test
    void tripWaitsForAnAcceptedSubmissionAndRejectsEveryLaterSubmission() throws Exception {
        var gate = new CaptureKafkaWriteGate();
        var submissionStarted = new CountDownLatch(1);
        var releaseSubmission = new CountDownLatch(1);
        var tripFinished = new CountDownLatch(1);
        var terminalFailure = new IllegalStateException("evicted");
        var laterSubmissionRan = new AtomicBoolean();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var accepted = executor.submit(() -> gate.submitIfWritable(() -> {
                submissionStarted.countDown();
                await(releaseSubmission);
            }));
            assertTrue(submissionStarted.await(1, TimeUnit.SECONDS));
            var trip = executor.submit(() -> {
                try {
                    return gate.trip(terminalFailure);
                } finally {
                    tripFinished.countDown();
                }
            });

            assertFalse(tripFinished.await(50, TimeUnit.MILLISECONDS));
            releaseSubmission.countDown();
            assertNull(accepted.get(1, TimeUnit.SECONDS));
            assertSame(terminalFailure, trip.get(1, TimeUnit.SECONDS));
        }

        assertSame(
            terminalFailure,
            gate.submitIfWritable(() -> laterSubmissionRan.set(true))
        );
        assertFalse(laterSubmissionRan.get());
    }

    @Test
    void terminalListenerReceivesOnlyTheFirstCause() {
        var gate = CaptureKafkaWriteGate.unrestricted();
        var observedFailure = new AtomicReference<Throwable>();
        gate.addTerminalFailureListener(observedFailure::set);
        var first = new IllegalStateException("first");

        assertSame(first, gate.trip(first));
        assertSame(first, gate.trip(new IllegalStateException("second")));
        assertEquals("first", observedFailure.get().getMessage());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
