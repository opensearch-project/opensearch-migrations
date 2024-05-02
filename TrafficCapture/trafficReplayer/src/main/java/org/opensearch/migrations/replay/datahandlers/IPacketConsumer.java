package org.opensearch.migrations.replay.datahandlers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.opensearch.migrations.replay.util.TrackedFuture;

/**
 * This class consumes arrays of bytes or ByteBufs, potentially asynchronously,
 * whose completion is signaled via the CompletableFuture that is returned.
 */
public interface IPacketConsumer {

    default TrackedFuture<String,Void> consumeBytes(byte[] nextRequestPacket) {
        var bb = Unpooled.wrappedBuffer(nextRequestPacket).retain();
        var rval = consumeBytes(bb);
        bb.release();
        return rval;
    }

    TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket);
}
