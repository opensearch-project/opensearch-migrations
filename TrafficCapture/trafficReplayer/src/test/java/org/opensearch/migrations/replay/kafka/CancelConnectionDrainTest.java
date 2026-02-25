package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that cancelConnection drains the OnlineRadixSorter by completing pending
 * scheduleFuture entries exceptionally, rather than leaving them orphaned.
 *
 * Gap: schedule.clear() in closeClientConnectionChannel removes TimeToResponseFulfillmentFutureMap
 * entries without completing their scheduleFuture futures. Sorter slots waiting on those futures
 * stall indefinitely, leaving requestWorkTracker entries and TrafficStreamLimiter slots unreleased.
 */
class CancelConnectionDrainTest extends InstrumentationTest {

    private NioEventLoopGroup eventLoopGroup;
    private ClientConnectionPool pool;
    private RequestSenderOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        eventLoopGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("test"));
        pool = new ClientConnectionPool(
            (el, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no-op channel"),
            "test-pool", 1
        );
        orchestrator = new RequestSenderOrchestrator(pool, (session, ctx) -> null);
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdownNow().get();
        eventLoopGroup.shutdownGracefully();
    }

    /**
     * After cancelConnection, the OnlineRadixSorter must be empty — orphaned scheduleFuture
     * entries would leave requestWorkTracker entries and TrafficStreamLimiter slots unreleased.
     *
     * Before fix: schedule.clear() orphans scheduleFuture entries → sorter slots never fire →
     * scheduleSequencer.isEmpty() stays false indefinitely.
     */
    @Test
    void cancelConnection_drainsSorterSlots() throws Exception {
        var onCloseFired = new CountDownLatch(1);
        pool.setGlobalOnSessionClose(session -> onCloseFired.countDown());

        var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0).getChannelKeyContext();

        // Get the session before cancelling so we can inspect it after
        var session = pool.getCachedSession(channelKeyCtx, 0);

        // Schedule 3 requests at far-future timestamps — they queue in the sorter
        for (int i = 0; i < 3; i++) {
            var reqCtx = rootContext.getTestConnectionRequestContext("conn-A", i);
            var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[]{1}));
            orchestrator.scheduleRequest(
                reqCtx.getReplayerRequestKey(),
                reqCtx,
                java.time.Instant.now().plusSeconds(60), // far future — won't fire naturally
                Duration.ZERO,
                packets,
                new org.opensearch.migrations.replay.http.retries.NoRetryEvaluatorFactory.NoRetryVisitor()
            );
        }

        // Let the event loop process the submissions
        Thread.sleep(50);
        Assertions.assertFalse(session.scheduleSequencer.isEmpty(),
            "sorter should have queued work before cancel");

        // cancelConnection should drain all queued sorter slots
        pool.cancelConnection(channelKeyCtx, 0);

        // onClose fires (null-channel path completes immediately)
        boolean fired = onCloseFired.await(5, TimeUnit.SECONDS);
        Assertions.assertTrue(fired, "onClose callback must fire after cancelConnection");

        // After cancel, the sorter must drain — poll with timeout
        // Before fix: orphaned scheduleFuture entries leave sorter slots pending indefinitely
        var deadline = System.currentTimeMillis() + 5000;
        while (!session.scheduleSequencer.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assertions.assertTrue(session.scheduleSequencer.isEmpty(),
            "sorter must be empty after cancelConnection — orphaned scheduleFuture entries " +
            "leave requestWorkTracker entries and TrafficStreamLimiter slots unreleased");
    }
}
