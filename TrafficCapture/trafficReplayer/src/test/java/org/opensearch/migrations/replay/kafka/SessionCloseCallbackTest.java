package org.opensearch.migrations.replay.kafka;

import java.util.concurrent.atomic.AtomicBoolean;

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
 * Test #7: Verify the onClose callback fires even when no TCP connection was ever opened
 * (null channel path in closeClientConnectionChannel).
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
    void globalOnSessionClose_firesForNullChannelSession() throws Exception {
        var onCloseFired = new AtomicBoolean(false);
        var channelKeyCtx = mock(IReplayContexts.IChannelKeyContext.class);
        when(channelKeyCtx.getConnectionId()).thenReturn("test-conn");

        // Create a session with no channel ever opened (cachedChannel stays null)
        // and an onClose callback that sets our flag
        var session = new ConnectionReplaySession(
            eventLoopGroup.next(),
            channelKeyCtx,
            (el, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no-op channel factory"),
            0,
            () -> onCloseFired.set(true)
        );

        // Use ClientConnectionPool.closeChannelForSession to trigger the close path
        var pool = new ClientConnectionPool(
            (el, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no-op"),
            "test-pool",
            1
        );

        // closeChannelForSession calls closeClientConnectionChannel which handles null channel
        var closeFuture = pool.closeChannelForSession(session);
        closeFuture.get(); // wait for completion

        Assertions.assertTrue(onCloseFired.get(),
            "onClose callback must fire even when no TCP connection was ever opened (null channel)");
    }
}
