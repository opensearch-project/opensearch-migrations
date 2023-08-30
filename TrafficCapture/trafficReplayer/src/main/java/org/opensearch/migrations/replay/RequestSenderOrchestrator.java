package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.time.Instant;
import java.util.stream.Stream;

@Slf4j
public class RequestSenderOrchestrator {
    private final int maxRetriesForNewConnection;
    public final ClientConnectionPool clientConnectionPool;

    public RequestSenderOrchestrator(ClientConnectionPool clientConnectionPool, int maxRetriesForNewConnection) {
        this.maxRetriesForNewConnection = maxRetriesForNewConnection;
        this.clientConnectionPool = clientConnectionPool;
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueRequestKey requestKey, Instant start, Instant end, Stream<ByteBuf> packets) {
        var nettySender = create(requestKey);
        return sendAllData(nettySender, packets);
    }

    private static DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    sendAllData(NettyPacketToHttpConsumer packetHandler, Stream<ByteBuf> packets) {
        DiagnosticTrackableCompletableFuture<String, TransformedOutputAndResult<TransformedPackets>> transformationCompleteFuture;
        var logLabel = packetHandler.getClass().getSimpleName();
        packets.forEach(packetData-> {
            log.atDebug().setMessage(()->logLabel + " sending " + packetData.readableBytes() +
                    " bytes to the packetHandler").log();
            var consumeFuture = packetHandler.consumeBytes(packetData);
            log.atDebug().setMessage(()->logLabel + " consumeFuture = " + consumeFuture).log();
        });
        log.atDebug().setMessage(()->logLabel + " done sending bytes, now finalizing the request").log();
        return packetHandler.finalizeRequest();
    }

    private NettyPacketToHttpConsumer create(UniqueRequestKey requestKey) {
        return new NettyPacketToHttpConsumer(clientConnectionPool.get(requestKey, maxRetriesForNewConnection),
                requestKey.toString());
    }

}
