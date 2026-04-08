package org.opensearch.migrations.replay.datahandlers;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;

public class TransformedPacketReceiver implements IPacketFinalizingConsumer<ByteBufListProducer> {

    public final ByteBufList packets = new ByteBufList();

    @Override
    public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
        packets.add(nextRequestPacket);
        return TrackedFuture.Factory.completedFuture(null, () -> "TransformedPacketReceiver.consume...");
    }

    @Override
    public TrackedFuture<String, ByteBufListProducer> finalizeRequest() {
        return TrackedFuture.Factory.completedFuture(
            ByteBufListProducer.of(packets), () -> "TransformedPacketReceiver.finalize...");
    }
}
