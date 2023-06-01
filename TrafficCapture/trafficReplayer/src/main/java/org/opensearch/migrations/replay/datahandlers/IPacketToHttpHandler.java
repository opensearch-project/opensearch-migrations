package org.opensearch.migrations.replay.datahandlers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.opensearch.migrations.replay.AggregatedRawResponse;

import java.util.concurrent.CompletableFuture;

public interface IPacketToHttpHandler {

    default CompletableFuture<Void> consumeBytes(byte[] nextRequestPacket) {
        var bb = Unpooled.wrappedBuffer(nextRequestPacket).retain();
        var rval = consumeBytes(bb);
        bb.release();
        return rval;
    }
    CompletableFuture<Void> consumeBytes(ByteBuf nextRequestPacket);
    CompletableFuture<AggregatedRawResponse> finalizeRequest();
}
