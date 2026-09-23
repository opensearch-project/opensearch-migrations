package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: IReplayContexts IRetryVisitorFactory IRootReplayerContext TrafficReplayerCore . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

*/
// REBUILD-LIMBO-END(G10)
/**
 * Verifies that request metrics are counted once per request, not once per retry attempt.
 *
 * A request that fails twice then succeeds will increment exceptionRequestCount by 2 AND
 * successfulRequestCount by 1. The counters should only reflect the final outcome per request.
 *
 * This test verifies that perResponseConsumer does not increment counters (counting
 * moved to handleCompletedTransaction which is called once per request).
 */
// REBUILD-LIMBO-START(G10)
/*
@Slf4j
public class MetricsDoubleCountingWithRetriesTest {

*/
// REBUILD-LIMBO-END(G10)
    /**
     * Minimal concrete subclass that exposes protected members for testing.
     */
// REBUILD-LIMBO-START(G10)
/*
    static class TestableReplayerCore extends TrafficReplayerCore {
        @SuppressWarnings("unchecked")
        TestableReplayerCore() {
            super(
                mock(IRootReplayerContext.class),
                URI.create("http://localhost:9200"),
                null,
                () -> mock(IJsonTransformer.class),
                1,
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
    void perResponseConsumer_doesNotCountRetries() {
        var core = new TestableReplayerCore();
        var ctx = mock(IReplayContexts.IReplayerHttpTransactionContext.class);
        var okStatus = HttpRequestTransformationStatus.completed();

        // Simulate retries: perResponseConsumer is called for EACH response
        var errorResponse = new AggregatedRawResponse(null, 0, null, null, new RuntimeException("503"));
        var successResponse = new AggregatedRawResponse(null, 0, null, null, null);

        // Retry 1: error
        core.callPerResponseConsumer(errorResponse, okStatus, ctx);
        // Retry 2: error
        core.callPerResponseConsumer(errorResponse, okStatus, ctx);
        // Final attempt: success
        core.callPerResponseConsumer(successResponse, okStatus, ctx);

        // perResponseConsumer does not increment counters — counting is done
        // once per request in handleCompletedTransaction
        Assertions.assertEquals(0, core.getExceptionCount().get(),
            "perResponseConsumer should not count retries");
        Assertions.assertEquals(0, core.getSuccessCount().get(),
            "perResponseConsumer should not count retries");
    }
}

*/
// REBUILD-LIMBO-END(G10)