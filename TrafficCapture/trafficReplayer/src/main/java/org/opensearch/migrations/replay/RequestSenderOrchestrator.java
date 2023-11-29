package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.IndexedChannelInteraction;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.ChannelKeyContext;
import org.opensearch.migrations.replay.tracing.RequestContext;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    scheduleWork(ISourceTrafficChannelKey channelKey, Instant timestamp,
                 Supplier<DiagnosticTrackableCompletableFuture<String,T>> task) {
        var connectionSession = clientConnectionPool.getCachedSession(channelKey, false);
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<T>(new CompletableFuture<>(),
                        ()->"waiting for final signal to confirm processing work has finished");
        log.atDebug().setMessage(()->"Scheduling work for "+channelKey+" at time "+timestamp).log();
        connectionSession.eventLoop.schedule(()->
                        task.get().map(f->f.whenComplete((v,t) -> {
                                    if (t!=null) {
                                        finalTunneledResponse.future.completeExceptionally(t);
                                    } else {
                                        finalTunneledResponse.future.complete(v);
                                    }
                                }),
                                ()->""),
                getDelayFromNowMs(timestamp), TimeUnit.MILLISECONDS);
        return finalTunneledResponse;
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueReplayerRequestKey requestKey, RequestContext ctx,
                    Instant start, Duration interval, Stream<ByteBuf> packets) {
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<AggregatedRawResponse>(new CompletableFuture<>(),
                        ()->"waiting for final aggregated response");
        log.atDebug().setMessage(()->"Scheduling request for "+requestKey+" at start time "+start).log();
        return asynchronouslyInvokeRunnableToSetupFuture(requestKey.getTrafficStreamKey(),
                requestKey.getReplayerRequestIndex(), ctx.getChannelKeyContext(), false, finalTunneledResponse,
                channelFutureAndRequestSchedule-> scheduleSendOnConnectionReplaySession(requestKey, ctx,
                        channelFutureAndRequestSchedule, finalTunneledResponse, start, interval, packets));
    }

    public StringTrackableCompletableFuture<Void> scheduleClose(ISourceTrafficChannelKey channelKey,
                                                                int channelInteractionNum,
                                                                ChannelKeyContext ctx,
                                                                Instant timestamp) {
        var channelInteraction = new IndexedChannelInteraction(channelKey, channelInteractionNum);
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<Void>(new CompletableFuture<>(),
                        ()->"waiting for final signal to confirm close has finished");
        log.atDebug().setMessage(()->"Scheduling CLOSE for "+channelInteraction+" at time "+timestamp).log();
        asynchronouslyInvokeRunnableToSetupFuture(channelKey, channelInteractionNum, ctx,true,
                finalTunneledResponse,
                channelFutureAndRequestSchedule->
                    scheduleOnConnectionReplaySession(channelKey, channelInteractionNum, ctx,
                            channelFutureAndRequestSchedule, finalTunneledResponse, timestamp, "close", () -> {
                                log.trace("Closing client connection " + channelInteraction);
                                clientConnectionPool.closeConnection(channelKey.getConnectionId());
                                finalTunneledResponse.future.complete(null);
                            })
                );
        return finalTunneledResponse;
    }

    private <T> DiagnosticTrackableCompletableFuture<String, T>
    asynchronouslyInvokeRunnableToSetupFuture(ISourceTrafficChannelKey channelKey, int channelInteractionNumber,
                                              ChannelKeyContext ctx, boolean ignoreIfChannelNotPresent,
                                              DiagnosticTrackableCompletableFuture<String,T> finalTunneledResponse,
                                              Consumer<ConnectionReplaySession> successFn) {
        var channelFutureAndScheduleFuture =
                clientConnectionPool.submitEventualSessionGet(channelKey, ignoreIfChannelNotPresent, ctx);
        channelFutureAndScheduleFuture.addListener(submitFuture->{
            if (!submitFuture.isSuccess()) {
                log.atError().setCause(submitFuture.cause())
                        .setMessage(()->channelKey.toString() + " unexpected issue found from a scheduled task")
                        .log();
                finalTunneledResponse.future.completeExceptionally(submitFuture.cause());
            } else {
                log.atTrace().setMessage(()->channelKey.toString() +
                        " on the channel's thread... getting a ConnectionReplaySession for it").log();
                var channelFutureAndRequestSchedule = ((ConnectionReplaySession) submitFuture.get());
                if (channelFutureAndRequestSchedule == null) {
                    finalTunneledResponse.future.complete(null);
                    return;
                }
                channelFutureAndRequestSchedule.getChannelFutureFuture()
                        .map(channelFutureGetAttemptFuture->channelFutureGetAttemptFuture
                                        .thenAccept(v->{
                                            log.atTrace().setMessage(()->channelKey.toString() + " in submitFuture(success) and scheduling the task" +
                                                    " for " + finalTunneledResponse.toString()).log();
                                            assert v.channel() ==
                                                    channelFutureAndRequestSchedule.getChannelFutureFuture().future
                                                            .getNow(null).channel();
                                            runAfterChannelSetup(channelFutureAndRequestSchedule,
                                                    finalTunneledResponse,
                                                    cffr -> {
                                                        cffr.scheduleSequencer.add(channelInteractionNumber,
                                                                () -> successFn.accept(channelFutureAndRequestSchedule),
                                                                x -> x.run());
                                                        if (cffr.scheduleSequencer.hasPending()) {
                                                            log.atDebug().setMessage(()->"Sequencer for "+channelKey+
                                                                    " = "+cffr.scheduleSequencer).log();
                                                        }
                                                    });
                                        })
                                        .exceptionally(t->{
                                            log.atTrace().setCause(t).setMessage(()->channelKey.toString() +
                                                    " ChannelFuture creation threw an exception").log();
                                            finalTunneledResponse.future.completeExceptionally(t);
                                            return null;
                                        }),
                                ()->"waiting for channel future creation to happen");
            }
        });
        return finalTunneledResponse;
    }

    private <T> void scheduleOnConnectionReplaySession(ISourceTrafficChannelKey channelKey, int channelInteractionIdx,
                                                       ChannelKeyContext ctx,
                                                       ConnectionReplaySession channelFutureAndRequestSchedule,
                                                       StringTrackableCompletableFuture<T> futureToBeCompletedByTask,
                                                       Instant atTime, String activityNameForLogging, Runnable task) {
        var channelInteraction = new IndexedChannelInteraction(channelKey, channelInteractionIdx);
        log.atInfo().setMessage(()->channelInteraction + " scheduling " + activityNameForLogging + " at " + atTime).log();

        var schedule = channelFutureAndRequestSchedule.schedule;
        var eventLoop = channelFutureAndRequestSchedule.getInnerChannelFuture().channel().eventLoop();

        futureToBeCompletedByTask.map(f->f.whenComplete((v,t)-> {
            var itemStartTimeOfPopped = schedule.removeFirstItem();
            assert atTime.equals(itemStartTimeOfPopped):
                    "Expected to have popped the item to match the start time for the responseFuture that finished";
            log.atDebug().setMessage(()->channelInteraction.toString() + " responseFuture completed - checking "
                    + schedule + " for the next item to schedule").log();
            Optional.ofNullable(schedule.peekFirstItem()).ifPresent(kvp-> {
                var sf = eventLoop.schedule(kvp.getValue(), getDelayFromNowMs(kvp.getKey()), TimeUnit.MILLISECONDS);
                sf.addListener(sfp->{
                    if (!sfp.isSuccess()) {
                        log.atWarn().setCause(sfp.cause()).setMessage(()->"Scheduled future was not successful").log();
                    }
                });
            });
        }), ()->"");

        if (schedule.isEmpty()) {
            var scheduledFuture =
                    eventLoop.schedule(task, getDelayFromNowMs(atTime), TimeUnit.MILLISECONDS);
            scheduledFuture.addListener(f->{
                if (!f.isSuccess()) {
                    log.atError().setCause(f.cause()).setMessage(()->"Error scheduling task").log();
                } else {
                    log.atInfo().setMessage(()->"scheduled future has finished for "+channelInteraction).log();
                }
            });
        } else {
            assert !atTime.isBefore(schedule.peekFirstItem().getKey()) :
                    "Per-connection TrafficStream ordering should force a time ordering on incoming requests";
        }

        schedule.appendTask(atTime, task);
        log.atTrace().setMessage(()->channelInteraction + " added a scheduled event at " + atTime +
                "... " + schedule).log();
    }

    private void scheduleSendOnConnectionReplaySession(UniqueReplayerRequestKey requestKey, RequestContext ctx,
                                                       ConnectionReplaySession channelFutureAndRequestSchedule,
                                                       StringTrackableCompletableFuture<AggregatedRawResponse> responseFuture,
                                                       Instant start, Duration interval, Stream<ByteBuf> packets) {
        var eventLoop = channelFutureAndRequestSchedule.eventLoop;
        var packetReceiverRef = new AtomicReference<NettyPacketToHttpConsumer>();
        Runnable packetSender = () -> sendNextPartAndContinue(() ->
                        getPacketReceiver(ctx, channelFutureAndRequestSchedule.getInnerChannelFuture(),
                                packetReceiverRef),
                eventLoop, packets.iterator(), start, interval, new AtomicInteger(), responseFuture);
        scheduleOnConnectionReplaySession(requestKey.trafficStreamKey, requestKey.getSourceRequestIndex(),
                ctx.getChannelKeyContext(), channelFutureAndRequestSchedule, responseFuture, start,
                "send", packetSender);
    }

    private <T> void runAfterChannelSetup(ConnectionReplaySession channelFutureAndItsFutureRequests,
                                          DiagnosticTrackableCompletableFuture<String,T> responseFuture,
                                          Consumer<ConnectionReplaySession> task) {
        var cf = channelFutureAndItsFutureRequests.getInnerChannelFuture();
        cf.addListener(f->{
            log.atTrace().setMessage(()->"channel creation has finished initialized (success="+f.isSuccess()+")").log();
            if (!f.isSuccess()) {
                responseFuture.future.completeExceptionally(
                        new IllegalStateException("channel was returned in a bad state", f.cause()));
            } else {
                task.accept(channelFutureAndItsFutureRequests);
            }
        });
    }

    private Instant now() {
        return Instant.now();
    }

    private long getDelayFromNowMs(Instant to) {
        return Math.max(0, Duration.between(now(), to).toMillis());
    }

    private static NettyPacketToHttpConsumer
    getPacketReceiver(RequestContext requestContext, ChannelFuture channelFuture,
                      AtomicReference<NettyPacketToHttpConsumer> packetReceiver) {
        if (packetReceiver.get() == null) {
            packetReceiver.set(new NettyPacketToHttpConsumer(channelFuture, requestContext));
        }
        return packetReceiver.get();
    }

    private void sendNextPartAndContinue(Supplier<NettyPacketToHttpConsumer> packetHandlerSupplier,
                                         EventLoop eventLoop, Iterator<ByteBuf> iterator,
                                         Instant start, Duration interval, AtomicInteger counter,
                                         StringTrackableCompletableFuture<AggregatedRawResponse> responseFuture) {
        log.atTrace().setMessage(()->"sendNextPartAndContinue: counter=" + counter.get()).log();
        var packetReceiver = packetHandlerSupplier.get();
        assert iterator.hasNext() : "Should not have called this with no items to send";

        packetReceiver.consumeBytes(iterator.next());
        if (iterator.hasNext()) {
            counter.incrementAndGet();
            Runnable packetSender = () -> sendNextPartAndContinue(packetHandlerSupplier, eventLoop,
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
