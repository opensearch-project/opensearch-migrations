package org.opensearch.migrations.replay.lifecycle;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Serializes replay-intake state changes onto the thread that constructs this mailbox.
 *
 * <p>The owner can keep servicing cross-thread completions while it waits for source I/O. This
 * avoids adding a feeder thread merely to keep permit and progress state responsive.
 */
public final class ReplayIntakeMailbox implements Executor {
    private static final Runnable WAKE_UP = () -> {};

    private final Thread owner = Thread.currentThread();
    private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
    private boolean dispatching;
    private Throwable queuedFailure;

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command);
        if (isOwnerThread() && !dispatching) {
            dispatch(command);
        } else {
            commands.add(command);
        }
    }

    public boolean isOwnerThread() {
        return Thread.currentThread() == owner;
    }

    public void runUntilIdle() throws ExecutionException {
        assertOwner();
        Runnable command;
        while ((command = commands.poll()) != null) {
            dispatchQueued(command);
        }
        throwQueuedFailure();
    }

    public <T> T await(CompletionStage<T> stage) throws InterruptedException, ExecutionException {
        assertOwner();
        var completion = Objects.requireNonNull(stage).toCompletableFuture();
        if (!completion.isDone()) {
            stage.whenComplete((value, failure) -> commands.add(WAKE_UP));
        }
        while (!completion.isDone()) {
            dispatchQueued(commands.take());
            throwQueuedFailure();
        }
        runUntilIdle();
        return completion.get();
    }

    private void dispatchQueued(Runnable command) {
        try {
            dispatch(command);
        } catch (Exception t) {
            if (queuedFailure == null) {
                queuedFailure = t;
            } else {
                queuedFailure.addSuppressed(t);
            }
        }
    }

    private void dispatch(Runnable command) {
        assertOwner();
        dispatching = true;
        try {
            command.run();
        } finally {
            dispatching = false;
        }
    }

    private void throwQueuedFailure() throws ExecutionException {
        if (queuedFailure != null) {
            var failure = queuedFailure;
            queuedFailure = null;
            throw new ExecutionException("replay intake mailbox command failed", failure);
        }
    }

    private void assertOwner() {
        if (!isOwnerThread()) {
            throw new IllegalStateException("replay intake mailbox accessed from a non-owner thread");
        }
    }
}
