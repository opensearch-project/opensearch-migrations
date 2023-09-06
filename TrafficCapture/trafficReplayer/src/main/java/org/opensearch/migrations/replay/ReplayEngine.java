package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

@Slf4j
public class ReplayEngine {
    RequestSenderOrchestrator networkSendOrchestrator;


    public ReplayEngine(RequestSenderOrchestrator networkSendOrchestrator) {
        this.networkSendOrchestrator = networkSendOrchestrator;
    }

    public void closeConnection(UniqueRequestKey requestKey, Instant timestamp) {
        networkSendOrchestrator.scheduleClose(requestKey, timestamp);
    }

    public DiagnosticTrackableCompletableFuture<String, Void> close() {
        return networkSendOrchestrator.clientConnectionPool.stopGroup();
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueRequestKey requestKey, Instant start, Instant end, int numPackets, Stream<ByteBuf> packets) {
        var interval = numPackets > 1 ? Duration.between(start, end).dividedBy(numPackets-1) : Duration.ZERO;
        var sendResult = networkSendOrchestrator.scheduleRequest(requestKey, start, interval, packets);
        return sendResult;
    }

    public int getNumConnectionsCreated() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsCreated();
    }

    public int getNumConnectionsClosed() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsClosed();
    }
}
