package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.tracing.AsyncPermitPoolMetrics;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AsyncPermitPoolTest extends InstrumentationTest {
    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void grantsInFifoOrderAndReleasesExactlyOnce() {
        var executor = new QueuedExecutor();
        var pool = new AsyncPermitPool(1, executor);
        var firstFuture = pool.acquire(request(0), 1).toCompletableFuture();
        var secondFuture = pool.acquire(request(1), 1).toCompletableFuture();

        executor.runAll();
        var first = firstFuture.join();
        Assertions.assertFalse(secondFuture.isDone());

        first.close();
        first.close();
        executor.runAll();

        Assertions.assertEquals(request(1), secondFuture.join().requestId());
    }

    @Test
    void cancellationAndShutdownSettleEveryQueuedAcquire() {
        var executor = new QueuedExecutor();
        var pool = new AsyncPermitPool(1, executor);
        var active = pool.acquire(request(0), 1).toCompletableFuture();
        var cancelled = pool.acquire(request(1), 1).toCompletableFuture();
        var closed = pool.acquire(request(2), 1).toCompletableFuture();
        executor.runAll();

        pool.cancel(id -> id.equals(request(1)), new CancellationException("session aborted"));
        pool.close(new CancellationException("pool closed"));
        executor.runAll();

        Assertions.assertTrue(cancelled.isCompletedExceptionally());
        Assertions.assertTrue(closed.isCompletedExceptionally());
        active.join().close();
        executor.runAll();
    }

    @Test
    void recordsAvailabilityQueueingLeaseDurationAndCancellation() {
        var executor = new QueuedExecutor();
        var nanoTime = new AtomicLong();
        var pool = new AsyncPermitPool(
            2,
            executor,
            rootContext.getPermitPoolMetrics(),
            nanoTime::get
        );
        var first = pool.acquire(request(0), 1).toCompletableFuture();
        var explicitlyCancelled = pool.acquire(request(1), 2).toCompletableFuture();
        executor.runAll();

        assertLongSum(AsyncPermitPoolMetrics.MetricNames.AVAILABLE, 1);
        assertLongSum(AsyncPermitPoolMetrics.MetricNames.QUEUED, 1);

        nanoTime.set(5_000_000);
        pool.cancel(request(1)::equals, new CancellationException("session aborted"));
        executor.runAll();
        assertLongSum(AsyncPermitPoolMetrics.MetricNames.QUEUED, 0);
        assertLongSum(AsyncPermitPoolMetrics.MetricNames.CANCELLATION_COUNT, 1);
        Assertions.assertTrue(explicitlyCancelled.isCompletedExceptionally());

        first.join().close();
        executor.runAll();
        var heldAtShutdown = pool.acquire(request(2), 2).toCompletableFuture();
        var cancelledAtShutdown = pool.acquire(request(3), 1).toCompletableFuture();
        executor.runAll();

        nanoTime.set(12_000_000);
        pool.close(new CancellationException("pool closed"));
        executor.runAll();
        heldAtShutdown.join().close();
        executor.runAll();
        var rejectedAfterShutdown = pool.acquire(request(4), 1).toCompletableFuture();
        executor.runAll();

        assertLongSum(AsyncPermitPoolMetrics.MetricNames.AVAILABLE, 0);
        assertLongSum(AsyncPermitPoolMetrics.MetricNames.QUEUED, 0);
        assertLongSum(AsyncPermitPoolMetrics.MetricNames.CANCELLATION_COUNT, 3);
        Assertions.assertTrue(cancelledAtShutdown.isCompletedExceptionally());
        Assertions.assertTrue(rejectedAfterShutdown.isCompletedExceptionally());

        var heldDuration = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(metric -> metric.getName().equals(AsyncPermitPoolMetrics.MetricNames.HELD_DURATION))
            .findFirst()
            .orElseThrow()
            .getHistogramData()
            .getPoints()
            .stream()
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(2, heldDuration.getCount());
        Assertions.assertEquals(12.0, heldDuration.getSum());
    }

    private void assertLongSum(String metricName, long expected) {
        var metric = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(candidate -> candidate.getName().equals(metricName))
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(
            expected,
            metric.getLongSumData().getPoints().stream().findFirst().orElseThrow().getValue()
        );
    }

    private static ReplayRequestId request(int index) {
        return new ReplayRequestId(
            new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 0, 1),
            index
        );
    }

    private static class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}
