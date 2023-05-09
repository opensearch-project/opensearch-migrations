package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class InMemoryConnectionCaptureFactory implements IConnectionCaptureFactory {

    @AllArgsConstructor
    public static class RecordedTrafficStream {
        public final byte[] data;
    }

    @Getter
    ConcurrentLinkedQueue<RecordedTrafficStream> recordedStreams = new ConcurrentLinkedQueue<>();

    public InMemoryConnectionCaptureFactory() {
    }

    private CompletableFuture closeHandler(ByteBuffer byteBuffer) {
        return CompletableFuture.runAsync(() -> {
            byte[] filledBytes = Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position());
            recordedStreams.add(new RecordedTrafficStream(filledBytes));
        });
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException {
        AtomicInteger supplierCallCounter = new AtomicInteger();
        AtomicReference<CompletableFuture> aggregateCf = new AtomicReference<>(CompletableFuture.completedFuture(null));
        WeakHashMap<CodedOutputStream, ByteBuffer> codedStreamToByteBufferMap = new WeakHashMap<>();
        return new StreamChannelConnectionCaptureSerializer(connectionId, 100, () -> {
            ByteBuffer bb = ByteBuffer.allocate(1024 * 1024);
            var cos = CodedOutputStream.newInstance(bb);
            codedStreamToByteBufferMap.put(cos, bb);
            return cos;
        }, (codedOutputStream) -> {
            CompletableFuture cf = closeHandler(codedStreamToByteBufferMap.get(codedOutputStream));
            codedStreamToByteBufferMap.remove(codedOutputStream);
            aggregateCf.set(aggregateCf.get().isDone() ? cf : CompletableFuture.allOf(aggregateCf.get(), cf));
            return aggregateCf.get();
        });
    }
}
