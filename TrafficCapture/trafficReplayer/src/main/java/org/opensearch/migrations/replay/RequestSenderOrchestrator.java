package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opensearch.migrations.NettyFutureBinders;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ChannelTask;
import org.opensearch.migrations.replay.datatypes.ChannelTaskType;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.datatypes.IndexedChannelInteraction;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.RefSafeHolder;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * This class deals with scheduling different HTTP connection/request activities on a Netty Event Loop.
 * There are 4 public methods for this class.  scheduleAtFixedRate serves as a utility function and the
 * other 3 schedule methods.  scheduleWork handles any preparatory work that may need to be performed
 * (like transformation).  scheduleRequest will send the request and wait for the response, retrying
 * as necessary (with the same pacing, though it should probably be as fast as possible for retries - TODO).
 * scheduleClose will close the connection, if still open, used to send requests for the specified channel.<br><br>
 *
 * Notice that if the channel doesn't exist or isn't active when sending any request, a new one will be
 * created.  That channel (a socket connection to the server) is managed by theClientConnectionPool that's
 * passed into the constructor.  The pool itself will create a connection (Channel/ChannelFuture) via a
 * static factory method.  That connection is ready to hand off to packet consumer that's created from
 * the IPacketConsumer factory passed to the constructor.  Of course, the connection may be reused by multiple
 * IPacketConsumer objects (multiple requests on one connection) OR there could be multiple retries with new
 * connections for one request.  So the coupling is actually between the IPacketConsumer, which is for a single
 * request, and the ConnectionReplaySession, which can recreate (reconnect) a channel if it hasn't already or
 * if its previously created one is no longer functional.<br><br>
 *
 *
 */
@Slf4j
public class RequestSenderOrchestrator {

    private final ClientConnectionPool clientConnectionPool;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory;

