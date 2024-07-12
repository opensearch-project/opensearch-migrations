package org.opensearch.migrations.replay.datahandlers;

import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.util.TrackedFuture;

import io.netty.buffer.ByteBuf;

public class TransformedPacketReceiver implements IPacketFinalizingConsumer<TransformedPackets> {

    public final TransformedPackets packets = new TransformedPackets();

    @Override
    public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
        packets.add(nextRequestPacket);
        return TrackedFuture.Factory.completedFuture(null, () -> "TransformedPacketReceiver.consume...");
    }

    @Override
    public TrackedFuture<String, TransformedPackets> finalizeRequest() {
        return TrackedFuture.Factory.completedFuture(packets, () -> "TransformedPacketReceiver.finalize...");
    }
}
