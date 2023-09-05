package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ChannelAndScheduledRequests;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
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

    private static class TimeAdjustData {
        public final Instant firstTimestamp;
        public final Instant systemTimeForFirstReplay;

        public TimeAdjustData(Instant firstTimestamp) {
            this.firstTimestamp = firstTimestamp;
            this.systemTimeForFirstReplay = Instant.now();
        }
    }

    // TODO - Reconsider how time shifting is done
    AtomicReference<TimeAdjustData> firstTimestampRef = new AtomicReference<>();

    public final ClientConnectionPool clientConnectionPool;

    public RequestSenderOrchestrator(ClientConnectionPool clientConnectionPool) {
        this.clientConnectionPool = clientConnectionPool;
    }

    Instant getTransformedTime(Instant ts) {
        firstTimestampRef.compareAndSet(null, new TimeAdjustData(ts));
        var rval = firstTimestampRef.get().systemTimeForFirstReplay
                .plus(Duration.between(firstTimestampRef.get().firstTimestamp, ts).multipliedBy(2));
        log.debug("Transformed time=" + ts + " -> " + rval);
        return rval;
    }
    
    public StringTrackableCompletableFuture<Void> scheduleClose(UniqueRequestKey requestKey, Instant orginalTimestamp) {
        var timestamp = getTransformedTime(orginalTimestamp);
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<Void>(new CompletableFuture<>(),
                        ()->"waiting for final signal to confirm close has finished");
        asynchronouslyInvokeRunnableToSetupFuture(requestKey, finalTunneledResponse,
                channelFutureAndRequestSchedule-> {
                    log.atInfo().setMessage(()->requestKey + " setting packet send at " + timestamp).log();
                    scheduleOnCffr(requestKey, channelFutureAndRequestSchedule,
                            finalTunneledResponse, timestamp, () -> {
                                log.trace("Closing client connection " + requestKey);
                                clientConnectionPool.closeConnection(requestKey.connectionId);
                                finalTunneledResponse.future.complete(null);
                            });
                });
        return finalTunneledResponse;
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueRequestKey requestKey, Instant originalStart, Duration interval, Stream<ByteBuf> packets) {
        var start = getTransformedTime(originalStart);
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<AggregatedRawResponse>(new CompletableFuture<>(),
                        ()->"waiting for final aggregated response");
        return asynchronouslyInvokeRunnableToSetupFuture(requestKey, finalTunneledResponse,
                channelFutureAndRequestSchedule-> scheduleSendOnCffr(requestKey, channelFutureAndRequestSchedule,
                        finalTunneledResponse, start, interval, packets));
    }

    private <T> DiagnosticTrackableCompletableFuture<String, T>
    asynchronouslyInvokeRunnableToSetupFuture(UniqueRequestKey requestKey,
                                              DiagnosticTrackableCompletableFuture<String,T> finalTunneledResponse,
                                              Consumer<ChannelAndScheduledRequests> successFn) {
        var channelFutureAndScheduleFuture = clientConnectionPool.submitEventualChannelGet(requestKey);
        var cf2 = channelFutureAndScheduleFuture.addListener(submitFuture->{
            if (!submitFuture.isSuccess()) {
                log.atError().setCause(submitFuture.cause())
                        .setMessage(()->requestKey.toString() + " unexpected issue found from a scheduled task")
                        .log();
                finalTunneledResponse.future.completeExceptionally(submitFuture.cause());
            } else {
                log.atTrace().setMessage(()->requestKey.toString() + " in submitFuture(success) callback").log();
                var channelFutureAndRequestSchedule = ((ChannelAndScheduledRequests) submitFuture.get());
                channelFutureAndRequestSchedule.channelFutureFuture
                        .map(channelFutureGetAttemptFuture->channelFutureGetAttemptFuture
                                        .thenAccept(v->{
                                            log.atTrace().setMessage(()->requestKey.toString() +
                                                    " ChannelFuture was created with "+v).log();
                                            assert v.channel() ==
                                                    channelFutureAndRequestSchedule.channelFutureFuture.future
                                                            .getNow(null).channel();
                                            runAfterChannelSetup(channelFutureAndRequestSchedule,
                                                    finalTunneledResponse,
                                                    cffr -> cffr.scheduleSequencer.add(requestKey.requestIndex,
                                                            ()->successFn.accept(channelFutureAndRequestSchedule),
                                                            x->x.run()));
                                        })
                                        .exceptionally(t->{
                                            log.atTrace().setCause(t).setMessage(()->requestKey.toString() +
                                                    " ChannelFuture creation threw an exception").log();
                                            finalTunneledResponse.future.completeExceptionally(t);
                                            return null;
                                        }),
                                ()->"waiting for channel future creation to happen");
            }
        });
        return finalTunneledResponse;
    }

    private <T> void scheduleOnCffr(UniqueRequestKey requestKey,
                                    ChannelAndScheduledRequests channelFutureAndRequestSchedule,
                                    StringTrackableCompletableFuture<T> signalCleanupCompleteToFuture,
                                    Instant atTime, Runnable task) {
        var schedule = channelFutureAndRequestSchedule.schedule;
        var eventLoop = channelFutureAndRequestSchedule.getInnerChannelFuture().channel().eventLoop();

        signalCleanupCompleteToFuture.map(f->f.whenComplete((v,t)-> {
            var itemStartTimeOfPopped = schedule.removeFirstItem();
            assert atTime.equals(itemStartTimeOfPopped):
                    "Expected to have popped the item to match the start time for the responseFuture that finished";
            log.atDebug().setMessage(()->requestKey.toString() + " responseFuture completed - checking "
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
                }
            });
        } else {
            assert !atTime.isBefore(schedule.peekFirstItem().getKey()) :
                    "Per-connection TrafficStream ordering should force a time ordering on incoming requests";
        }

        schedule.appendTask(atTime, task);
        log.atTrace().setMessage(()->requestKey + " added a scheduled event at " + atTime +
                "... " + schedule).log();
    }

    private void scheduleSendOnCffr(UniqueRequestKey requestKey,
                                    ChannelAndScheduledRequests channelFutureAndRequestSchedule,
                                    StringTrackableCompletableFuture<AggregatedRawResponse> responseFuture,
                                    Instant start, Duration interval, Stream<ByteBuf> packets) {
        log.atInfo().setMessage(()->requestKey + " scheduling packet send at " + start).log();
        var eventLoop = channelFutureAndRequestSchedule.eventLoop;
        AtomicReference packetReceiverRef = new AtomicReference();
        Runnable packetSender = () -> sendNextPartAndContinue(() ->
                        getPacketReceiver(requestKey, channelFutureAndRequestSchedule.getInnerChannelFuture(),
                                packetReceiverRef),
                eventLoop, packets.iterator(), start, interval, new AtomicInteger(), responseFuture);
        scheduleOnCffr(requestKey, channelFutureAndRequestSchedule, responseFuture, start, packetSender);
    }

    private <T> void runAfterChannelSetup(ChannelAndScheduledRequests channelFutureAndItsFutureRequests,
                                          DiagnosticTrackableCompletableFuture<String,T> responseFuture,
                                          Consumer<ChannelAndScheduledRequests> task) {
        var cf = channelFutureAndItsFutureRequests.getInnerChannelFuture();
        log.trace("cf="+cf);
        cf.addListener(f->{
            log.atTrace().setMessage(()->"channel creation has finished initialized (success="+f.isSuccess()+")").log();
            if (!f.isSuccess()) {
                responseFuture.future.completeExceptionally(
                        new RuntimeException("channel was returned in a bad state", f.cause()));
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
    getPacketReceiver(UniqueRequestKey requestKey, ChannelFuture channelFuture,
                      AtomicReference<NettyPacketToHttpConsumer> packetReceiver) {
        if (packetReceiver.get() == null) {
            packetReceiver.set(new NettyPacketToHttpConsumer(channelFuture, requestKey.toString()));
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
            var delayMs = Duration.between(Instant.now(), start.plus(interval.multipliedBy(counter.get()))).toMillis();
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
