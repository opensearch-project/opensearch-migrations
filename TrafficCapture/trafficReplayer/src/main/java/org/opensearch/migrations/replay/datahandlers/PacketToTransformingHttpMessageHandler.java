package org.opensearch.migrations.replay.datahandlers;

import lombok.AllArgsConstructor;
import org.opensearch.migrations.replay.AggregatedRawResponse;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

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

    public interface ByteTransformer {
        void consumeBytes(byte[] nextRequestPacket) throws IOException;

        /**
         * To be called once consumeBytes() has been called with all of the data from a request.
         * Once this is called, the transformed contents of the full Http message will be returned
         * as a stream.
         * @return
         * @throws IOException
         */
         SizeAndInputStream getFullyTransformedBytes() throws IOException;
    }

    @Override
    public void consumeBytes(byte[] nextRequestPacket) throws InvalidHttpStateException, IOException {
        packetCount++;
        transformer.consumeBytes(nextRequestPacket);
    }

    @Override
    public void finalizeRequest(Consumer<AggregatedRawResponse> onResponseFinishedCallback)
            throws InvalidHttpStateException, IOException {
        try (var sizeAndOutputStream = transformer.getFullyTransformedBytes()) {
            int remainingBytes = sizeAndOutputStream.size;
            for (int packetsRemaining=packetCount; ; --packetsRemaining) {
                byte[] nextBuffer;
                if (remainingBytes <= 0) {
                    break;
                } else if (packetsRemaining <= 1) {
                    nextBuffer = sizeAndOutputStream.inputStream.readAllBytes();
                } else {
                    var nextChunkSize = Math.max(1, sizeAndOutputStream.size / packetCount);
                    nextBuffer = sizeAndOutputStream.inputStream.readNBytes(nextChunkSize);
                }
                httpHandler.consumeBytes(nextBuffer);
                remainingBytes -= nextBuffer.length;
            }
        }
        httpHandler.finalizeRequest(onResponseFinishedCallback);
    }
}
