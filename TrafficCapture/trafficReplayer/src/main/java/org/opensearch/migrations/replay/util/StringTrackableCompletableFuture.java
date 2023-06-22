package org.opensearch.migrations.replay.util;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class StringTrackableCompletableFuture<T>
        extends DiagnosticTrackableCompletableFuture<String, T> {
    public StringTrackableCompletableFuture(CompletableFuture<T> future, RecursiveImmutableChain<Supplier<String>> diagnosticSupplierChain) {
        super(future, diagnosticSupplierChain);
    }

    public StringTrackableCompletableFuture(CompletableFuture<T> future, Supplier<String> diagnosticSupplier) {
        super(future, diagnosticSupplier);
    }

    public StringTrackableCompletableFuture(CompletableFuture<T> future, String label) {
        super(future, ()->label);
    }

    public static <T> StringTrackableCompletableFuture<T>
    failedFuture(Exception e, Supplier<String> diagnosticSupplier) {
        return new StringTrackableCompletableFuture<>(CompletableFuture.failedFuture(e), diagnosticSupplier);
    }

    public static <U> StringTrackableCompletableFuture<U> completedFuture(U v, Supplier<String> diagnosticSupplier) {
        return new StringTrackableCompletableFuture<>(CompletableFuture.completedFuture(v), diagnosticSupplier);
    }

    public static <D> StringTrackableCompletableFuture<Void>
    allOf(DiagnosticTrackableCompletableFuture<D,Void>[] allRemainingWorkArray, Supplier<String> diagnosticSupplier) {
        return new StringTrackableCompletableFuture<>(
                CompletableFuture.allOf(Arrays.stream(allRemainingWorkArray)
                        .map(tcf->tcf.future).toArray(CompletableFuture[]::new)),
                diagnosticSupplier);

    }
}
