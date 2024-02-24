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
import org.slf4j.event.Level;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class RequestSenderOrchestrator {

    public final ClientConnectionPool clientConnectionPool;

    public RequestSenderOrchestrator(ClientConnectionPool clientConnectionPool) {
        this.clientConnectionPool = clientConnectionPool;
    }

    public <T> DiagnosticTrackableCompletableFuture<String, T>
    scheduleWork(IReplayContexts.IReplayerHttpTransactionContext ctx, Instant timestamp,
                 Supplier<DiagnosticTrackableCompletableFuture<String,T>> task) {
        var connectionSession = clientConnectionPool.getCachedSession(ctx.getChannelKeyContext(), false);
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<T>(new CompletableFuture<>(),
                        ()->"waiting for final signal to confirm processing work has finished");
        log.atDebug().setMessage(()->"Scheduling work for "+ctx.getConnectionId()+" at time "+timestamp).log();
        var scheduledContext = ctx.createScheduledContext(timestamp);
        // this method doesn't use the scheduling that scheduleRequest and scheduleClose use because
        // doing work associated with a connection is considered to be preprocessing work independent
        // of the underlying network connection itself, so it's fair to be able to do this without
        // first needing to wait for a connection to succeed.  In fact, making them more independent
        // means that the work item being enqueued is less likely to cause a connection timeout.
        connectionSession.eventLoop.schedule(()-> {
                    scheduledContext.close();
                    return task.get().map(f -> f.whenComplete((v, t) -> {
                                if (t != null) {
                                    finalTunneledResponse.future.completeExceptionally(t);
                                } else {
                                    finalTunneledResponse.future.complete(v);
                                }
                            }),
                            () -> "");
                },
                getDelayFromNowMs(timestamp), TimeUnit.MILLISECONDS);
        return finalTunneledResponse;
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueReplayerRequestKey requestKey, IReplayContexts.IReplayerHttpTransactionContext ctx,
                    Instant start, Duration interval, Stream<ByteBuf> packets) {
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<AggregatedRawResponse>(new CompletableFuture<>(),
                        ()->"waiting for final aggregated response");
        log.atDebug().setMessage(()->"Scheduling request for "+requestKey+" at start time "+start).log();
        // When a socket connection is attempted could be more precise.
        // Ideally, we would match the relative timestamps of when connections were being initiated
        // as well as the period between connection and the first bytes sent.  However, this code is a
        // bit too cavalier.  It should be tightened at some point.
        return asynchronouslyInvokeRunnable(ctx.getLogicalEnclosingScope(),
                requestKey.getReplayerRequestIndex(), false, finalTunneledResponse,
                channelFutureAndRequestSchedule -> scheduleSendRequestOnConnectionReplaySession(ctx,
                        channelFutureAndRequestSchedule, finalTunneledResponse, start, interval, packets));
    }

    public StringTrackableCompletableFuture<Void> scheduleClose(IReplayContexts.IChannelKeyContext ctx,
                                                                int channelInteractionNum,
                                                                Instant timestamp) {
        var channelKey = ctx.getChannelKey();
        var channelInteraction = new IndexedChannelInteraction(channelKey, channelInteractionNum);
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<Void>(new CompletableFuture<>(),
                        ()->"waiting for final signal to confirm close has finished");
        log.atDebug().setMessage(() -> "Scheduling CLOSE for " + channelInteraction + " at time " + timestamp).log();
        asynchronouslyInvokeRunnable(ctx, channelInteractionNum, true,
                finalTunneledResponse,
                channelFutureAndRequestSchedule->
                    scheduleOnConnectionReplaySession(ctx, channelInteractionNum,
                            channelFutureAndRequestSchedule, finalTunneledResponse, timestamp,
                            new ChannelTask(ChannelTaskType.CLOSE, () -> {
                                log.trace("Closing client connection " + channelInteraction);
                                clientConnectionPool.closeConnection(ctx);
                                finalTunneledResponse.future.complete(null);
                            })));
        return finalTunneledResponse;
    }

    /**
     * This method sets up the onSessionCallback to run in the order defined by channeInteractionNumber.  The
     * onSessionCallback task passed will be called only after all callbacks for previous channelInteractionNumbers
     * have been called.  This method isn't concerned with scheduling items, that would be left up to the callback.
     */
    private <T> DiagnosticTrackableCompletableFuture<String, T>
    asynchronouslyInvokeRunnable(IReplayContexts.IChannelKeyContext ctx,
                                 int channelInteractionNumber,
                                 boolean ignoreIfChannelNotActive,
                                 DiagnosticTrackableCompletableFuture<String,T> finalTunneledResponse,
                                 Consumer<ConnectionReplaySession> onSessionCallback) {
        final var replaySession = clientConnectionPool.getCachedSession(ctx, ignoreIfChannelNotActive);
        if (replaySession == null) {
            log.atLevel(ignoreIfChannelNotActive ? Level.TRACE : Level.ERROR)
                    .setMessage(()->"No cached replaySession.  Not running the onSessionCallback for " + ctx).log();
            finalTunneledResponse.future.complete(null);
            return finalTunneledResponse;
        }
        replaySession.eventLoop.submit(()->{
                    log.atTrace().setMessage(() -> "adding work item at slot " +
                            channelInteractionNumber + " for " + replaySession.getChannelKeyContext() + " with " +
                            replaySession.scheduleSequencer).log();
                    replaySession.scheduleSequencer.add(channelInteractionNumber,
                            () -> onSessionCallback.accept(replaySession),
                            Runnable::run);
                    log.atLevel(replaySession.scheduleSequencer.hasPending() ? Level.DEBUG : Level.TRACE)
                            .setMessage(() -> "Sequencer for " + replaySession.getChannelKeyContext() + " = " +
                                    replaySession.scheduleSequencer).log();
        });
        return finalTunneledResponse;
    }

    private void scheduleSendRequestOnConnectionReplaySession(IReplayContexts.IReplayerHttpTransactionContext ctx,
                                                              ConnectionReplaySession connectionReplaySession,
                                                              StringTrackableCompletableFuture<AggregatedRawResponse>
                                                                      responseFuture,
                                                              Instant start,
                                                              Duration interval,
                                                              Stream<ByteBuf> packets) {
        var eventLoop = connectionReplaySession.eventLoop;
        var scheduledContext = ctx.createScheduledContext(start);
        scheduleOnConnectionReplaySession(ctx.getLogicalEnclosingScope(),
                ctx.getReplayerRequestKey().getSourceRequestIndex(), connectionReplaySession, responseFuture, start,
                new ChannelTask(ChannelTaskType.TRANSMIT, ()->{
                    scheduledContext.close();
                    sendNextPartAndContinue(new NettyPacketToHttpConsumer(connectionReplaySession, ctx),
                            eventLoop, packets.iterator(), start, interval, new AtomicInteger(), responseFuture);
                }));
    }

    private <T> void scheduleOnConnectionReplaySession(IReplayContexts.IChannelKeyContext ctx,
                                                       int channelInteractionIdx,
                                                       ConnectionReplaySession channelFutureAndRequestSchedule,
                                                       StringTrackableCompletableFuture<T> futureToBeCompletedByTask,
                                                       Instant atTime,
                                                       ChannelTask task) {
        var channelInteraction = new IndexedChannelInteraction(ctx.getChannelKey(), channelInteractionIdx);
        log.atInfo().setMessage(()->channelInteraction + " scheduling " + task.kind + " at " + atTime).log();

        var schedule = channelFutureAndRequestSchedule.schedule;
        var eventLoop = channelFutureAndRequestSchedule.eventLoop;

        if (schedule.isEmpty()) {
            var scheduledFuture =
                    eventLoop.schedule(task.runnable, getDelayFromNowMs(atTime), TimeUnit.MILLISECONDS);
            scheduledFuture.addListener(f->{
                if (!f.isSuccess()) {
                    log.atError().setCause(f.cause()).setMessage(()->"Error running the scheduled task: " + ctx +
                            " interaction: " + channelInteraction).log();
                } else {
                    log.atInfo().setMessage(()->"scheduled task has finished for " + ctx + " interaction: " +
                            channelInteraction).log();
                }
            });
        } else {
            assert !atTime.isBefore(schedule.peekFirstItem().getKey()) :
                    "Per-connection TrafficStream ordering should force a time ordering on incoming requests";
        }

        schedule.appendTask(atTime, task);
        log.atTrace().setMessage(()->channelInteraction + " added a scheduled event at " + atTime +
                "... " + schedule).log();

        futureToBeCompletedByTask.map(f->f.whenComplete((v,t)-> {
            var itemStartTimeOfPopped = schedule.removeFirstItem();
            assert atTime.equals(itemStartTimeOfPopped):
                    "Expected to have popped the item to match the start time for the responseFuture that finished";
            log.atDebug().setMessage(()->channelInteraction.toString() + " responseFuture completed - checking "
                    + schedule + " for the next item to schedule").log();
            Optional.ofNullable(schedule.peekFirstItem()).ifPresent(kvp-> {
                var runnable = kvp.getValue().runnable;
                var sf = eventLoop.schedule(runnable, getDelayFromNowMs(kvp.getKey()), TimeUnit.MILLISECONDS);
                sf.addListener(sfp->{
                    if (!sfp.isSuccess()) {
                        log.atWarn().setCause(sfp.cause()).setMessage(()->"Scheduled future did not successfully run " +
                                channelInteraction).log();
                    }
                });
            });
        }), ()->"");
    }

    private Instant now() {
        return Instant.now();
    }

    private long getDelayFromNowMs(Instant to) {
        return Math.max(0, Duration.between(now(), to).toMillis());
    }

    // TODO - rewrite this - the recursion (at least as it is) is terribly confusing
    private void sendNextPartAndContinue(NettyPacketToHttpConsumer packetReceiver,
                                         EventLoop eventLoop, Iterator<ByteBuf> iterator,
                                         Instant start, Duration interval, AtomicInteger counter,
                                         StringTrackableCompletableFuture<AggregatedRawResponse> responseFuture) {
        final var oldCounter = counter.getAndIncrement();
        log.atTrace().setMessage(()->"sendNextPartAndContinue: counter=" + oldCounter).log();
        assert iterator.hasNext() : "Should not have called this with no items to send";

        packetReceiver.consumeBytes(iterator.next());
        if (iterator.hasNext()) {
            Runnable packetSender = () -> sendNextPartAndContinue(packetReceiver, eventLoop,
                    iterator, start, interval, counter, responseFuture);
            var delayMs = Duration.between(now(),
                    start.plus(interval.multipliedBy(counter.get()))).toMillis();
            eventLoop.schedule(packetSender, Math.min(0, delayMs), TimeUnit.MILLISECONDS);
        } else {
            packetReceiver.finalizeRequest().handle((v,t)-> {
                if (t != null) {
                    responseFuture.future.completeExceptionally(t);
                } else {
                    responseFuture.future.complete(v);
                }
                return null;
            }, ()->"waiting for finalize to send Aggregated Response");
        }
    }

}
