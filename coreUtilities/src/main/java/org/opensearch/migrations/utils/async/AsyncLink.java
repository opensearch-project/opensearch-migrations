/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.migrations.utils.async;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import lombok.NonNull;

/**
 * Statically pairs a handler's output type with a compatible receiver.
 *
 * <p>Normal handler completion necessarily invokes the supplied receiver exactly once. The
 * returned stage completes only after the receiver completes. Unexpected exceptions are not
 * converted into domain values; they remain exceptional so the owning component can report them
 * to the process supervisor.
 */
public final class AsyncLink<I, O, C, R> {
    private final AsyncHandler<I, C, O> handler;

    public AsyncLink(@NonNull AsyncHandler<I, C, O> handler) {
        this.handler = handler;
    }

    public CompletionStage<R> receive(
        @NonNull I input,
        @NonNull AsyncReceiver<O, C, R> receiver,
        @NonNull C context
    ) {
        return invoke(() -> handler.handle(input, context))
            .thenCompose(output -> invoke(() -> receiver.receive(
                Objects.requireNonNull(output, "handler completed with a null output"),
                context
            )));
    }

    private static <T> CompletionStage<T> invoke(
        Supplier<? extends CompletionStage<T>> operation
    ) {
        try {
            return Objects.requireNonNull(
                operation.get(),
                "asynchronous operation returned a null stage"
            );
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
