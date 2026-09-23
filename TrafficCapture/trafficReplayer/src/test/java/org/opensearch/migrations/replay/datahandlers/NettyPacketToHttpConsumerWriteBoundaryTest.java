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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer.PacketSendOutcome;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WrapWithNettyLeakDetection(repetitions = 1)
class NettyPacketToHttpConsumerWriteBoundaryTest extends InstrumentationTest {
    @Test
    void firstWriteIsReportedAfterSubmissionReturnsWithoutWaitingForPromise() throws Exception {
        var requestContext = rootContext.getTestConnectionRequestContext(
            "first-write-accepted",
            0
        );
        try (var fixture = new NettyPacketToHttpConsumerTestFixture(requestContext)) {
            var write = fixture.newWritePromise();
            var firstWriteCountInsideSubmission = new AtomicInteger(-1);
            when(fixture.channel.writeAndFlush(any())).thenAnswer(ignored -> {
                firstWriteCountInsideSubmission.set(fixture.firstWriteCount.get());
                return write;
            });
            var packet = Unpooled.wrappedBuffer(new byte[] { 1 });

            var send = fixture.sendPacket(packet);

            Assertions.assertEquals(
                0,
                firstWriteCountInsideSubmission.get(),
                "the callback must not run inside writeAndFlush"
            );
            Assertions.assertEquals(1, fixture.firstWriteCount.get());
            Assertions.assertFalse(write.isDone());
            Assertions.assertFalse(send.future.isDone());

            write.setSuccess();
            Assertions.assertInstanceOf(
                PacketSendOutcome.PacketSubmitted.class,
                send.get(NettyPacketToHttpConsumerTestFixture.RESPONSE_TIMEOUT)
            );
            releaseIfOwned(packet);
        }
    }

    @Test
    void firstWriteIsReportedOnlyOnceAcrossRequestPackets() throws Exception {
        var requestContext = rootContext.getTestConnectionRequestContext(
            "first-write-two-packets",
            0
        );
        try (var fixture = new NettyPacketToHttpConsumerTestFixture(requestContext)) {
            var writes = new AtomicInteger();
            var firstWrite = fixture.newWritePromise();
            var secondWrite = fixture.newWritePromise();
            when(fixture.channel.writeAndFlush(any())).thenAnswer(ignored ->
                writes.getAndIncrement() == 0 ? firstWrite : secondWrite
            );
            var firstPacket = Unpooled.wrappedBuffer(new byte[] { 1 });
            var secondPacket = Unpooled.wrappedBuffer(new byte[] { 2 });

            var firstSend = fixture.sendPacket(firstPacket);
            var secondSend = fixture.sendPacket(secondPacket);

            Assertions.assertEquals(1, fixture.firstWriteCount.get());
            Assertions.assertEquals(1, writes.get());
            firstWrite.setSuccess();
            await(() -> writes.get() == 2);
            Assertions.assertEquals(1, fixture.firstWriteCount.get());
            secondWrite.setSuccess();
            Assertions.assertInstanceOf(
                PacketSendOutcome.PacketSubmitted.class,
                firstSend.get(NettyPacketToHttpConsumerTestFixture.RESPONSE_TIMEOUT)
            );
            Assertions.assertInstanceOf(
                PacketSendOutcome.PacketSubmitted.class,
                secondSend.get(NettyPacketToHttpConsumerTestFixture.RESPONSE_TIMEOUT)
            );
            Assertions.assertEquals(
                1,
                fixture.firstWriteCount.get(),
                "both packet completions must observe one request-scoped milestone"
            );
            releaseIfOwned(firstPacket);
            releaseIfOwned(secondPacket);
        }
    }

    @Test
    void synchronousWriteRejectionDoesNotReportFirstWriteAndReleasesPacket()
        throws Exception {
        var requestContext = rootContext.getTestConnectionRequestContext(
            "first-write-rejected",
            0
        );
        try (var fixture = new NettyPacketToHttpConsumerTestFixture(requestContext)) {
            var rejection = new RejectedExecutionException("write submission rejected");
            when(fixture.channel.writeAndFlush(any())).thenThrow(rejection);
            var packet = Unpooled.wrappedBuffer(new byte[] { 1 });

            var failure = Assertions.assertThrows(
                ExecutionException.class,
                () -> fixture.sendPacket(packet).get(
                    NettyPacketToHttpConsumerTestFixture.RESPONSE_TIMEOUT
                )
            );

            Assertions.assertSame(rejection, failure.getCause());
            Assertions.assertEquals(0, fixture.firstWriteCount.get());
            Assertions.assertEquals(0, packet.refCnt());
        }
    }

    private static void releaseIfOwned(io.netty.buffer.ByteBuf packet) {
        if (packet.refCnt() > 0) {
            packet.release();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition)
        throws InterruptedException {
        var deadline = System.nanoTime()
            + NettyPacketToHttpConsumerTestFixture.RESPONSE_TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        Assertions.assertTrue(condition.getAsBoolean(), "condition timed out");
    }
}

*/
// REBUILD-LIMBO-END(G10)