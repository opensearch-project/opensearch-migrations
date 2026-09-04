package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AsyncPermitPoolTest {
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
