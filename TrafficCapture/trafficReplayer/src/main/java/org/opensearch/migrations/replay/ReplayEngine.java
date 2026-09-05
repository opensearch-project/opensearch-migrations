package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayWorkId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController.WorkToken;
import org.opensearch.migrations.replay.lifecycle.ReplayReadGate;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
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
    public static final Duration EXPECTED_TRANSFORMATION_DURATION = Duration.ofSeconds(1);
    private final RequestSenderOrchestrator networkSendOrchestrator;
    private final BufferedFlowController contentTimeController;
    private final ReplayProgressController progressController;
    private final TimeShifter timeShifter;
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
        this(
            networkSendOrchestrator,
            contentTimeController,
            timeShifter,
            new ReplayProgressController(
                Runnable::run,
                new ReplayReadGate(contentTimeController.getBufferTimeWindow(), contentTimeController)
            )
        );
    }

    public ReplayEngine(
        RequestSenderOrchestrator networkSendOrchestrator,
        BufferedFlowController contentTimeController,
        TimeShifter timeShifter,
        ReplayProgressController progressController
    ) {
        this.networkSendOrchestrator = networkSendOrchestrator;
        this.contentTimeController = contentTimeController;
        this.timeShifter = timeShifter;
        this.progressController = progressController;
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
        var currentSourceTimeOp = timeShifter.transformRealTimeToSourceTime(Instant.now());
        if (currentSourceTimeOp.isEmpty()) {
            // do nothing - the traffic source shouldn't be blocking initially.
            // Leave it manage its own initialization since we don't have any better information about what a
            // start time might be yet.
            return;
        }
        progressController.advanceIdlePartitions(currentSourceTimeOp.get());
    }

    public boolean isWorkOutstanding() {
        return progressController.isWorkOutstanding();
    }

    public CompletionStage<Void> whenQuiescent() {
        return progressController.whenQuiescent();
    }

    public WorkToken admitWork(
        SourcePartitionKey partition,
        ReplayWorkId workId,
        Instant sourceTime
    ) {
        return progressController.admit(partition, workId, sourceTime).toCompletableFuture().join();
    }

    public <T> TrackedFuture<String, T> scheduleRequestLifecycle(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant originalStart,
        Instant originalEnd,
        AsyncPermitPool permitPool,
        Supplier<TrackedFuture<String, TransformedOutputAndResult<ByteBufListProducer>>> preparation,
        Function<
            TransformedOutputAndResult<ByteBufListProducer>,
            RequestSenderOrchestrator.RetryVisitor<T>
        > retryVisitorFactory,
        Function<HttpRequestTransformationStatus, T> filteredResultFactory,
        Duration quiescentDurationForRequest
    ) {
        var start = timeShifter.transformSourceTimeToRealTime(originalStart);
        if (quiescentDurationForRequest != null) {
            start = start.plus(quiescentDurationForRequest);
            log.atInfo().setMessage("Applying quiescent delay through {} for {}")
                .addArgument(start)
                .addArgument(ctx)
                .log();
        }
        var end = timeShifter.transformSourceTimeToRealTime(originalEnd);
        var requestKey = ctx.getReplayerRequestKey();
        return networkSendOrchestrator.scheduleRequestLifecycle(
            requestKey,
            ctx,
            start.minus(EXPECTED_TRANSFORMATION_DURATION),
            start,
            end,
            permitPool,
            preparation,
            retryVisitorFactory,
            filteredResultFactory
        );
    }

    public RequestSenderOrchestrator.TransactionRuntime transactionRuntime(
        IReplayContexts.IReplayerHttpTransactionContext context
    ) {
        return networkSendOrchestrator.transactionRuntime(
            context.getReplayerRequestKey(),
            context.getChannelKeyContext()
        );
    }

    public CompletionStage<Void> observeRunwayLost(
        ConnectionSessionKey sessionKey,
        ReplayTransaction.RunwayLossReason reason
    ) {
        return networkSendOrchestrator.observeRunwayLost(sessionKey, reason);
    }

    public CompletionStage<Void> observeAllRunwaysLost(ReplayTransaction.RunwayLossReason reason) {
        return networkSendOrchestrator.observeAllRunwaysLost(reason);
    }

    public CompletionStage<Void> shutdownConnections(CancellationException cause) {
        updateContentTimeControllerScheduledFuture.cancel(false);
        return networkSendOrchestrator.shutdownActors(cause);
    }

    /**
     * Immediately aborts a connection actor due to a traffic source reader interruption.
     */
    public TrackedFuture<String, Void> cancelConnection(
        IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber
    ) {
        return networkSendOrchestrator.abortActor(
            ctx,
            channelSessionNumber,
            AbortReason.SOURCE_REASSIGNMENT,
            new java.util.concurrent.CancellationException(
                "Session cancelled due to source reassignment for " + ctx.getConnectionId()
            )
        );
    }

    public TrackedFuture<String, SessionOutcome> closeConnection(
        IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber,
        Instant timestamp
    ) {
        var atTime = timeShifter.transformSourceTimeToRealTime(timestamp);
        return networkSendOrchestrator.scheduleActorClose(ctx, channelSessionNumber, atTime);
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
        var progress = progressController.currentSnapshot();
        sb.append("tasksOutstanding=").append(progress.outstandingWork());
        sb.append(" assignedPartitions=").append(progress.assignedPartitions());
        if (!progress.settledWatermark().equals(Instant.MIN)) {
            sb.append(" settledSourceTime=").append(progress.settledWatermark());
        }

        // Scheduling lag: how far wall clock is ahead of source time
        var sourceTimeOp = timeShifter.transformRealTimeToSourceTime(Instant.now());
        if (sourceTimeOp.isPresent() && !progress.settledWatermark().equals(Instant.MIN)) {
            var currentSourceTime = sourceTimeOp.get();
            var lag = Duration.between(progress.settledWatermark(), currentSourceTime);
            sb.append(" schedulingLag=").append(org.opensearch.migrations.Utils.formatDurationInSeconds(lag));
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
