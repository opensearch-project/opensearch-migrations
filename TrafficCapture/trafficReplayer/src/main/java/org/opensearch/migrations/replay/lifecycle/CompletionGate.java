package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
