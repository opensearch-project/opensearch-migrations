package org.opensearch.migrations.replay.datahandlers;

import io.netty.buffer.ByteBuf;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

public class TransformedPacketReceiver implements IPacketFinalizingConsumer<TransformedPackets> {

    public final TransformedPackets packets = new TransformedPackets();

    @Override
    public DiagnosticTrackableCompletableFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
        packets.add(nextRequestPacket);
        return DiagnosticTrackableCompletableFuture.factory.completedFuture(null,
                ()->"TransformedPacketReceiver.consume...");
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String, TransformedPackets> finalizeRequest() {
        return DiagnosticTrackableCompletableFuture.factory.completedFuture(packets,
                ()->"TransformedPacketReceiver.finalize...");
    }
}
