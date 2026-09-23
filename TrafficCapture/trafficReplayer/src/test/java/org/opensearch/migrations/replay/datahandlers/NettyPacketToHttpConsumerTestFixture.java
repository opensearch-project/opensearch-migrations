package org.opensearch.migrations.replay.datahandlers;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: ConnectionReplaySession IReplayContexts NettyPacketToHttpConsumer . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer.PacketSendOutcome;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class NettyPacketToHttpConsumerTestFixture implements AutoCloseable {
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    final AtomicInteger firstWriteCount = new AtomicInteger();
    final NioEventLoopGroup eventLoopGroup;
    final EventLoop eventLoop;
    final Channel channel = mock(Channel.class);
    final ChannelPipeline pipeline = mock(ChannelPipeline.class);
    final ChannelConfig config = mock(ChannelConfig.class);
    final DefaultChannelPromise connect;
    final DefaultChannelPromise close;
    final ConnectionReplaySession session;
    final NettyPacketToHttpConsumer consumer;

    NettyPacketToHttpConsumerTestFixture(
        IReplayContexts.IReplayerHttpTransactionContext requestContext
    ) throws Exception {
        eventLoopGroup = new NioEventLoopGroup(
            1,
            new DefaultThreadFactory("netty-write-boundary-test")
        );
        eventLoop = eventLoopGroup.next();
        connect = new DefaultChannelPromise(channel, eventLoop);
        close = new DefaultChannelPromise(channel, eventLoop);
        when(channel.isActive()).thenReturn(true);
        when(channel.pipeline()).thenReturn(pipeline);
        when(channel.config()).thenReturn(config);
        when(channel.closeFuture()).thenReturn(close);
        when(pipeline.last()).thenReturn(mock(SslHandler.class));
        when(config.isAutoRead()).thenReturn(false);
        connect.setSuccess();
        session = new ConnectionReplaySession(
            eventLoop,
            requestContext.getChannelKeyContext(),
            (ignoredEventLoop, ignoredContext) ->
                TextTrackedFuture.completedFuture(connect, () -> "mocked target channel")
        );
        consumer = new NettyPacketToHttpConsumer(
            session,
            requestContext,
            RESPONSE_TIMEOUT,
            firstWriteCount::incrementAndGet
        );
        consumer.activeChannelFuture.get(RESPONSE_TIMEOUT);
    }

    DefaultChannelPromise newWritePromise() {
        return new DefaultChannelPromise(channel, eventLoop);
    }

    TrackedFuture<String, PacketSendOutcome> sendPacket(ByteBuf packet)
        throws Exception {
        return eventLoop.submit(() -> consumer.sendPacket(packet)).sync().getNow();
    }

    @Override
    public void close() throws Exception {
        var cancellation = new CancellationException("test fixture closed");
        eventLoop.submit(() -> consumer.abort(cancellation)).sync();
        session.retireMetrics();
        eventLoopGroup.shutdownGracefully().sync();
    }
}

*/
// REBUILD-LIMBO-END(G10)