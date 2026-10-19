package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import lombok.NonNull;

/**
 * Enforces the single-writer thread-confinement rule used by replay state owners.
 *
 * <p>For an executor without a direct thread predicate, the first guarded task atomically binds the owner
 * thread and every later task must run there. For event loops and dedicated mailboxes that already know
 * whether the current thread is valid, the guard delegates to that predicate.</p>
 *
 * <p>The checks remain enabled in production. A wrong-thread mutation is an architectural invariant failure,
 * not a debug-only assertion, because it can reorder lifecycle transitions or race exact record and resource
 * accounting.</p>
 */
public final class OwnerThreadGuard {
    private final String ownerDescription;
    private final BooleanSupplier currentThreadIsOwner;
    private final AtomicReference<Thread> boundOwner;

    public OwnerThreadGuard(@NonNull String ownerDescription) {
        this.ownerDescription = ownerDescription;
        this.currentThreadIsOwner = null;
        this.boundOwner = new AtomicReference<>();
    }

    public OwnerThreadGuard(
        @NonNull String ownerDescription,
        @NonNull BooleanSupplier currentThreadIsOwner
    ) {
        this.ownerDescription = ownerDescription;
        this.currentThreadIsOwner = currentThreadIsOwner;
        this.boundOwner = null;
    }

    /**
     * Wraps an owner-executor task, binding the first task's thread and rejecting any later task
     * that runs on a different thread.
     */
    public Runnable guard(@NonNull Runnable command) {
        return () -> {
            bindOrRequireOwnerThread();
            command.run();
        };
    }

    public void requireOwnerThread() {
        if (currentThreadIsOwner != null) {
            if (!currentThreadIsOwner.getAsBoolean()) {
                throw wrongThread();
            }
            return;
        }
        var owner = boundOwner.get();
        if (owner == null || owner != Thread.currentThread()) {
            throw wrongThread();
        }
    }

    private void bindOrRequireOwnerThread() {
        if (currentThreadIsOwner != null) {
            requireOwnerThread();
            return;
        }
        var current = Thread.currentThread();
        var owner = boundOwner.get();
        if (owner == null && boundOwner.compareAndSet(null, current)) {
            return;
        }
        requireOwnerThread();
    }

    private IllegalStateException wrongThread() {
        return new IllegalStateException(
            ownerDescription + " accessed from non-owner thread " + Thread.currentThread().getName()
        );
    }
}
