package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public interface ByteTransformer {
    CompletableFuture<Void> addBytes(byte[] nextRequestPacket);

    /**
     * To be called once consumeBytes() has been called with all of the data from a request.
     * Once this is called, the transformed contents of the full Http message will be returned
     * as a stream.
     *
     * @return
     * @throws IOException
     */
    CompletableFuture<PacketToTransformingHttpMessageHandler.SizeAndInputStream> getFullyTransformedBytes();
}
