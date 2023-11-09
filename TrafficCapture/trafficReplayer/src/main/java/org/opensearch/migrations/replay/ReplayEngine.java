package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.IndexedChannelInteraction;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class ReplayEngine {
    public static final int BACKPRESSURE_UPDATE_FREQUENCY = 8;
    public static final TimeUnit TIME_UNIT_MILLIS = TimeUnit.MILLISECONDS;
    public static final Duration EXPECTED_TRANSFORMATION_DURATION = Duration.ofSeconds(1);
    private final RequestSenderOrchestrator networkSendOrchestrator;
    private final BufferedFlowController contentTimeController;
    private final AtomicLong lastCompletedSourceTimeEpochMs;
    private final AtomicLong lastIdleUpdatedTimestampEpochMs;
    private final TimeShifter timeShifter;
    private static final MetricsLogger metricsLogger = new MetricsLogger("ReplayEngine");
    /**
     * If this proves to be a contention bottleneck, we can move to a scheme with ThreadLocals
     * and on a scheduled basis, submit work to each thread to find out if they're idle or not.
     */
    private final AtomicLong totalCountOfScheduledTasksOutstanding;
    ScheduledFuture<?> updateContentTimeControllerScheduledFuture;

    /**
     *
     * @param networkSendOrchestrator
     * @param contentTimeController
     * @param timeShifter
     */
    public ReplayEngine(RequestSenderOrchestrator networkSendOrchestrator,
                        BufferedFlowController contentTimeController,
                        TimeShifter timeShifter) {
        this.networkSendOrchestrator = networkSendOrchestrator;
        this.contentTimeController = contentTimeController;
        this.timeShifter = timeShifter;
        this.totalCountOfScheduledTasksOutstanding = new AtomicLong();
        this.lastCompletedSourceTimeEpochMs = new AtomicLong(0);
        this.lastIdleUpdatedTimestampEpochMs = new AtomicLong(0);
        // this is gross, but really useful.  Grab a thread out of the clientConnectionPool's event loop
        // and run a daemon to update the contentTimeController if there isn't any work that will be doing that
        var bufferPeriodMs = getUpdatePeriodMs();
        updateContentTimeControllerScheduledFuture =
                networkSendOrchestrator.clientConnectionPool.eventLoopGroup.next()
                        .scheduleAtFixedRate(this::updateContentTimeControllerWhenIdling,
                                bufferPeriodMs, bufferPeriodMs, TIME_UNIT_MILLIS);
    }

    private long getUpdatePeriodMs() {
        var bufferPeriodMs = contentTimeController.getBufferTimeWindow().dividedBy(BACKPRESSURE_UPDATE_FREQUENCY)
                .toMillis();
        if (bufferPeriodMs == 0) {
            throw new RuntimeException("Buffer window time is too small, make it at least " +
                    BACKPRESSURE_UPDATE_FREQUENCY + " " + TIME_UNIT_MILLIS.name());
        }
        return bufferPeriodMs;
    }

    private void updateContentTimeControllerWhenIdling() {
        if (isWorkOutstanding()) {
            return;
        }
        var currentSourceTimeOp = timeShifter.transformRealTimeToSourceTime(Instant.now());
        if (!currentSourceTimeOp.isPresent()) {
            // do nothing - the traffic source shouldn't be blocking initially.
            // Leave it manage its own initialization since we don't have any better information about what a
            // start time might be yet.
            return;
        }
        var currentSourceTimeEpochMs = currentSourceTimeOp.get().toEpochMilli();
        var lastUpdatedTimeEpochMs =
                Math.max(lastCompletedSourceTimeEpochMs.get(), lastIdleUpdatedTimestampEpochMs.get());
        var maxSkipTimeEpochMs =
                        lastUpdatedTimeEpochMs +
                        (long) (getUpdatePeriodMs()*this.timeShifter.maxRateMultiplier());
        lastIdleUpdatedTimestampEpochMs.set(Math.min(currentSourceTimeEpochMs, maxSkipTimeEpochMs));
        contentTimeController.stopReadsPast(Instant.ofEpochMilli(lastIdleUpdatedTimestampEpochMs.get()));
    }

    // See the comment on totalCountOfScheduledTasksOutstanding.  We could do this on a per-thread basis and
    // join the results all via `networkSendOrchestrator.clientConnectionPool.eventLoopGroup`;
    public boolean isWorkOutstanding() {
        return totalCountOfScheduledTasksOutstanding.get() > 1;
    }

    private <T> DiagnosticTrackableCompletableFuture<String, T>
    hookWorkFinishingUpdates(DiagnosticTrackableCompletableFuture<String, T> future, Instant timestamp,
                             Object stringableKey, String taskDescription) {
        return future.map(f->f
                        .whenComplete((v,t)->Utils.setIfLater(lastCompletedSourceTimeEpochMs, timestamp.toEpochMilli()))
                        .whenComplete((v,t)->{
                            var newCount = totalCountOfScheduledTasksOutstanding.decrementAndGet();
                            log.atInfo().setMessage(()->"Scheduled task '" + taskDescription + "' finished ("
                                    + stringableKey + ") decremented tasksOutstanding to "+newCount).log();
                        })
                        .whenComplete((v,t)->contentTimeController.stopReadsPast(timestamp))
                        .whenComplete((v,t)->log.atDebug().
                                setMessage(()->"work finished and used timestamp=" + timestamp +
                                        " to update contentTimeController (tasksOutstanding=" +
                                        totalCountOfScheduledTasksOutstanding.get() +")").log()),
                ()->"Updating fields for callers to poll progress and updating backpressure");
    }

    private static void logStartOfWork(Object stringableKey, long newCount, Instant start, String label) {
        log.atInfo().setMessage(()->"Scheduling '" + label + "' (" + stringableKey +
                ") to run at " + start + " incremented tasksOutstanding to "+ newCount).log();
    }

    public <T> DiagnosticTrackableCompletableFuture<String, T>
    scheduleTransformationWork(UniqueReplayerRequestKey requestKey, Instant originalStart,
                               Supplier<DiagnosticTrackableCompletableFuture<String,T>> task) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        final String label = "processing";
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        logStartOfWork(requestKey, newCount, start, label);
        var result = networkSendOrchestrator.scheduleWork(requestKey.trafficStreamKey,
                start.minus(EXPECTED_TRANSFORMATION_DURATION), task);
        return hookWorkFinishingUpdates(result, originalStart, requestKey, label);
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueReplayerRequestKey requestKey, Instant originalStart, Instant originalEnd,
                    int numPackets, Stream<ByteBuf> packets) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        final String label = "request";
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        var end = timeShifter.transformSourceTimeToRealTime(originalEnd);
        var interval = numPackets > 1 ? Duration.between(start, end).dividedBy(numPackets-1L) : Duration.ZERO;
        logStartOfWork(requestKey, newCount, start, label);
        metricsLogger.atSuccess()
                .addKeyValue("requestId", requestKey.toString())
                .addKeyValue("connectionId", requestKey.getTrafficStreamKey().getConnectionId())
                .addKeyValue("delayFromOriginalToScheduledStartInMs", Duration.between(originalStart, start).toMillis())
                .addKeyValue("scheduledStartTime", start.toString())
                .setMessage("Request scheduled to be sent").log();
        var sendResult = networkSendOrchestrator.scheduleRequest(requestKey, start, interval, packets);
        return hookWorkFinishingUpdates(sendResult, originalStart, requestKey, label);
    }

    public void closeConnection(ISourceTrafficChannelKey channelKey, int channelInteractionNum, Instant timestamp) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        final String label = "close";
        var atTime = timeShifter.transformSourceTimeToRealTime(timestamp);
        logStartOfWork(new IndexedChannelInteraction(channelKey, channelInteractionNum), newCount, atTime, label);
        var future = networkSendOrchestrator.scheduleClose(channelKey, channelInteractionNum, atTime);
        hookWorkFinishingUpdates(future, timestamp, channelKey, label);
    }

    public DiagnosticTrackableCompletableFuture<String, Void> closeConnectionsAndShutdown() {
        return networkSendOrchestrator.clientConnectionPool.closeConnectionsAndShutdown();
    }

    public int getNumConnectionsCreated() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsCreated();
    }

    public int getNumConnectionsClosed() {
        return networkSendOrchestrator.clientConnectionPool.getNumConnectionsClosed();
    }

    public void setFirstTimestamp(Instant firstPacketTimestamp) {
        timeShifter.setFirstTimestamp(firstPacketTimestamp);
    }
}
