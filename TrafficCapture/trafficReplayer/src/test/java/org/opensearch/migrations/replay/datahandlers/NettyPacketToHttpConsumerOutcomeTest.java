package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer.PacketSendOutcome;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WrapWithNettyLeakDetection(repetitions = 1)
class NettyPacketToHttpConsumerOutcomeTest extends InstrumentationTest {
    @Test
    void ioWriteFailureReturnsTypedNoResponse() throws Exception {
        var requestContext = rootContext.getTestConnectionRequestContext(
            "typed-io-write-failure",
            0
        );
        try (var fixture = new NettyPacketToHttpConsumerTestFixture(requestContext)) {
            var write = fixture.newWritePromise();
            when(fixture.channel.writeAndFlush(any())).thenReturn(write);
            var packet = Unpooled.wrappedBuffer(new byte[] { 1 });
            var send = fixture.sendPacket(packet);

            write.setFailure(new IOException("connection reset"));

            var outcome = Assertions.assertInstanceOf(
                PacketSendOutcome.NoTargetResponseObtained.class,
                send.get(NettyPacketToHttpConsumerTestFixture.RESPONSE_TIMEOUT)
            );
            Assertions.assertTrue(outcome.reason().contains(IOException.class.getName()));
            Assertions.assertTrue(outcome.reason().contains("connection reset"));
            Assertions.assertEquals(1, fixture.firstWriteCount.get());
            releaseIfOwned(packet);
        }
    }

    @Test
    void unexpectedAsynchronousWriteFailureRemainsExceptional() throws Exception {
        var requestContext = rootContext.getTestConnectionRequestContext(
            "unexpected-write-failure",
            0
        );
        try (var fixture = new NettyPacketToHttpConsumerTestFixture(requestContext)) {
            var write = fixture.newWritePromise();
            when(fixture.channel.writeAndFlush(any())).thenReturn(write);
            var packet = Unpooled.wrappedBuffer(new byte[] { 1 });
            var send = fixture.sendPacket(packet);
            var invariantFailure = new IllegalStateException(
                "write invariant failed"
            );

            write.setFailure(invariantFailure);

            var failure = Assertions.assertThrows(
                ExecutionException.class,
                () -> send.get(NettyPacketToHttpConsumerTestFixture.RESPONSE_TIMEOUT)
            );
            Assertions.assertSame(invariantFailure, failure.getCause());
            Assertions.assertEquals(1, fixture.firstWriteCount.get());
            releaseIfOwned(packet);
        }
    }

    private static void releaseIfOwned(io.netty.buffer.ByteBuf packet) {
        if (packet.refCnt() > 0) {
            packet.release();
        }
    }
}
