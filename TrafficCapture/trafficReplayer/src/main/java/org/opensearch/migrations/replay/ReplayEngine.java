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
import java.time.Instant;
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

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueRequestKey requestKey, Instant start, Instant end,
                    Stream<ByteBuf> packets) {
        var sendResult = networkSendOrchestrator.scheduleRequest(requestKey, start, end, packets);
        return sendResult;
    }

    public int getNumConnectionsCreated() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsCreated();
    }

    public int getNumConnectionsClosed() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsClosed();
    }
}
