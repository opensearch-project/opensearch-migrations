package org.opensearch.migrations.trafficcapture;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import com.google.protobuf.InvalidProtocolBufferException;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class InMemoryConnectionCaptureFactory implements IConnectionCaptureFactory<Void> {

    private final int bufferSize;
    private final String nodeId;

    @AllArgsConstructor
    public static class RecordedTrafficStream {
        public final byte[] data;
    }

    @Getter
    ConcurrentLinkedQueue<RecordedTrafficStream> recordedStreams = new ConcurrentLinkedQueue<>();
    Runnable onCaptureClosedCallback;

    public InMemoryConnectionCaptureFactory(String nodeId, int bufferSize, Runnable onCaptureClosedCallback) {
        this.bufferSize = bufferSize;
        this.nodeId = nodeId;
        this.onCaptureClosedCallback = onCaptureClosedCallback;
    }

    @AllArgsConstructor
    class StreamManager extends OrderedStreamLifecyleManager<Void> {

        @Override
        public CodedOutputStreamHolder createStream() {
            return new CodedOutputStreamAndByteBufferWrapper(bufferSize);
        }

        @Override
        protected CompletableFuture<Void> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
            if (!(outputStreamHolder instanceof CodedOutputStreamAndByteBufferWrapper)) {
                throw new IllegalArgumentException(
                    "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
                );
            }
            var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
            return CompletableFuture.runAsync(() -> {
                var bb = osh.getByteBuffer();
                byte[] filledBytes = Arrays.copyOfRange(bb.array(), 0, bb.position());
                recordedStreams.add(new RecordedTrafficStream(filledBytes));
            }).whenComplete((v, t) -> onCaptureClosedCallback.run()).thenApply(x -> null);
        }
    }

    @Override
    public IChannelConnectionCaptureSerializer<Void> createOffloader(IConnectionContext ctx) throws IOException {
        // This array is only an indirection to work around Java's constraint that lambda values are final
        return new StreamChannelConnectionCaptureSerializer<>(nodeId, ctx.getConnectionId(), new StreamManager());
    }

    public Stream<TrafficStream> getRecordedTrafficStreamsStream() {
        return recordedStreams.stream().map(rts -> {
            try {
                return TrafficStream.parseFrom(rts.data);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
