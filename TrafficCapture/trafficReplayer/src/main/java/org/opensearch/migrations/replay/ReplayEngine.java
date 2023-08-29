package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.handler.ssl.SslContext;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.net.URI;
import java.time.Duration;
import java.util.stream.Stream;

@Slf4j
public class ReplayEngine {
    public final Duration resendDelay;
    RequestSenderOrchestrator networkSendOrchestrator;

    public ReplayEngine(RequestSenderOrchestrator networkSendOrchestrator, Duration resendDelay) {
        this.resendDelay = resendDelay;
        this.networkSendOrchestrator = networkSendOrchestrator;
    }

    public void closeConnection(String connId) {
        networkSendOrchestrator.clientConnectionPool.closeConnection(connId);
    }

    public DiagnosticTrackableCompletableFuture<String, Void> close() {
        return networkSendOrchestrator.clientConnectionPool.stopGroup();
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedTransformedResponse>
    scheduleRequest(UniqueRequestKey requestKey,
                    HttpRequestTransformationStatus transformationStatus,
                    Stream<ByteBuf> packets) {
        var nettySender = networkSendOrchestrator.create(requestKey);
        var sendResult = sendAllData(nettySender, packets);
        return sendResult.map(f->f.thenApply(aggregatedRawResponse ->
                        new AggregatedTransformedResponse(aggregatedRawResponse,
                                transformationStatus)),
                ()->"Combining original transformation status with sent result");
    }

    private static <R> DiagnosticTrackableCompletableFuture<String, R>
    sendAllData(IPacketFinalizingConsumer<R> packetHandler, Stream<ByteBuf> packets) {
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

    public int getNumConnectionsCreated() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsCreated();
    }

    public int getNumConnectionsClosed() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsClosed();
    }
}
