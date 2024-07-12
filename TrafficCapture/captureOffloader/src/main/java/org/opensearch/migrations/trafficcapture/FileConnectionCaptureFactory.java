package org.opensearch.migrations.trafficcapture;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

/**
 * Reference implementation of a TrafficStream protobuf-encoded sink.
 * TrafficStreams are dumped to individual files that are named according to the TrafficStream id.
 *
 * @deprecated - This class is NOT meant to be used for production.
 */
@Slf4j
@Deprecated(since = "0.1", forRemoval = false)
public class FileConnectionCaptureFactory implements IConnectionCaptureFactory<Void> {
    private final BiFunction<String, Integer, FileOutputStream> outputStreamCreator;
    private String nodeId;
    private final int bufferSize;

    public FileConnectionCaptureFactory(
        String nodeId,
        int bufferSize,
        BiFunction<String, Integer, FileOutputStream> outputStreamCreator
    ) {
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
                throw Lombok.sneakyThrow(e);
            }
        });
    }

    public FileConnectionCaptureFactory(String nodeId, String path, int bufferSize) {
        this(nodeId, bufferSize, Paths.get(path));
    }

    @AllArgsConstructor
    class StreamManager extends OrderedStreamLifecyleManager<Void> {
        String connectionId;

        @Override
        public CodedOutputStreamAndByteBufferWrapper createStream() {
            return new CodedOutputStreamAndByteBufferWrapper(bufferSize);
        }

        @Override
        public CompletableFuture<Void> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
            if (!(outputStreamHolder instanceof CodedOutputStreamAndByteBufferWrapper)) {
                throw new IllegalArgumentException(
                    "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
                );
            }
            var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
            return CompletableFuture.runAsync(() -> {
                try {
                    FileOutputStream fs = outputStreamCreator.apply(connectionId, index);
                    var bb = osh.getByteBuffer();
                    byte[] filledBytes = Arrays.copyOfRange(bb.array(), 0, bb.position());
                    fs.write(filledBytes);
                    fs.flush();
                    log.warn(
                        "NOT removing the CodedOutputStream from the WeakHashMap, which is a memory leak.  Doing this until the system knows when to properly flush buffers"
                    );
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            }).thenApply(v -> null);
        }
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(IConnectionContext ctx) {
        final var connectionId = ctx.getConnectionId();
        return new StreamChannelConnectionCaptureSerializer<>(nodeId, connectionId, new StreamManager(connectionId));
    }
}
