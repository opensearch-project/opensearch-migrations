package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Reference implementation of a TrafficStream protobuf-encoded sink.
 * TrafficStreams are dumped to individual files that are named according to the TrafficStream id.
 *
 * @deprecated - This class is NOT meant to be used for production.
 */
@Slf4j
@Deprecated
public class FileConnectionCaptureFactory implements IConnectionCaptureFactory {
    private final BiFunction<String, Integer, FileOutputStream> outputStreamCreator;
    private String nodeId;
    private final int bufferSize;

    public FileConnectionCaptureFactory(String nodeId, int bufferSize,
                                        BiFunction<String, Integer, FileOutputStream> outputStreamCreator) {
        this.nodeId = nodeId;
        this.outputStreamCreator = outputStreamCreator;
        this.bufferSize = bufferSize;
    }

    public FileConnectionCaptureFactory(String nodeId, int bufferSize, Path rootPath) {
        this(nodeId, bufferSize, (id, n) -> {
            try {
                var filePath = rootPath.resolve(id + "_" + n.toString() + ".protocap");
                return new FileOutputStream(filePath.toString());
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public FileConnectionCaptureFactory(String nodeId, String path, int bufferSize) {
        this(nodeId, bufferSize, Paths.get(path));
    }

    private CompletableFuture closeHandler(String connectionId, ByteBuffer byteBuffer, CodedOutputStream codedOutputStream, int callCounter) {
        return CompletableFuture.runAsync(() -> {
            try {
                FileOutputStream fs = outputStreamCreator.apply(connectionId, callCounter);
                byte[] filledBytes = Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position());
                fs.write(filledBytes);
                fs.flush();
                log.warn("NOT removing the CodedOutputStream from the WeakHashMap, which is a memory leak.  Doing this until the system knows when to properly flush buffers");
                //codedStreamToFileStreamMap.remove(stream);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException {
        AtomicInteger supplierCallCounter = new AtomicInteger();
        // This array is only an indirection to work around Java's constraint that lambda values are final
        CompletableFuture[] singleAggregateCfRef = new CompletableFuture[1];
        singleAggregateCfRef[0] = CompletableFuture.completedFuture(null);
        WeakHashMap<CodedOutputStream, ByteBuffer> codedStreamToFileStreamMap = new WeakHashMap<>();
        return new StreamChannelConnectionCaptureSerializer(nodeId, connectionId,
            () -> {
                ByteBuffer bb = ByteBuffer.allocate(bufferSize);
                var cos = CodedOutputStream.newInstance(bb);
                codedStreamToFileStreamMap.put(cos, bb);
                return cos;
            },
            (codedOutputStream) -> {
                CompletableFuture cf = closeHandler(connectionId, codedStreamToFileStreamMap.get(codedOutputStream), codedOutputStream, supplierCallCounter.incrementAndGet());
                singleAggregateCfRef[0] = singleAggregateCfRef[0].isDone() ? cf : CompletableFuture.allOf(singleAggregateCfRef[0], cf);
                return singleAggregateCfRef[0];
            }
        );
    }
}
