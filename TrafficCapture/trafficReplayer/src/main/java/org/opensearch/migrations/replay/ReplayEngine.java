package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.IndexedChannelInteraction;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.utils.TrackedFuture;

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
    // Heartbeat: response status code counters (reset each heartbeat)
    private final java.util.concurrent.ConcurrentHashMap<Integer, AtomicLong> responseCodeCounters =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final org.slf4j.Logger heartbeatLogger =
        org.slf4j.LoggerFactory.getLogger("ReplayHeartbeat");

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
        updateContentTimeControllerScheduledFuture = networkSendOrchestrator.scheduleAtFixedRate(
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
        if (currentSourceTimeOp.isEmpty()) {
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
                    log.atDebug().setMessage("Scheduled task '{}' finished ({}) decremented tasksOutstanding to {}")
                        .addArgument(taskDescription)
                        .addArgument(stringableKey)
                        .addArgument(newCount)
                        .log();
                })
                .whenComplete((v, t) -> contentTimeController.stopReadsPast(timestamp))
                .whenComplete((v, t) -> log.atDebug()
                    .setMessage("work finished and used timestamp={} " +
                        "to update contentTimeController (tasksOutstanding={})")
                    .addArgument(timestamp)
                    .addArgument(totalCountOfScheduledTasksOutstanding::get)
                    .log()
                ),
            () -> "Updating fields for callers to poll progress and updating backpressure"
        );
    }

    private static void logStartOfWork(Object stringableKey, long newCount, Instant start, String label) {
        log.atDebug().setMessage("Scheduling '{}' ({}) to run at {} incremented tasksOutstanding to {}")
            .addArgument(label)
            .addArgument(stringableKey)
            .addArgument(start)
            .addArgument(newCount)
            .log();
    }

    public <T> TrackedFuture<String, T> scheduleRequest(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant originalStart,
        Instant originalEnd,
        int numPackets,
        ByteBufList packets,
        RequestSenderOrchestrator.RetryVisitor<T> retryVisitor
    ) {
        return scheduleRequest(ctx, originalStart, originalEnd, numPackets, packets, retryVisitor, null);
    }

    public <T> TrackedFuture<String, T> scheduleRequest(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant originalStart,
        Instant originalEnd,
        int numPackets,
        ByteBufList packets,
        RequestSenderOrchestrator.RetryVisitor<T> retryVisitor,
        Duration quiescentDurationForRequest
    ) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        final String label = "request";
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        // Apply quiescent delay relative to the time-shifted start (not wall-clock now),
        // so the delay is consistent regardless of when the request is processed
        if (quiescentDurationForRequest != null) {
            var quiescentUntil = start.plus(quiescentDurationForRequest);
            log.atInfo().setMessage("Applying quiescent delay: shifting start from {} to {} for {}")
                .addArgument(start).addArgument(quiescentUntil).addArgument(ctx).log();
            start = quiescentUntil;
        }
        var end = timeShifter.transformSourceTimeToRealTime(originalEnd);
        var interval = numPackets > 1 ? Duration.between(start, end).dividedBy(numPackets - 1L) : Duration.ZERO;
        var requestKey = ctx.getReplayerRequestKey();
        logStartOfWork(requestKey, newCount, start, label);

        log.atDebug().setMessage("Scheduling request for {} to run from [{}, {}] with an interval of {} for {} packets")
            .addArgument(ctx)
            .addArgument(start)
            .addArgument(end)
            .addArgument(interval)
            .addArgument(numPackets)
            .log();
        var result = networkSendOrchestrator.scheduleRequest(requestKey, ctx, start, interval, packets, retryVisitor);
        return hookWorkFinishingUpdates(result, originalStart, requestKey, label);
    }

    /**
     * Schedules a request where transformation is deferred until the connection's sequencer is ready.
     * This keeps signatures (e.g. SigV4) fresh by running the transform just before sending,
     * rather than eagerly when the request is first processed.
     */
    public <T> TrackedFuture<String, T> scheduleDeferredTransformAndSendRequest(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant originalStart,
        Instant originalEnd,
        Supplier<TrackedFuture<String, TransformedOutputAndResult<ByteBufList>>> transformSupplier,
        java.util.function.Function<TransformedOutputAndResult<ByteBufList>,
            RequestSenderOrchestrator.RetryVisitor<T>> retryVisitorFactory,
        Duration quiescentDurationForRequest
    ) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        final String label = "deferred-transform-request";
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        if (quiescentDurationForRequest != null) {
            var quiescentUntil = start.plus(quiescentDurationForRequest);
            log.atInfo().setMessage("Applying quiescent delay: shifting start from {} to {} for {}")
                .addArgument(start).addArgument(quiescentUntil).addArgument(ctx).log();
            start = quiescentUntil;
        }
        var end = timeShifter.transformSourceTimeToRealTime(originalEnd);
        var requestKey = ctx.getReplayerRequestKey();
        logStartOfWork(requestKey, newCount, start, label);

        var result = networkSendOrchestrator.scheduleDeferredTransformAndSendRequest(
            requestKey, ctx, start, end, transformSupplier, retryVisitorFactory
        );
        return hookWorkFinishingUpdates(result, originalStart, requestKey, label);
    }

    /**
     * Immediately cancels a connection due to a traffic source reader interruption.
     * Unlike {@link #closeConnection}, this bypasses the OnlineRadixSorter and time-shifting —
     * the channel is closed directly and the session is marked cancelled to prevent reconnection.
     */
    public TrackedFuture<String, Void> cancelConnection(
        IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber
    ) {
        var newCount = totalCountOfScheduledTasksOutstanding.incrementAndGet();
        var future = networkSendOrchestrator.cancelConnection(ctx, channelSessionNumber);
        return hookWorkFinishingUpdates(future, Instant.now(), ctx.getChannelKey(), "cancel");
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

    /** Record a target response status code for heartbeat reporting. */
    public void recordTargetResponseCode(int statusCode) {
        responseCodeCounters.computeIfAbsent(statusCode, k -> new AtomicLong()).incrementAndGet();
    }

    /** Emit a periodic heartbeat log summarizing the replay engine state. */
    public void logHeartbeat() {
        var sb = new StringBuilder();
        sb.append("tasksOutstanding=").append(totalCountOfScheduledTasksOutstanding.get());

        // Scheduling lag: how far wall clock is ahead of source time
        var sourceTimeOp = timeShifter.transformRealTimeToSourceTime(Instant.now());
        var lastCompletedMs = lastCompletedSourceTimeEpochMs.get();
        if (sourceTimeOp.isPresent() && lastCompletedMs > 0) {
            var currentSourceTime = sourceTimeOp.get();
            var lastCompleted = Instant.ofEpochMilli(lastCompletedMs);
            var lag = Duration.between(lastCompleted, currentSourceTime);
            sb.append(" schedulingLag=").append(org.opensearch.migrations.Utils.formatDurationInSeconds(lag));
            sb.append(" lastCompletedSourceTime=").append(lastCompleted);
        }

        sb.append(" bufferWindow=").append(org.opensearch.migrations.Utils.formatDurationInSeconds(contentTimeController.getBufferTimeWindow()));

        // Response codes since last heartbeat
        sb.append(" targetResponses={");
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        responseCodeCounters.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .forEach(e -> {
                var count = e.getValue().getAndSet(0);
                if (count > 0) {
                    if (!first.getAndSet(false)) sb.append(", ");
                    sb.append(e.getKey()).append("=").append(count);
                }
            });
        sb.append("}");

        heartbeatLogger.atInfo().setMessage("{}").addArgument(sb).log();
    }

}
