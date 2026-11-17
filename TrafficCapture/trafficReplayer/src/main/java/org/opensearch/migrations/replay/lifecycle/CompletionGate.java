package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Gives one state owner exclusive completion authority while exposing only a read-only completion stage.
 *
 * <p>The mutable {@link CompletableFuture} never escapes, so consumers can observe or compose the result but
 * cannot complete, cancel, or otherwise race the owning state machine. The boolean completion result lets
 * the owner detect duplicate terminal transitions instead of silently accepting them.</p>
 */
final class CompletionGate<T> {
    private final CompletableFuture<T> ownerFuture = new CompletableFuture<>();
    private final CompletionStage<T> readOnlyView = ownerFuture.minimalCompletionStage();

    CompletionStage<T> stage() {
        return readOnlyView;
    }

    boolean complete(T value) {
        return ownerFuture.complete(value);
    }

    boolean completeExceptionally(Throwable cause) {
        return ownerFuture.completeExceptionally(cause);
    }

    boolean isDone() {
        return ownerFuture.isDone();
    }
}
