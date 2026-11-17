package org.opensearch.migrations.trafficcapture.proxyserver;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureFailureTerminatorTest {
    @Test
    void flushesLogsAndHaltsExactlyOnce() throws Exception {
        var flushCalls = new AtomicInteger();
        var haltCalls = new AtomicInteger();
        var halted = new CountDownLatch(1);
        var terminator = new CaptureFailureTerminator(
            78,
            Duration.ofSeconds(1),
            flushCalls::incrementAndGet,
            exitCode -> {
                assertEquals(78, exitCode);
                haltCalls.incrementAndGet();
                halted.countDown();
            }
        );

        terminator.accept(new IllegalStateException("first"));
        terminator.accept(new IllegalStateException("second"));

        assertTrue(halted.await(1, TimeUnit.SECONDS));
        assertEquals(1, flushCalls.get());
        assertEquals(1, haltCalls.get());
    }

    @Test
    void haltDoesNotWaitPastTheLogFlushTimeout() throws Exception {
        var releaseFlush = new CountDownLatch(1);
        var halted = new CountDownLatch(1);
        var terminator = new CaptureFailureTerminator(
            78,
            Duration.ofMillis(5),
            () -> {
                try {
                    releaseFlush.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            },
            ignored -> halted.countDown()
        );

        terminator.accept(new IllegalStateException("capture failed"));

        assertTrue(halted.await(1, TimeUnit.SECONDS));
        releaseFlush.countDown();
    }
}
