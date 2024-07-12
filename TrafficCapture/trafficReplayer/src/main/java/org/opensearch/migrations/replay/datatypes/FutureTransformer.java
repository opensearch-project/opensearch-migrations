package org.opensearch.migrations.replay.datatypes;

import java.util.function.Function;

import org.opensearch.migrations.replay.util.TrackedFuture;

/**
 * This is a function rather than just a supplier so that the future returned can be
 * chained to its logical parent dependency.
 */
public interface FutureTransformer<U> extends Function<TrackedFuture<String, Void>, TrackedFuture<String, U>> {}
