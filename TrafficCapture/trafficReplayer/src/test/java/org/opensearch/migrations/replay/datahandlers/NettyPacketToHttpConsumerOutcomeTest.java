package org.opensearch.migrations.replay.datahandlers;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: InstrumentationTest . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer.PacketSendOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseKind;
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

            var transportFailure = new IOException("connection reset");
            write.setFailure(transportFailure);

            var outcome = Assertions.assertInstanceOf(
                PacketSendOutcome.NoTargetResponseObtained.class,
                send.get(NettyPacketToHttpConsumerTestFixture.RESPONSE_TIMEOUT)
            );
            Assertions.assertSame(transportFailure, outcome.cause());
            Assertions.assertEquals(
                NoTargetResponseKind.TRANSPORT_FAILURE,
                outcome.diagnostic().kind()
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

*/
// REBUILD-LIMBO-END(G10)