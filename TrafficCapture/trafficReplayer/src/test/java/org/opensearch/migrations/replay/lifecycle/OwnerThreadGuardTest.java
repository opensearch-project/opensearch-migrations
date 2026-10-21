package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OwnerThreadGuardTest {
    @Test
    void firstGuardedTaskBindsTheOwnerThread() throws Exception {
        var guard = new OwnerThreadGuard("test owner");
        guard.guard(guard::requireOwnerThread).run();

        var offOwnerFailure = new CompletableFuture<Throwable>();
        var thread = new Thread(() -> {
            try {
                guard.requireOwnerThread();
                offOwnerFailure.complete(null);
            } catch (Throwable t) {
                offOwnerFailure.complete(t);
            }
        }, "not-the-owner");
        thread.start();
        thread.join();

        Assertions.assertInstanceOf(IllegalStateException.class, offOwnerFailure.join());
    }

    @Test
    void explicitOwnerCheckIsAlwaysEnforced() {
        var owner = Thread.currentThread();
        var guard = new OwnerThreadGuard("explicit owner", () -> Thread.currentThread() == owner);

        guard.requireOwnerThread();
        var failure = new CompletableFuture<Throwable>();
        var thread = new Thread(() -> {
            try {
                guard.requireOwnerThread();
                failure.complete(null);
            } catch (Throwable t) {
                failure.complete(t);
            }
        }, "not-the-explicit-owner");
        thread.start();
        Assertions.assertDoesNotThrow(() -> {
            thread.join();
        });
        Assertions.assertInstanceOf(IllegalStateException.class, failure.join());
    }
}
