package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datatypes.IndexedChannelInteraction;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.replay.util.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * This class is responsible for managing the BufferedFlowController, which is responsible for releasing
 * backpressure on the traffic source so that this class can schedule those requests to run on a
 * RequestSenderOrchestrator at the appropriate time.  This class uses a TimeShifter, the current time,
 * progress of tasks, and periods of inactivity, to move determine
 * from the current time, what the frontier time value should be for the traffic source
 */
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
    public ReplayEngine(
        RequestSenderOrchestrator networkSendOrchestrator,
        BufferedFlowController contentTimeController,
        TimeShifter timeShifter
    ) {
        this.networkSendOrchestrator = networkSendOrchestrator;
        this.contentTimeController = contentTimeController;
        this.timeShifter = timeShifter;
        this.totalCountOfScheduledTasksOutstanding = new AtomicLong();
        this.lastCompletedSourceTimeEpochMs = new AtomicLong(0);
        this.lastIdleUpdatedTimestampEpochMs = new AtomicLong(0);
        // this is gross, but really useful. Grab a thread out of the clientConnectionPool's event loop
        // and run a daemon to update the contentTimeController if there isn't any work that will be doing that
        var bufferPeriodMs = getUpdatePeriodMs();
        updateContentTimeControllerScheduledFuture = networkSendOrchestrator.clientConnectionPool.eventLoopGroup.next()
            .scheduleAtFixedRate(
                this::updateContentTimeControllerWhenIdling,
                bufferPeriodMs,
                bufferPeriodMs,
                TIME_UNIT_MILLIS
            );
    }

    private long getUpdatePeriodMs() {
        var bufferPeriodMs = contentTimeController.getBufferTimeWindow()
            .dividedBy(BACKPRESSURE_UPDATE_FREQUENCY)
            .toMillis();
        if (bufferPeriodMs == 0) {
            throw new IllegalStateException(
                "Buffer window time is too small, make it at least "
                    + BACKPRESSURE_UPDATE_FREQUENCY
                    + " "
                    + TIME_UNIT_MILLIS.name()
            );
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
        var lastUpdatedTimeEpochMs = Math.max(
            lastCompletedSourceTimeEpochMs.get(),
            lastIdleUpdatedTimestampEpochMs.get()
        );
        var maxSkipTimeEpochMs = lastUpdatedTimeEpochMs + (long) (getUpdatePeriodMs() * this.timeShifter
            .maxRateMultiplier());
        lastIdleUpdatedTimestampEpochMs.set(Math.min(currentSourceTimeEpochMs, maxSkipTimeEpochMs));
        contentTimeController.stopReadsPast(Instant.ofEpochMilli(lastIdleUpdatedTimestampEpochMs.get()));
    }

    // See the comment on totalCountOfScheduledTasksOutstanding. We could do this on a per-thread basis and
    // join the results all via `networkSendOrchestrator.clientConnectionPool.eventLoopGroup`
    public boolean isWorkOutstanding() {
        return totalCountOfScheduledTasksOutstanding.get() > 0;
    }

    private <T> TrackedFuture<String, T> hookWorkFinishingUpdates(
        TrackedFuture<String, T> future,
        Instant timestamp,
        Object stringableKey,
        String taskDescription
    ) {
        return future.map(
            f -> f.whenComplete((v, t) -> Utils.setIfLater(lastCompletedSourceTimeEpochMs, timestamp.toEpochMilli()))
                .whenComplete((v, t) -> {
                    var newCount = totalCountOfScheduledTasksOutstanding.decrementAndGet();
                    log.atInfo()
                        .setMessage(
                            () -> "Scheduled task '"
                                + taskDescription
                                + "' finished ("
                                + stringableKey
                                + ") decremented tasksOutstanding to "
                                + newCount
                        )
                        .log();
                })
                .whenComplete((v, t) -> contentTimeController.stopReadsPast(timestamp))
                .whenComplete(
                    (v, t) -> log.atDebug()
                        .setMessage(
                            () -> "work finished and used timestamp="
                                + timestamp
                                + " to update contentTimeController (tasksOutstanding="
                                + totalCountOfScheduledTasksOutstanding.get()
                                + ")"
                        )
                        .log()
                ),
            () -> "Updating fields for callers to poll progress and updating backpressure"
        );
    }

    private static void logStartOfWork(Object stringableKey, long newCount, Instant start, String label) {
        log.atInfo()
            .setMessage(
                () -> "Scheduling '"
                    + label
                    + "' ("
                    + stringableKey
                    + ") to run at "
                    + start
                    + " incremented tasksOutstanding to "
                    + newCount
            )
            .log();
    }

    public <T> TrackedFuture<String, T> scheduleTransformationWork(
        IReplayContexts.IReplayerHttpTransactionContext requestCtx,
        Instant originalStart,
        Supplier<TrackedFuture<String, T>> task
    ) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        final String label = "processing";
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        logStartOfWork(requestCtx, newCount, start, label);
        var result = networkSendOrchestrator.scheduleWork(
            requestCtx,
            start.minus(EXPECTED_TRANSFORMATION_DURATION),
            task
        );
        return hookWorkFinishingUpdates(result, originalStart, requestCtx, label);
    }

    public TrackedFuture<String, AggregatedRawResponse> scheduleRequest(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant originalStart,
        Instant originalEnd,
        int numPackets,
        Stream<ByteBuf> packets
    ) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        final String label = "request";
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        var end = timeShifter.transformSourceTimeToRealTime(originalEnd);
        var interval = numPackets > 1 ? Duration.between(start, end).dividedBy(numPackets - 1L) : Duration.ZERO;
        var requestKey = ctx.getReplayerRequestKey();
        logStartOfWork(requestKey, newCount, start, label);

        log.atDebug()
            .setMessage(
                () -> "Scheduling request for "
                    + ctx
                    + " to run from ["
                    + start
                    + ", "
                    + end
                    + " with an interval of "
                    + interval
                    + " for "
                    + numPackets
                    + " packets"
            )
            .log();
        var sendResult = networkSendOrchestrator.scheduleRequest(requestKey, ctx, start, interval, packets);
        return hookWorkFinishingUpdates(sendResult, originalStart, requestKey, label);
    }

    public TrackedFuture<String, Void> closeConnection(
        int channelInteractionNum,
        IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber,
        Instant timestamp
    ) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        final String label = "close";
        var atTime = timeShifter.transformSourceTimeToRealTime(timestamp);
        var channelKey = ctx.getChannelKey();
        logStartOfWork(new IndexedChannelInteraction(channelKey, channelInteractionNum), newCount, atTime, label);
        var future = networkSendOrchestrator.scheduleClose(ctx, channelSessionNumber, channelInteractionNum, atTime);
        return hookWorkFinishingUpdates(future, timestamp, channelKey, label);
    }

    public void setFirstTimestamp(Instant firstPacketTimestamp) {
        timeShifter.setFirstTimestamp(firstPacketTimestamp);
    }
}
