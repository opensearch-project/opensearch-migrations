package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class ReplayEngine {
    public static final int BACKPRESSURE_UPDATE_FREQUENCY = 8;
    public static final TimeUnit TIME_UNIT_MILLIS = TimeUnit.MILLISECONDS;
    public static final Duration EXPECTED_TRANSFORMATION_DURATION = Duration.ofSeconds(1);
    private final RequestSenderOrchestrator networkSendOrchestrator;
    private final BufferedTimeController contentTimeController;
    private final AtomicLong lastCompletedSourceTimeEpochMs;
    private final AtomicLong lastIdleUpdatedTimestampEpochMs;
    private final TimeShifter timeShifter;
    private final double maxSpeedupFactor;
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
     * @param maxSpeedupFactor - when we've been idle and the last value to stopReadsPast was much
     *                         different than the inverted realtime, it wouldn't make sense to have
     *                         the supplier start to load a huge amount of messages.  This factor
     *                         will throttle how much we can advance time every iteration if we're
     *                         behind our idealized time.
     */
    public ReplayEngine(RequestSenderOrchestrator networkSendOrchestrator,
                        BufferedTimeController contentTimeController,
                        TimeShifter timeShifter,
                        double maxSpeedupFactor) {
        this.networkSendOrchestrator = networkSendOrchestrator;
        this.contentTimeController = contentTimeController;
        this.timeShifter = timeShifter;
        this.maxSpeedupFactor = maxSpeedupFactor;
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
        var maxSkipTimeEpochMs = lastUpdatedTimeEpochMs + (long) (getUpdatePeriodMs()*this.maxSpeedupFactor);
        lastIdleUpdatedTimestampEpochMs.set(Math.min(currentSourceTimeEpochMs, maxSkipTimeEpochMs));
        contentTimeController.stopReadsPast(Instant.ofEpochMilli(lastIdleUpdatedTimestampEpochMs.get()));
    }

    // See the comment on totalCountOfScheduledTasksOutstanding.  We could do this on a per-thread basis and
    // join the results all via `networkSendOrchestrator.clientConnectionPool.eventLoopGroup`;
    public boolean isWorkOutstanding() {
        return totalCountOfScheduledTasksOutstanding.get() > 1;
    }

    private <T> DiagnosticTrackableCompletableFuture<String, T>
    hookWorkFinishingUpdates(DiagnosticTrackableCompletableFuture<String, T> future, Instant timestamp) {
        return future.map(f->f
                        .whenComplete((v,t)->Utils.setIfLater(lastCompletedSourceTimeEpochMs, timestamp.toEpochMilli()))
                        .whenComplete((v,t)->totalCountOfScheduledTasksOutstanding.decrementAndGet())
                        .whenComplete((v,t)->contentTimeController.stopReadsPast(timestamp))
                        .whenComplete((v,t)->log.atDebug().
                                setMessage(()->"work finished and used timestamp="+timestamp+" for updates " +
                                        "(tasksOutstanding=" + totalCountOfScheduledTasksOutstanding.get() +")").log()),
                ()->"Updating fields for callers to poll progress and updating backpressure");
    }

    public <T> DiagnosticTrackableCompletableFuture<String, T>
    scheduleTransformationWork(UniqueRequestKey requestKey, Instant originalStart, Instant originalEnd,
                               Supplier<DiagnosticTrackableCompletableFuture<String,T>> task) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        log.atDebug().setMessage(()->"scheduleWork: incremented tasksOutstanding to "+newCount).log();
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        var end = timeShifter.transformSourceTimeToRealTime(originalEnd);
        var result = networkSendOrchestrator.scheduleWork(requestKey, end.minus(EXPECTED_TRANSFORMATION_DURATION) ,task);
        return hookWorkFinishingUpdates(result, originalStart);
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueRequestKey requestKey, Instant originalStart, Instant originalEnd,
                    int numPackets, Stream<ByteBuf> packets) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        log.atDebug().setMessage(()->"scheduleRequest: incremented tasksOutstanding to "+newCount).log();
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        var end = timeShifter.transformSourceTimeToRealTime(originalEnd);
        var interval = numPackets > 1 ? Duration.between(start, end).dividedBy(numPackets-1) : Duration.ZERO;
        var sendResult = networkSendOrchestrator.scheduleRequest(requestKey, start, interval, packets);
        return hookWorkFinishingUpdates(sendResult, originalStart);
    }

    public void closeConnection(UniqueRequestKey requestKey, Instant timestamp) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        log.atDebug().setMessage(()->"scheduleClose: incremented tasksOutstanding to "+newCount).log();
        var future = networkSendOrchestrator
                .scheduleClose(requestKey, timeShifter.transformSourceTimeToRealTime(timestamp));
        hookWorkFinishingUpdates(future, timestamp);
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
