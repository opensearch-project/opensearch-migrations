package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ChannelTask;
import org.opensearch.migrations.replay.datatypes.ChannelTaskType;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.datatypes.IndexedChannelInteraction;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class RequestSenderOrchestrator {

    public static CompletableFuture<Void>
    bindNettyFutureToCompletableFuture(Future<?> nettyFuture, CompletableFuture<Void> cf) {
        nettyFuture.addListener(f -> {
            if (!f.isSuccess()) {
                cf.completeExceptionally(f.cause());
            } else {
                cf.complete(null);
            }
        });
        return cf;
    }

    public static CompletableFuture<Void>
    bindNettyFutureToCompletableFuture(Future<?> nettyFuture) {
        return bindNettyFutureToCompletableFuture(nettyFuture, new CompletableFuture<>());
    }

    public static DiagnosticTrackableCompletableFuture<String,Void>
    bindNettyFutureToTrackableFuture(Future<?> nettyFuture, String label) {
        return new StringTrackableCompletableFuture<>(bindNettyFutureToCompletableFuture(nettyFuture), label);
    }

    public static DiagnosticTrackableCompletableFuture<String,Void>
    bindNettyFutureToTrackableFuture(Future<?> nettyFuture, Supplier<String> labelProvider) {
        return new StringTrackableCompletableFuture<>(bindNettyFutureToCompletableFuture(nettyFuture), labelProvider);
    }

    public static DiagnosticTrackableCompletableFuture<String,Void>
    bindNettyFutureToTrackableFuture(Function<Runnable,Future<?>> nettyFutureGenerator, String label) {
        return bindNettyFutureToTrackableFuture(nettyFutureGenerator.apply(()->{}), label);
    }

    public static DiagnosticTrackableCompletableFuture<String,Void>
    bindNettySubmitToTrackableFuture(EventLoop eventLoop) {
        return bindNettyFutureToTrackableFuture(eventLoop::submit, "waiting for event loop submission");
    }

    public static DiagnosticTrackableCompletableFuture<String,Void>
    bindNettyScheduleToCompletableFuture(EventLoop eventLoop, Duration delay) {
        var delayMs = Math.max(0, delay.toMillis());
        return bindNettyFutureToTrackableFuture(eventLoop.schedule(()->{}, delayMs, TimeUnit.MILLISECONDS),
                "scheduling to run next send in " + delay);
    }

    private DiagnosticTrackableCompletableFuture<String,Void>
    bindNettyScheduleToCompletableFuture(EventLoop eventLoop, Instant timestamp) {
        return bindNettyScheduleToCompletableFuture(eventLoop, getDelayFromNowMs(timestamp));
    }

    public static CompletableFuture<Void>
    bindNettyScheduleToCompletableFuture(EventLoop eventLoop, Duration delay, CompletableFuture<Void> cf) {
        var delayMs = Math.max(0, delay.toMillis());
        return bindNettyFutureToCompletableFuture(eventLoop.schedule(()->{}, delayMs, TimeUnit.MILLISECONDS), cf);
    }

    private CompletableFuture<Void>
    bindNettyScheduleToCompletableFuture(EventLoop eventLoop, Instant timestamp, CompletableFuture<Void> cf) {
        return bindNettyScheduleToCompletableFuture(eventLoop, getDelayFromNowMs(timestamp), cf);
    }


    public void
    bindNettyFutureToTrackableFuture(EventLoop eventLoop,
                                     Instant timestamp,
                                     DiagnosticTrackableCompletableFuture<String, Void> existingFuture) {
        var delayMs = Math.max(0, getDelayFromNowMs(timestamp).toMillis());
        var scheduleFuture = eventLoop.schedule(()->{}, delayMs, TimeUnit.MILLISECONDS);
        new StringTrackableCompletableFuture<>(
                bindNettyFutureToCompletableFuture(scheduleFuture, existingFuture.future),
                "scheduling to run next send in " + timestamp);
    }


    public final ClientConnectionPool clientConnectionPool;

    public RequestSenderOrchestrator(ClientConnectionPool clientConnectionPool) {
        this.clientConnectionPool = clientConnectionPool;
    }

    public <T> DiagnosticTrackableCompletableFuture<String, T>
    scheduleWork(IReplayContexts.IReplayerHttpTransactionContext ctx, Instant timestamp,
                 Supplier<DiagnosticTrackableCompletableFuture<String,T>> task) {
        var connectionSession = clientConnectionPool.getCachedSession(ctx.getChannelKeyContext(),
                ctx.getReplayerRequestKey().sourceRequestIndexSessionIdentifier);
        log.atDebug().setMessage(()->"Scheduling work for "+ctx.getConnectionId()+" at time "+timestamp).log();
        var scheduledContext = ctx.createScheduledContext(timestamp);
        // This method doesn't use the scheduling that scheduleRequest and scheduleClose use because
        // doing work associated with a connection is considered to be preprocessing work independent
        // of the underlying network connection itself, so it's fair to be able to do this without
        // first needing to wait for a connection to succeed.
        //
        // This means that this method might run transformation work "out-of-order" from the natural
        // ordering of the requests (defined by their original captured order).  However, the final
        // order will be preserved once they're sent since sending requires the channelInteractionIndex,
        // which is the caller's responsibility to track and pass.  This method doesn't need it to
        // schedule work to happen on the channel's thread at some point in the future.
        //
        // Making them more independent means that the work item being enqueued is lighter-weight and
        // less likely to cause a connection timeout.
        return bindNettyScheduleToCompletableFuture(connectionSession.eventLoop, timestamp)
                .getDeferredFutureThroughHandle((nullValue,scheduleFailure)-> {
            scheduledContext.close();
            if (scheduleFailure == null) {
                return task.get();
            } else {
                return StringTrackableCompletableFuture.failedFuture(scheduleFailure, ()->"netty scheduling failure");
            }
        }, ()->"The scheduled callback is running");
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueReplayerRequestKey requestKey, IReplayContexts.IReplayerHttpTransactionContext ctx,
                    Instant start, Duration interval, Stream<ByteBuf> packets) {
        var sessionNumber = requestKey.sourceRequestIndexSessionIdentifier;
        var channelInteractionNum = requestKey.getReplayerRequestIndex();
        // TODO: Separate socket connection from the first bytes sent.
        // Ideally, we would match the relative timestamps of when connections were being initiated
        // as well as the period between connection and the first bytes sent.  However, this code is a
        // bit too cavalier.  It should be tightened at some point by adding a first packet that is empty.
        // Thankfully, given the trickiness of this class, that would be something that should be tracked
        // upstream and should be handled transparently by this class.
        return submitUnorderedWorkToEventLoop(ctx.getLogicalEnclosingScope(), sessionNumber, channelInteractionNum,
                connectionReplaySession -> scheduleSendRequestOnConnectionReplaySession(ctx,
                        connectionReplaySession, start, interval, packets));
    }

    public DiagnosticTrackableCompletableFuture<String,Void> scheduleClose(IReplayContexts.IChannelKeyContext ctx,
                                                                           int sessionNumber,
                                                                           int channelInteractionNum,
                                                                           Instant timestamp) {
        var channelKey = ctx.getChannelKey();
        var channelInteraction = new IndexedChannelInteraction(channelKey, channelInteractionNum);
        log.atDebug().setMessage(() -> "Scheduling CLOSE for " + channelInteraction + " at time " + timestamp).log();
        return submitUnorderedWorkToEventLoop(ctx, sessionNumber, channelInteractionNum,
                connectionReplaySession -> scheduleCloseOnConnectionReplaySession(ctx,
                        connectionReplaySession, timestamp, sessionNumber, channelInteractionNum, channelInteraction));
    }

    /**
     * This method will run the callback on the connection's dedicated thread such that all of the executions
     * of the callbacks sent for the connection are in the order defined by channelInteractionNumber, whose
     * values must be of the entire set of ints [0,N] for N work items (so, 0,1,2.  no gaps, no dups).  The
     * onSessionCallback task passed will be called only after all callbacks for previous channelInteractionNumbers
     * have been called.  This method isn't concerned with scheduling items to run at a specific time, that is
     * left up to the callback.
     */
    private <T> DiagnosticTrackableCompletableFuture<String, T>
    submitUnorderedWorkToEventLoop(IReplayContexts.IChannelKeyContext ctx,
                                   int sessionNumber,
                                   int channelInteractionNumber,
                                   Function<ConnectionReplaySession, DiagnosticTrackableCompletableFuture<String,T>>
                                           onSessionCallback) {
        final var replaySession = clientConnectionPool.getCachedSession(ctx, sessionNumber);
        return bindNettySubmitToTrackableFuture(replaySession.eventLoop)
                .getDeferredFutureThroughHandle((v,t) -> {
                    log.atTrace().setMessage(() -> "adding work item at slot " +
                            channelInteractionNumber + " for " + replaySession.getChannelKeyContext() + " with " +
                            replaySession.scheduleSequencer).log();
                    return replaySession.scheduleSequencer.addFutureForWork(channelInteractionNumber,
                            f->f.thenCompose(voidValue ->
                                    onSessionCallback.apply(replaySession), ()->"Work callback on replay session"));
                }, () -> "Waiting for sequencer to run for slot " + channelInteractionNumber);
    }

    private DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>
    scheduleSendRequestOnConnectionReplaySession(IReplayContexts.IReplayerHttpTransactionContext ctx,
                                                 ConnectionReplaySession connectionReplaySession,
                                                 Instant startTime,
                                                 Duration interval,
                                                 Stream<ByteBuf> packets) {
        var eventLoop = connectionReplaySession.eventLoop;
        var scheduledContext = ctx.createScheduledContext(startTime);
        int channelInterationNum = ctx.getReplayerRequestKey().getSourceRequestIndex();
        var diagnosticCtx =
                new IndexedChannelInteraction(ctx.getLogicalEnclosingScope().getChannelKey(), channelInterationNum);
        return scheduleOnConnectionReplaySession(diagnosticCtx, connectionReplaySession, startTime,
                new ChannelTask<>(ChannelTaskType.TRANSMIT, trigger ->
                        trigger.thenCompose(voidVal -> {
                            scheduledContext.close();
                            return sendSendingRestOfPackets(new NettyPacketToHttpConsumer(connectionReplaySession, ctx),
                                    eventLoop, packets.iterator(), startTime, interval, new AtomicInteger());
                        }, ()->"sending next packets")));
    }

    private DiagnosticTrackableCompletableFuture<String, Void>
    scheduleCloseOnConnectionReplaySession(IReplayContexts.IChannelKeyContext ctx,
                                           ConnectionReplaySession connectionReplaySession,
                                           Instant timestamp,
                                           int connectionReplaySessionNum,
                                           int channelInteractionNum,
                                           IndexedChannelInteraction channelInteraction) {
        var diagnosticCtx = new IndexedChannelInteraction(ctx.getChannelKey(), channelInteractionNum);
        return scheduleOnConnectionReplaySession(diagnosticCtx, connectionReplaySession, timestamp,
                new ChannelTask<>(ChannelTaskType.CLOSE,
                        dcf -> dcf.whenComplete((v,t) -> {
                            log.trace("Calling closeConnection at slot " + channelInteraction);
                            clientConnectionPool.closeConnection(ctx, connectionReplaySessionNum);
                        }, () -> "Close connection")
                ));
    }

    private <T> DiagnosticTrackableCompletableFuture<String,T>
    scheduleOnConnectionReplaySession(IndexedChannelInteraction channelInteraction,
                                      ConnectionReplaySession channelFutureAndRequestSchedule,
                                      Instant atTime,
                                      ChannelTask<T> task) {
        log.atInfo().setMessage(()->channelInteraction + " scheduling " + task.kind + " at " + atTime).log();

        var schedule = channelFutureAndRequestSchedule.schedule;
        var eventLoop = channelFutureAndRequestSchedule.eventLoop;

        var wasEmpty = schedule.isEmpty();
        assert wasEmpty || !atTime.isBefore(schedule.peekFirstItem().getKey()) :
                "Per-connection TrafficStream ordering should force a time ordering on incoming requests";
        var workPointTrigger = schedule.appendTaskTrigger(atTime, task.kind).scheduleFuture;
        var workFuture = task.getRunnable().apply(workPointTrigger);
        log.atTrace().setMessage(()->channelInteraction + " added a scheduled event at " + atTime +
                "... " + schedule).log();
        if (wasEmpty) {
            bindNettyScheduleToCompletableFuture(eventLoop, atTime, workPointTrigger.future);
        }

        workFuture.map(f->f.whenComplete((v,t)-> {
            var itemStartTimeOfPopped = schedule.removeFirstItem();
            assert atTime.equals(itemStartTimeOfPopped):
                    "Expected to have popped the item to match the start time for the responseFuture that finished";
            log.atDebug().setMessage(()->channelInteraction.toString() + " responseFuture completed - checking "
                    + schedule + " for the next item to schedule").log();
            Optional.ofNullable(schedule.peekFirstItem()).ifPresent(kvp-> {
                bindNettyFutureToTrackableFuture(eventLoop, kvp.getKey(), kvp.getValue().scheduleFuture);
            });
        }), ()->"");

        return workFuture;
    }

    private Instant now() {
        return Instant.now();
    }

    private Duration getDelayFromNowMs(Instant to) {
        return Duration.ofMillis(Math.max(0, Duration.between(now(), to).toMillis()));
    }

    private DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>
    sendSendingRestOfPackets(NettyPacketToHttpConsumer packetReceiver,
                             EventLoop eventLoop,
                             Iterator<ByteBuf> iterator,
                             Instant startAt,
                             Duration interval,
                             AtomicInteger counter) {
        final var oldCounter = counter.getAndIncrement();
        log.atTrace().setMessage(()->"sendNextPartAndContinue: counter=" + oldCounter).log();
        assert iterator.hasNext() : "Should not have called this with no items to send";

        packetReceiver.consumeBytes(iterator.next());
        if (iterator.hasNext()) {
            var delay = Duration.between(now(),
                    startAt.plus(interval.multipliedBy(counter.get())));
            return bindNettyScheduleToCompletableFuture(eventLoop, delay)
                    .getDeferredFutureThroughHandle((v,t)-> sendSendingRestOfPackets(packetReceiver, eventLoop,
                                iterator, startAt, interval, counter), () -> "sending next packet");
        } else {
            return packetReceiver.finalizeRequest();
        }
    }
}
