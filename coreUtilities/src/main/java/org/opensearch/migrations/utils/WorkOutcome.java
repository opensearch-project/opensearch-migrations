package org.opensearch.migrations.utils;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import lombok.NonNull;

/**
 * The terminal outcome of an asynchronous work item.
 *
 * Completion and success are intentionally distinct: failed and cancelled work is settled, but
 * must not be treated as successful by downstream business callbacks.
 */
public sealed interface WorkOutcome<T>
    permits WorkOutcome.Succeeded, WorkOutcome.Failed, WorkOutcome.Cancelled {

    interface Visitor<T, R> {
        R onSucceeded(Succeeded<T> outcome);

        R onFailed(Failed<T> outcome);

        R onCancelled(Cancelled<T> outcome);
    }

    <R> R visit(Visitor<T, R> visitor);

    record Succeeded<T>(T value) implements WorkOutcome<T> {
        @Override
        public <R> R visit(@NonNull Visitor<T, R> visitor) {
            return visitor.onSucceeded(this);
        }
    }

    record Failed<T>(@NonNull Throwable cause) implements WorkOutcome<T> {
        @Override
        public <R> R visit(@NonNull Visitor<T, R> visitor) {
            return visitor.onFailed(this);
        }
    }

    record Cancelled<T>(@NonNull CancellationException cause) implements WorkOutcome<T> {
        @Override
        public <R> R visit(@NonNull Visitor<T, R> visitor) {
            return visitor.onCancelled(this);
        }
    }

    static <T> WorkOutcome<T> from(T value, Throwable throwable) {
        if (throwable == null) {
            return new Succeeded<>(value);
        }

        var cause = unwrapCompletionException(throwable);
        if (cause instanceof CancellationException cancellationException) {
            return new Cancelled<>(cancellationException);
        }
        return new Failed<>(cause);
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        var current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null)
        {
            current = current.getCause();
        }
        return current;
    }
}
