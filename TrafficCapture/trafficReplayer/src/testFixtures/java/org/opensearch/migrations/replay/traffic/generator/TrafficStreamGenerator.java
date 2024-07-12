package org.opensearch.migrations.replay.traffic.generator;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.InMemoryConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class TrafficStreamGenerator {
    public static final int MAX_COMMANDS_IN_CONNECTION = 256;

    public static InMemoryConnectionCaptureFactory buildSerializerFactory(int bufferSize, Runnable onClosedCallback) {
        return new InMemoryConnectionCaptureFactory("TEST_NODE_ID", bufferSize, onClosedCallback);
    }

    private static byte nextPrintable(int i) {
        final char firstChar = ' ';
        final byte lastChar = '~';
        var r = (byte) (i % (lastChar - firstChar));
        return (byte) ((r < 0) ? (lastChar + r) : (byte) (r + firstChar));
    }

    static ByteBuf makeSequentialByteBuf(int offset, int size) {
        var bb = Unpooled.buffer(size);
        final var b = nextPrintable(offset);
        for (int i = 0; i < size; ++i) {
            // bb.writeByte((i+offset)%255);
            bb.writeByte(b);
        }
        return bb;
    }

    public static TrafficStream[] makeTrafficStream(
        int bufferSize,
        int interactionOffset,
        AtomicInteger uniqueIdCounter,
        List<ObservationDirective> directives,
        TestContext rootContext
    ) throws Exception {
        var connectionFactory = buildSerializerFactory(bufferSize, () -> {});
        var tsk = PojoTrafficStreamKeyAndContext.build(
            "n",
            "test_" + uniqueIdCounter.incrementAndGet(),
            0,
            rootContext::createTrafficStreamContextForTest
        );
        var offloader = connectionFactory.createOffloader(rootContext.createChannelContext(tsk));
        for (var directive : directives) {
            serializeEvent(offloader, interactionOffset++, directive, Instant.EPOCH);
        }
        offloader.addCloseEvent(Instant.EPOCH);
        offloader.flushCommitAndResetStream(true).get();
        return connectionFactory.getRecordedTrafficStreamsStream().toArray(TrafficStream[]::new);
    }

    private static void serializeEvent(
        IChannelConnectionCaptureSerializer offloader,
        int offset,
        ObservationDirective directive,
        Instant timestamp
    ) throws IOException {
        switch (directive.offloaderCommandType) {
            case Read:
                offloader.addReadEvent(timestamp, makeSequentialByteBuf(offset, directive.size));
                return;
            case EndOfMessage:
                offloader.commitEndOfHttpMessageIndicator(timestamp);
                return;
            case Write:
                offloader.addWriteEvent(timestamp, makeSequentialByteBuf(offset + 3, directive.size));
                return;
            case Flush:
                offloader.flushCommitAndResetStream(false);
                return;
            case DropRequest:
                offloader.cancelCaptureForCurrentRequest(timestamp);
                return;
            default:
                throw new IllegalStateException("Unknown directive type: " + directive.offloaderCommandType);
        }
    }

}