    /**
     * Notice that the two arguments need to be in agreement with each other.  The clientConnectionPool will need to
     * be able to create/return ConnectionReplaySession objects with Channels (or, to be more exact, ChannelFutures
     * that resolve Channels) that can be utilized by the IPacketFinalizingConsumer objects.  For example, it TLS
     * is being used, either the clientConnectionPool will be responsible for configuring the channel with handlers
     * to do that or that functionality will need to be provided by the factory/packet consumer.
     * @param clientConnectionPool
     * @param packetConsumerFactory
     */
    public RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory
    ) {
        this(clientConnectionPool, Duration.ofMillis(100), Duration.ofSeconds(300), packetConsumerFactory);
    }

    public RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory
    ) {
        this.clientConnectionPool = clientConnectionPool;
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.packetConsumerFactory = packetConsumerFactory;
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable,
                                                  long initialDelay,
                                                  long delay,
                                                  TimeUnit timeUnit) {
        return clientConnectionPool.scheduleAtFixedRate(runnable, initialDelay, delay, timeUnit);
    }

    public <T> TrackedFuture<String, T> scheduleWork(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant timestamp,
        Supplier<TrackedFuture<String, T>> task
    ) {
        var connectionSession = clientConnectionPool.getCachedSession(
            ctx.getChannelKeyContext(),
            ctx.getReplayerRequestKey().sourceRequestIndexSessionIdentifier
        );
        log.atDebug().setMessage("Scheduling work for {} at time {}")
            .addArgument(ctx::getConnectionId)
            .addArgument(timestamp)
            .log();
        var scheduledContext = ctx.createScheduledContext(timestamp);
        // This method doesn't use the scheduling that scheduleRequest and scheduleClose use because
        // doing work associated with a connection is considered to be preprocessing work independent
        // of the underlying network connection itself, so it's fair to be able to do this without
        // first needing to wait for a connection to succeed.
        //
        // This means that this method might run transformation work "out-of-order" from the natural
        // ordering of the requests (defined by their original captured order). However, the final
        // order will be preserved once they're sent since sending requires the channelInteractionIndex,
        // which is the caller's responsibility to track and pass. This method doesn't need it to
        // schedule work to happen on the channel's thread at some point in the future.
        //
        // Making them more independent means that the work item being enqueued is lighter-weight and
        // less likely to cause a connection timeout.
        return bindNettyScheduleToCompletableFuture(connectionSession.eventLoop, timestamp)
            .getDeferredFutureThroughHandle((nullValue, scheduleFailure) -> {
                scheduledContext.close();
                if (scheduleFailure == null) {
                    return task.get();
                } else {
                    return TextTrackedFuture.failedFuture(scheduleFailure, () -> "netty scheduling failure");
                }
            }, () -> "The scheduled callback is running work for " + ctx);
    }

    public enum RetryDirective {
        DONE, RETRY
    }

    @AllArgsConstructor
    public static class DeterminedTransformedResponse<T> {
        RetryDirective directive;
        T value;
    }

    public interface RetryVisitor<T> {
        /**
         * Return null to continue trying according to
         * @param arr
         * @return
         */
        TrackedFuture<String,DeterminedTransformedResponse<T>>
        visit(ByteBuf requestBytes, AggregatedRawResponse arr, Throwable t);
    }

    public <T> TrackedFuture<String, T> scheduleRequest(
        UniqueReplayerRequestKey requestKey,
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant start,
        Duration interval,
        ByteBufList packets,
        RetryVisitor<T> visitor
    ) {
        var sessionNumber = requestKey.sourceRequestIndexSessionIdentifier;
        var channelInteractionNum = requestKey.getReplayerRequestIndex();
        var generation = requestKey.trafficStreamKey.getSourceGeneration();
        // TODO: Separate socket connection from the first bytes sent.
        // Ideally, we would match the relative timestamps of when connections were being initiated
        // as well as the period between connection and the first bytes sent. However, this code is a
        // bit too cavalier. It should be tightened at some point by adding a first packet that is empty.
        // Thankfully, given the trickiness of this class, that would be something that should be tracked
        // upstream and should be handled transparently by this class.
        return submitUnorderedWorkToEventLoop(
            ctx.getLogicalEnclosingScope(),
            sessionNumber,
            channelInteractionNum,
            generation,
            connectionReplaySession -> scheduleSendRequestOnConnectionReplaySession(
                ctx,
                connectionReplaySession,
                start,
                interval,
                packets,
                visitor
            )
        );
    }

    public TrackedFuture<String, Void> scheduleClose(
        IReplayContexts.IChannelKeyContext ctx,
        int sessionNumber,
        int channelInteractionNum,
        Instant timestamp
    ) {
        var channelKey = ctx.getChannelKey();
        var channelInteraction = new IndexedChannelInteraction(channelKey, channelInteractionNum);
        log.atDebug().setMessage("Scheduling CLOSE for {} at time {}")
            .addArgument(channelInteraction)
            .addArgument(timestamp)
            .log();
        return submitUnorderedWorkToEventLoop(
            ctx,
            sessionNumber,
            channelInteractionNum,
            connectionReplaySession -> scheduleCloseOnConnectionReplaySession(
                ctx,
                connectionReplaySession,
                timestamp,
                sessionNumber,
                channelInteractionNum,
                channelInteraction
            )
        );
    }

    private TrackedFuture<String, Void> bindNettyScheduleToCompletableFuture(EventLoop eventLoop, Instant timestamp) {
        return NettyFutureBinders.bindNettyScheduleToCompletableFuture(eventLoop, getDelayFromNowMs(timestamp));
    }

    private TextTrackedFuture<Void> bindNettyScheduleToCompletableFuture(
        EventLoop eventLoop,
        Instant timestamp,
        TrackedFuture<String, Void> existingFuture
    ) {
        var delayMs = getDelayFromNowMs(timestamp);
        NettyFutureBinders.bindNettyScheduleToCompletableFuture(eventLoop, delayMs, existingFuture.future);
        return new TextTrackedFuture<>(
            existingFuture.future,
            "scheduling to run next send at " + timestamp + " in " + delayMs + "ms"
        );
    }

    private CompletableFuture<Void> bindNettyScheduleToCompletableFuture(
        EventLoop eventLoop,
        Instant timestamp,
        CompletableFuture<Void> cf
    ) {
        return NettyFutureBinders.bindNettyScheduleToCompletableFuture(eventLoop, getDelayFromNowMs(timestamp), cf);
    }

    /**
     * This method will run the callback on the connection's dedicated thread such that all of the executions
     * of the callbacks sent for the connection are in the order defined by channelInteractionNumber, whose
     * values must be of the entire set of ints [0,N] for N work items (so, 0,1,2.  no gaps, no dups).  The
     * onSessionCallback task passed will be called only after all callbacks for previous channelInteractionNumbers
     * have been called.  This method isn't concerned with scheduling items to run at a specific time, that is
     * left up to the callback.
     */
    private <T> TrackedFuture<String, T> submitUnorderedWorkToEventLoop(
        IReplayContexts.IChannelKeyContext ctx,
        int sessionNumber,
        int channelInteractionNumber,
        Function<ConnectionReplaySession, TrackedFuture<String, T>> onSessionCallback
    ) {
        return submitUnorderedWorkToEventLoop(ctx, sessionNumber, channelInteractionNumber, 0, onSessionCallback);
    }

    private <T> TrackedFuture<String, T> submitUnorderedWorkToEventLoop(
        IReplayContexts.IChannelKeyContext ctx,
        int sessionNumber,
        int channelInteractionNumber,
        int generation,
        Function<ConnectionReplaySession, TrackedFuture<String, T>> onSessionCallback
    ) {
        final var replaySession = clientConnectionPool.getCachedSession(ctx, sessionNumber, generation);
        return NettyFutureBinders.bindNettySubmitToTrackableFuture(replaySession.eventLoop)
            .getDeferredFutureThroughHandle((v, t) -> {
                log.atTrace().setMessage("adding work item at slot {} for {} with {}")
                    .addArgument(channelInteractionNumber)
                    .addArgument(replaySession::getChannelKeyContext)
                    .addArgument(replaySession.scheduleSequencer)
                    .log();
                return replaySession.scheduleSequencer.addFutureForWork(
                    channelInteractionNumber,
                    f -> f.thenCompose(
                        voidValue -> onSessionCallback.apply(replaySession),
                        () -> "Work callback on replay session"
                    )
                );
            }, () -> "Waiting for sequencer to finish for slot " + channelInteractionNumber);
    }

    private <T> TrackedFuture<String, T> scheduleSendRequestOnConnectionReplaySession(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        ConnectionReplaySession connectionReplaySession,
        Instant startTime,
        Duration interval,
        ByteBufList packets,
        RetryVisitor<T> visitor
    ) {
        var eventLoop = connectionReplaySession.eventLoop;
        var scheduledContext = ctx.createScheduledContext(startTime);
        int channelInterationNum = ctx.getReplayerRequestKey().getSourceRequestIndex();
        var diagnosticCtx = new IndexedChannelInteraction(
            ctx.getLogicalEnclosingScope().getChannelKey(),
            channelInterationNum
        );
        packets.retain();
        return scheduleOnConnectionReplaySession(
            diagnosticCtx,
            connectionReplaySession,
            startTime,
            new ChannelTask<>(ChannelTaskType.TRANSMIT, trigger -> trigger.thenCompose(voidVal -> {
                scheduledContext.close();
                final Supplier<IPacketFinalizingConsumer<AggregatedRawResponse>> senderSupplier =
                    () -> packetConsumerFactory.apply(connectionReplaySession, ctx);
                return sendRequestWithRetries(senderSupplier, eventLoop, packets, startTime, initialRetryDelay,
                    interval, visitor);
            }, () -> "sending packets for request"))
        )
            .whenComplete((v,t) -> packets.release(), () -> "waiting for request to be sent to release ByteBufList");
    }

    private TrackedFuture<String, Void> scheduleCloseOnConnectionReplaySession(
        IReplayContexts.IChannelKeyContext ctx,
        ConnectionReplaySession connectionReplaySession,
        Instant timestamp,
        int connectionReplaySessionNum,
        int channelInteractionNum,
        IndexedChannelInteraction channelInteraction
    ) {
        var diagnosticCtx = new IndexedChannelInteraction(ctx.getChannelKey(), channelInteractionNum);
        return scheduleOnConnectionReplaySession(
            diagnosticCtx,
            connectionReplaySession,
            timestamp,
            new ChannelTask<>(ChannelTaskType.CLOSE, tf -> tf.whenComplete((v, t) -> {
                log.trace("Calling closeConnection at slot " + channelInteraction);
                clientConnectionPool.closeConnection(ctx, connectionReplaySessionNum);
            }, () -> "Close connection"))
        );
    }

    private <T> TrackedFuture<String, T> scheduleOnConnectionReplaySession(
        IndexedChannelInteraction channelInteraction,
        ConnectionReplaySession channelFutureAndRequestSchedule,
        Instant atTime,
        ChannelTask<T> task
    ) {
        log.atDebug().setMessage("{} scheduling {} at {}")
            .addArgument(channelInteraction)
            .addArgument(task.kind)
            .addArgument(atTime)
            .log();

        var schedule = channelFutureAndRequestSchedule.schedule;
        var eventLoop = channelFutureAndRequestSchedule.eventLoop;

        var wasEmpty = schedule.isEmpty();
        assert wasEmpty || !atTime.isBefore(schedule.peekFirstItem().startTime)
            : "Per-connection TrafficStream ordering should force a time ordering on incoming requests";
        var workPointTrigger = schedule.appendTaskTrigger(atTime, task.kind).scheduleFuture;
        var workFuture = task.getRunnable().apply(workPointTrigger);
        log.atTrace().setMessage("{} added a scheduled event at {}... {}")
            .addArgument(channelInteraction)
            .addArgument(atTime)
            .addArgument(schedule)
            .log();
        if (wasEmpty) {
            bindNettyScheduleToCompletableFuture(eventLoop, atTime, workPointTrigger.future);
        }

        workFuture.map(f -> f.whenComplete((v, t) -> {
            var itemStartTimeOfPopped = schedule.removeFirstItem();
            assert atTime.equals(itemStartTimeOfPopped)
                : "Expected to have popped the item to match the start time for the responseFuture that finished";
            log.atDebug().setMessage("{} responseFuture completed - checking {} for the next item to schedule")
                .addArgument(channelInteraction::toString)
                .addArgument(schedule)
                .log();
            Optional.ofNullable(schedule.peekFirstItem())
                .ifPresent(kvp -> bindNettyScheduleToCompletableFuture(eventLoop, kvp.startTime, kvp.scheduleFuture));
        }), () -> "");

        return workFuture;
    }

    private Instant now() {
        return Instant.now();
    }

    private Duration getDelayFromNowMs(Instant to) {
        return Duration.ofMillis(Math.max(0, Duration.between(now(), to).toMillis()));
    }

    private Duration doubleRetryDelayCapped(Duration d) {
        return Duration.ofMillis(Math.min(d.multipliedBy(2).toMillis(), maxRetryDelay.toMillis()));
    }

    private <T> TrackedFuture<String, T>
    sendRequestWithRetries(Supplier<IPacketFinalizingConsumer<AggregatedRawResponse>> senderSupplier,
                           EventLoop eventLoop,
                           ByteBufList byteBufList,
                           Instant referenceStartTime,
                           Duration nextRetryDelay,
                           Duration interval,
                           RetryVisitor<T> visitor)
    {
        if (eventLoop.isShuttingDown()) {
            return TextTrackedFuture.failedFuture(new IllegalStateException("EventLoop is shutting down"),
                () -> "sendRequestWithRetries is failing due to the pending shutdown of the EventLoop");
        }
        return sendPackets(senderSupplier.get(), eventLoop,
            byteBufList.streamUnretained().iterator(), referenceStartTime, interval, new AtomicInteger())
            .getDeferredFutureThroughHandle((response, t) -> {
                    try (var requestBytesHolder = RefSafeHolder.create(byteBufList.asCompositeByteBufRetained())) {
                        return visitor.visit(requestBytesHolder.get(), response, t);
                    }
                },
                () -> "checking response to determine if the request should be retried")
            .getDeferredFutureThroughHandle((dtr,t) -> {
                if (t != null) {
                    return TextTrackedFuture.failedFuture(t, () -> "failed future");
                }
                if (dtr.directive == RetryDirective.RETRY) {
                    var computedStartTime = referenceStartTime.plus(nextRetryDelay);
                    // Ensure retry is not scheduled in the past to prevent tight retry loops
                    // that monopolize event loop threads when referenceStartTime is far in the past
                    var now = now();
                    var newStartTime = computedStartTime.isBefore(now)
                        ? now.plus(nextRetryDelay)
                        : computedStartTime;
                    log.atDebug().setMessage("Making request scheduled at {}").addArgument(newStartTime).log();
                    var schedulingDelay = Duration.between(now(), newStartTime);
                    return NettyFutureBinders.bindNettyScheduleToCompletableFuture(
                        eventLoop, schedulingDelay)
                        .thenCompose(
                            v -> sendRequestWithRetries(senderSupplier, eventLoop, byteBufList, newStartTime,
                                doubleRetryDelayCapped(nextRetryDelay), interval, visitor),
                            () -> "retrying request with delay of " + schedulingDelay);
                } else {
                    return TextTrackedFuture.completedFuture(dtr.value,
                        () -> "done retrying and returning received response");
                }
            }, () -> "determining if the response must be retried or if it should be returned now");
    }

    private TrackedFuture<String, AggregatedRawResponse> sendPackets(
        IPacketFinalizingConsumer<AggregatedRawResponse> packetReceiver,
        EventLoop eventLoop,
        Iterator<ByteBuf> iterator,
        Instant referenceStartAt,
        Duration interval,
        AtomicInteger requestPacketCounter
    ) {
        final var oldCounter = requestPacketCounter.getAndIncrement();
        log.atTrace().setMessage("sendNextPartAndContinue: packetCounter={}").addArgument(oldCounter).log();
        assert iterator.hasNext() : "Should not have called this with no items to send";

        var consumeFuture = packetReceiver.consumeBytes(iterator.next().retainedDuplicate());
        if (iterator.hasNext()) {
            return consumeFuture.thenCompose(
                tf -> NettyFutureBinders.bindNettyScheduleToCompletableFuture(
                        eventLoop,
                        Duration.between(now(), referenceStartAt.plus(interval.multipliedBy(requestPacketCounter.get())))
                    )
                    .thenCompose(
                        v -> sendPackets(packetReceiver, eventLoop, iterator, referenceStartAt, interval, requestPacketCounter),
                        () -> "sending next packet"
                    ),
                () -> "recursing, once ready"
            );
        } else {
            return consumeFuture.getDeferredFutureThroughHandle(
                (v, t) -> packetReceiver.finalizeRequest(),
                () -> "finalizing, once ready"
            );
        }
    }
}
