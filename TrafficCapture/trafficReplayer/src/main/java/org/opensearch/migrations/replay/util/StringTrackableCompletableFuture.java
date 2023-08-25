package org.opensearch.migrations.replay.util;

import lombok.NonNull;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class StringTrackableCompletableFuture<T>
        extends DiagnosticTrackableCompletableFuture<String, T> {
    public StringTrackableCompletableFuture(@NonNull CompletableFuture<T> future, Supplier<String> diagnosticSupplier) {
        super(future, diagnosticSupplier);
    }

    public StringTrackableCompletableFuture(@NonNull CompletableFuture<T> future, String label) {
        super(future, ()->label);
    }

    public static <T> StringTrackableCompletableFuture<T>
    failedFuture(Throwable e, Supplier<String> diagnosticSupplier) {
        return new StringTrackableCompletableFuture<>(CompletableFuture.failedFuture(e), diagnosticSupplier);
    }

    public static <U> StringTrackableCompletableFuture<U> completedFuture(U v, Supplier<String> diagnosticSupplier) {
        return new StringTrackableCompletableFuture<>(CompletableFuture.completedFuture(v), diagnosticSupplier);
    }

    public static <U> StringTrackableCompletableFuture<Void>
    allOf(DiagnosticTrackableCompletableFuture<String,U>[] allRemainingWorkArray, Supplier<String> diagnosticSupplier) {
        return allOf(Arrays.stream(allRemainingWorkArray), diagnosticSupplier);
    }

    public static <U> StringTrackableCompletableFuture<Void>
    allOf(Stream<DiagnosticTrackableCompletableFuture<String,U>> allRemainingWorkStream, Supplier<String> diagnosticSupplier) {
        return new StringTrackableCompletableFuture<>(
                CompletableFuture.allOf(allRemainingWorkStream
                        .map(tcf->tcf.future).toArray(CompletableFuture[]::new)),
                diagnosticSupplier);

    }
}
