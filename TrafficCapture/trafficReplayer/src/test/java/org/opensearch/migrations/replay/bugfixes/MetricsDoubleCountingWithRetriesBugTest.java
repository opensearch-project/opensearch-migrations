package org.opensearch.migrations.replay.bugfixes;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.TrafficReplayerCore;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Bug 3: TrafficReplayerCore.perResponseConsumer() is called for every response including retries.
 *
 * A request that fails twice then succeeds will increment exceptionRequestCount by 2 AND
 * successfulRequestCount by 1. The counters should only reflect the final outcome per request.
 *
 * This test asserts on the CURRENT BUGGY behavior (double-counting).
 * When the bug is fixed, this test should FAIL.
 */
@Slf4j
public class MetricsDoubleCountingWithRetriesBugTest {

    /**
     * Minimal concrete subclass that exposes protected members for testing.
     */
    static class TestableReplayerCore extends TrafficReplayerCore {
        @SuppressWarnings("unchecked")
        TestableReplayerCore() {
            super(
                mock(IRootReplayerContext.class),
                URI.create("http://localhost:9200"),
                null,
                () -> mock(IJsonTransformer.class),
                mock(TrafficStreamLimiter.class),
                mock(TrafficReplayerCore.IWorkTracker.class),
                mock(IRetryVisitorFactory.class)
            );
        }

        @Override
        protected CompletableFuture<Void> shutdown(Error error) {
            return CompletableFuture.completedFuture(null);
        }

        // Expose protected method and counters
        void callPerResponseConsumer(AggregatedRawResponse summary,
                                     HttpRequestTransformationStatus status,
                                     IReplayContexts.IReplayerHttpTransactionContext ctx) {
            perResponseConsumer(summary, status, ctx);
        }

        AtomicInteger getSuccessCount() { return successfulRequestCount; }
        AtomicInteger getExceptionCount() { return exceptionRequestCount; }
    }

    @Test
    void perResponseConsumer_countsEveryRetryResponse_notJustFinalOutcome() {
        var core = new TestableReplayerCore();
        var ctx = mock(IReplayContexts.IReplayerHttpTransactionContext.class);
        var okStatus = HttpRequestTransformationStatus.completed();

        // Simulate what happens during retries: perResponseConsumer is called for EACH response
        var errorResponse = new AggregatedRawResponse(null, 0, null, null, new RuntimeException("503"));
        var successResponse = new AggregatedRawResponse(null, 0, null, null, null);

        // Retry 1: error
        core.callPerResponseConsumer(errorResponse, okStatus, ctx);
        // Retry 2: error
        core.callPerResponseConsumer(errorResponse, okStatus, ctx);
        // Final attempt: success
        core.callPerResponseConsumer(successResponse, okStatus, ctx);

        // BUG ASSERTION: For a single request with 2 retries then success,
        // both counters are incremented. A correct implementation would only
        // count the final outcome (1 success, 0 exceptions).
        Assertions.assertEquals(2, core.getExceptionCount().get(),
            "BUG: exceptionRequestCount is 2 because each retry error is counted separately");
        Assertions.assertEquals(1, core.getSuccessCount().get(),
            "BUG: successfulRequestCount is also 1, so total counted = 3 for 1 request");

        // The total counted responses (3) exceeds the actual number of unique requests (1)
        int totalCounted = core.getExceptionCount().get() + core.getSuccessCount().get();
        Assertions.assertEquals(3, totalCounted,
            "BUG: 3 responses counted for 1 request due to per-retry counting");
    }
}
