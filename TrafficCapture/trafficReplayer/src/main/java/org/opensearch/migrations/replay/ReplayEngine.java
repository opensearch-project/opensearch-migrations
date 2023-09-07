package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Slf4j
public class ReplayEngine {
    private final RequestSenderOrchestrator networkSendOrchestrator;
    private final BufferedTimeController contentTimeController;
    private final AtomicReference<Instant> lastCompletedTimestamp;
    private final TimeShifter timeShifter;

    public ReplayEngine(RequestSenderOrchestrator networkSendOrchestrator,
                        BufferedTimeController contentTimeController, TimeShifter timeShifter) {
        this.networkSendOrchestrator = networkSendOrchestrator;
        this.contentTimeController = contentTimeController;
        this.timeShifter = timeShifter;
        lastCompletedTimestamp = new AtomicReference<>(Instant.EPOCH);
    }

    public void closeConnection(UniqueRequestKey requestKey, Instant timestamp) {
        networkSendOrchestrator.scheduleClose(requestKey, timeShifter.transformSourceTimeToRealTime(timestamp))
                .map(f->f.whenComplete((v,t)->Utils.setIfLater(lastCompletedTimestamp, timestamp)),
                        ()->"updating ts due to close");
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueRequestKey requestKey, Instant originalStart, Instant originalEnd,
                    int numPackets, Stream<ByteBuf> packets) {
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        var end = timeShifter.transformSourceTimeToRealTime(originalEnd);
        var interval = numPackets > 1 ? Duration.between(start, end).dividedBy(numPackets-1) : Duration.ZERO;
        var sendResult = networkSendOrchestrator.scheduleRequest(requestKey, start, interval, packets);
        sendResult.map(f->f.whenComplete((v,t)->Utils.setIfLater(lastCompletedTimestamp, originalEnd)),
                ()->"updating ts due to close");
        return sendResult;
    }

    public DiagnosticTrackableCompletableFuture<String, Void> close() {
        return networkSendOrchestrator.clientConnectionPool.stopGroup();
    }

    public int getNumConnectionsCreated() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsCreated();
    }

    public int getNumConnectionsClosed() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsClosed();
    }
}
