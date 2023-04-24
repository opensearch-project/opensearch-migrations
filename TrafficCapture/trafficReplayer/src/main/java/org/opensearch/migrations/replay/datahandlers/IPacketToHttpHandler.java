package org.opensearch.migrations.replay.datahandlers;

import org.opensearch.migrations.replay.AggregatedRawResponse;

import java.util.concurrent.CompletableFuture;

public interface IPacketToHttpHandler {

    CompletableFuture<Void> consumeBytes(byte[] nextRequestPacket);
    CompletableFuture<AggregatedRawResponse> finalizeRequest();
}
