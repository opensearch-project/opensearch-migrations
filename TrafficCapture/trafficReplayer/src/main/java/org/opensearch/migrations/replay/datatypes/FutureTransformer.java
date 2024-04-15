package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.util.function.Function;

/**
 * This is a function rather than just a supplier so that the future returned can be
 * chained to its logical parent dependency.
 */
public interface FutureTransformer<U> extends
        Function<DiagnosticTrackableCompletableFuture<String,Void>, DiagnosticTrackableCompletableFuture<String,U>> {
}
