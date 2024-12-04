package org.opensearch.migrations.utils;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

import lombok.NonNull;

public class TextTrackedFuture<T> extends TrackedFuture<String, T> {

    public TextTrackedFuture(String diagnosticLabel) {
        this(new CompletableFuture<>(), () -> diagnosticLabel);
    }

    public TextTrackedFuture(Supplier<String> diagnosticSupplier) {
        this(new CompletableFuture<>(), diagnosticSupplier);
    }

    public TextTrackedFuture(@NonNull CompletableFuture<T> future, Supplier<String> diagnosticSupplier) {
        super(future, diagnosticSupplier);
    }

    public TextTrackedFuture(@NonNull CompletableFuture<T> future, String diagnosticLabel) {
        super(future, () -> diagnosticLabel);
    }

    public static <T> TextTrackedFuture<T> failedFuture(Throwable e, Supplier<String> diagnosticSupplier) {
        return new TextTrackedFuture<>(CompletableFuture.failedFuture(e), diagnosticSupplier);
    }

    public static <U> TextTrackedFuture<U> completedFuture(U v, Supplier<String> diagnosticSupplier) {
        return new TextTrackedFuture<>(CompletableFuture.completedFuture(v), diagnosticSupplier);
    }

    public static <U> TextTrackedFuture<Void> allOf(
        TrackedFuture<String, U>[] allRemainingWorkArray,
        Supplier<String> diagnosticSupplier
    ) {
        return allOf(Arrays.stream(allRemainingWorkArray), diagnosticSupplier);
    }

    public static <U> TextTrackedFuture<Void> allOf(
        Stream<TrackedFuture<String, U>> allRemainingWorkStream,
        Supplier<String> diagnosticSupplier
    ) {
        return new TextTrackedFuture<>(
            CompletableFuture.allOf(allRemainingWorkStream.map(tcf -> tcf.future).toArray(CompletableFuture[]::new)),
            diagnosticSupplier
        );

    }
}
