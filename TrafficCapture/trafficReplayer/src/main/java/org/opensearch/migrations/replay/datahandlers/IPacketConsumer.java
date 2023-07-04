package org.opensearch.migrations.replay.datahandlers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

/**
 * This class consumes arrays of bytes or ByteBufs, potentially asynchronously,
 * whose completion is signaled via the CompletableFuture that is returned.
 */
public interface IPacketConsumer {

    default DiagnosticTrackableCompletableFuture<String,Void> consumeBytes(byte[] nextRequestPacket) {
        var bb = Unpooled.wrappedBuffer(nextRequestPacket).retain();
        var rval = consumeBytes(bb);
        bb.release();
        return rval;
    }

    DiagnosticTrackableCompletableFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket);
}
