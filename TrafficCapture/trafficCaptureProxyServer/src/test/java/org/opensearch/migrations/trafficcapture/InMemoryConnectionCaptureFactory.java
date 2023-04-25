package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

public class InMemoryConnectionCaptureFactory implements IConnectionCaptureFactory {

    @AllArgsConstructor
    public static class RecordedTrafficStream {
        public final byte[] data;
    }

    @Getter
    ConcurrentLinkedQueue<RecordedTrafficStream> recordedStreams = new ConcurrentLinkedQueue<>();

    public InMemoryConnectionCaptureFactory() {
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException {
        AtomicInteger supplierCallCounter = new AtomicInteger();
        WeakHashMap<CodedOutputStream, ByteArrayOutputStream> codedStreamToFileStreamMap = new WeakHashMap<>();
        return new StreamChannelConnectionCaptureSerializer(connectionId, () -> {
            var baos = new ByteArrayOutputStream();
            var cos = CodedOutputStream.newInstance(baos);
            codedStreamToFileStreamMap.put(cos, baos);
            return cos;
        }, (offloaderInput) -> CompletableFuture.runAsync(() -> {
            try {
                CodedOutputStream stream = offloaderInput.getCodedOutputStream();
                ByteArrayOutputStream baos = codedStreamToFileStreamMap.get(stream);
                baos.close();
                recordedStreams.add(new RecordedTrafficStream(baos.toByteArray()));
                codedStreamToFileStreamMap.remove(stream);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
    }
}
