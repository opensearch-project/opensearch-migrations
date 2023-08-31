package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.time.Instant;
import java.util.TreeMap;
import java.util.concurrent.Callable;

public class TimeToResponseFulfillmentFutureMap extends TreeMap<Instant, Runnable> {
}
