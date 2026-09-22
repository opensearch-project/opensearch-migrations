package org.opensearch.migrations.replay.datahandlers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WrapWithNettyLeakDetection(repetitions = 1)
class NettyPacketToHttpConsumerCancellationTest extends InstrumentationTest {
    @Test
    void abortBeforeChannelAcquisitionReturnsCancellationAsFinalizedValue()
        throws Exception {
        var eventLoopGroup = new NioEventLoopGroup(
            1,
            new DefaultThreadFactory("delayed-acquisition-abort")
        );
        try {
            var eventLoop = eventLoopGroup.next();
            var delayedAcquisition = new CompletableFuture<ChannelFuture>();
            var channel = mock(Channel.class);
            var connectFuture = new DefaultChannelPromise(channel, eventLoop);
            var closeFuture = new DefaultChannelPromise(channel, eventLoop);
            var closeCalls = new AtomicInteger();
            var firstWriteCount = new AtomicInteger();
            when(channel.isActive()).thenReturn(true);
            when(channel.closeFuture()).thenReturn(closeFuture);
            when(channel.close()).thenAnswer(ignored -> {
                closeCalls.incrementAndGet();
                closeFuture.trySuccess();
                return closeFuture;
            });
            connectFuture.setSuccess();
            var requestContext = rootContext.getTestConnectionRequestContext(
                "delayed-abort",
                0
            );
            var session = new ConnectionReplaySession(
                eventLoop,
                requestContext.getChannelKeyContext(),
                (ignoredEventLoop, ignoredContext) ->
                    new TextTrackedFuture<>(
                        delayedAcquisition,
                        "delayed target channel"
                    )
            );
            var consumer = new NettyPacketToHttpConsumer(
                session,
                requestContext,
                Duration.ofSeconds(30),
                firstWriteCount::incrementAndGet
            );
            var packet = Unpooled.buffer().writeBytes(
                "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8)
            );
            var send = eventLoop.submit(() -> consumer.consumeBytes(packet))
                .sync()
                .getNow();
            var cancellation = new CancellationException("source reassigned");

            eventLoop.submit(() -> consumer.abort(cancellation)).sync();
            Assertions.assertNull(
                session.cancelAndClose(cancellation).get(Duration.ofSeconds(5))
            );
            var sendFailure = Assertions.assertThrows(
                ExecutionException.class,
                () -> send.get(Duration.ofSeconds(5))
            );
            Assertions.assertSame(cancellation, sendFailure.getCause());
            Assertions.assertEquals(0, packet.refCnt());

            var finalization = eventLoop.submit(consumer::finalizeRequest)
                .sync()
                .getNow();
            var finalized = finalization.get(Duration.ofSeconds(5));
            Assertions.assertNull(finalized.getRawResponse());
            Assertions.assertSame(cancellation, finalized.getError());
            Assertions.assertEquals(0, firstWriteCount.get());

            delayedAcquisition.complete(connectFuture);
            await(() -> closeCalls.get() == 1);
            verify(channel, never()).writeAndFlush(any());
            session.retireMetrics();
        } finally {
            eventLoopGroup.shutdownGracefully().sync();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition)
        throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        Assertions.assertTrue(
            condition.getAsBoolean(),
            "condition did not become true before timeout"
        );
    }
}
