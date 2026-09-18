package org.opensearch.migrations.replay.kafka;

import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the channel-close stage itself represents the null-channel cleanup path.
 */
class SessionCloseCallbackTest extends InstrumentationTest {

    private NioEventLoopGroup eventLoopGroup;

    @BeforeEach
    void setUp() {
        eventLoopGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("test"));
    }

    @AfterEach
    void tearDown() {
        eventLoopGroup.shutdownGracefully();
    }

    @Test
    void nullChannelCloseCompletesItsLifecycleStage() throws Exception {
        var channelKeyCtx = mock(IReplayContexts.IChannelKeyContext.class);
        when(channelKeyCtx.getConnectionId()).thenReturn("test-conn");

        // Create a session with no channel ever opened (cachedChannel stays null).
        var session = new ConnectionReplaySession(
            eventLoopGroup.next(),
            channelKeyCtx,
            (el, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no-op channel factory"),
            0
        );

        // Use ClientConnectionPool.closeChannelForSession to trigger the close path
        var pool = new ClientConnectionPool(
            (el, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no-op"),
            "test-pool",
            1
        );

        try {
            var closeFuture = pool.closeChannelForSession(session);
            Assertions.assertNull(closeFuture.get());
            Assertions.assertTrue(closeFuture.future.isDone());
        } finally {
            pool.shutdownNow().get();
        }
    }
}
