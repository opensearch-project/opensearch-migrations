package org.opensearch.migrations.replay.util;

import lombok.NonNull;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

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
    allOf(DiagnosticTrackableCompletableFuture<String,? extends U>[] allRemainingWorkArray, Supplier<String> diagnosticSupplier) {
        return new StringTrackableCompletableFuture<>(
                CompletableFuture.allOf(Arrays.stream(allRemainingWorkArray)
                        .map(tcf->tcf.future).toArray(CompletableFuture[]::new)),
                diagnosticSupplier);

    }
}
