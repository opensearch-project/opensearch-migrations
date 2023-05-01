package org.opensearch.migrations.replay.datahandlers;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.AggregatedRawResponse;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

@Slf4j
public abstract class PacketToTransformingHttpMessageHandler implements IPacketToHttpHandler {
    private final IPacketToHttpHandler httpHandler;
    private final ByteTransformer transformer;
    private int packetCount;

    public PacketToTransformingHttpMessageHandler(IPacketToHttpHandler httpHandler, ByteTransformer transformer) {
        this.httpHandler = httpHandler;
        this.transformer = transformer;
    }

    @AllArgsConstructor
    public static class SizeAndInputStream implements Closeable {
        public final int size;
        public final InputStream inputStream;

        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }

    @Override
    public CompletableFuture<Void> consumeBytes(byte[] nextRequestPacket) {
        packetCount++;
        return transformer.addBytes(nextRequestPacket);
    }

    @Override
    public CompletableFuture<AggregatedRawResponse> finalizeRequest() {
        return transformer.getFullyTransformedBytes().thenCompose(sizeAndOutputStream -> {
            try {
                try (sizeAndOutputStream) {
                    int chunkSize = Math.max(1, sizeAndOutputStream.size / packetCount);
                    var chunkConsumerFuture = getAndConsumeNextChunk(httpHandler, sizeAndOutputStream.inputStream, chunkSize,
                            sizeAndOutputStream.size, packetCount);
                    return chunkConsumerFuture.thenCompose(v -> httpHandler.finalizeRequest());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static CompletableFuture<Void>
    getAndConsumeNextChunk(IPacketToHttpHandler httpHandler, InputStream stream,
                            int chunkSize, int bytesLeft, int packetsLeft) throws IOException {
        if (bytesLeft <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        var nextBuffer = getNextBytes(stream, chunkSize, bytesLeft, packetsLeft);
        final int bufferLength = nextBuffer.length;
        log.info("consuming "+nextBuffer.length+" bytes");
        return httpHandler.consumeBytes(nextBuffer)
                .thenCompose(v -> {
                    try {
                        log.info("getAndConsumeNextChunk has completed... recursing");
                        return getAndConsumeNextChunk(httpHandler, stream, chunkSize,
                                bytesLeft - bufferLength, packetsLeft - 1);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    final static byte[] EMPTY_ARRAY = new byte[0];
    private static byte[] getNextBytes(InputStream inputStream, int chunkSize,
                                int bytesLeft, int packetsLeft) throws IOException {
        if (bytesLeft <= 0) {
            return EMPTY_ARRAY;
        } else if (packetsLeft <= 1) {
            return inputStream.readAllBytes();
        } else {
            return inputStream.readNBytes(chunkSize);
        }
    }
}
