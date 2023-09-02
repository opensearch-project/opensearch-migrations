package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ChannelAndScheduledRequests;
import org.opensearch.migrations.replay.datatypes.TimeToResponseFulfillmentFutureMap;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class RequestSenderOrchestrator {

    private static final AttributeKey<TimeToResponseFulfillmentFutureMap> SCHEDULED_ITEMS_KEY =
            AttributeKey.valueOf("ScheduledItemsKey");

    public final ClientConnectionPool clientConnectionPool;

    public RequestSenderOrchestrator(ClientConnectionPool clientConnectionPool) {
        this.clientConnectionPool = clientConnectionPool;
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>
    scheduleRequest(UniqueRequestKey requestKey, Instant start, Duration interval, Stream<ByteBuf> packets) {
        var finalTunneledResponse =
                new StringTrackableCompletableFuture<AggregatedRawResponse>(new CompletableFuture<>(),
                        ()->"waiting for connection to be ready");
        var channelFutureAndScheduleFuture = clientConnectionPool.submitEventualChannelGet(requestKey);
        var cf2 = channelFutureAndScheduleFuture.addListener(submitFuture->{
                if (!submitFuture.isSuccess()) {
                    log.atError().setCause(submitFuture.cause())
                            .setMessage(()->requestKey.toString() + " unexpected issue found from a scheduled task")
                            .log();
                    finalTunneledResponse.future.completeExceptionally(submitFuture.cause());
                } else {
                    log.atTrace().setMessage(()->requestKey.toString() + " in submitFuture(success) callback").log();
                    var channelFutureAndFutureRequests = ((ChannelAndScheduledRequests) submitFuture.get());
                    channelFutureAndFutureRequests.channelFutureFuture
                            .map(channelFutureGetAttemptFuture->channelFutureGetAttemptFuture
                                            .thenAccept(v->{
                                                log.atTrace().setMessage(()->requestKey.toString() +
                                                        " ChannelFuture was created with "+v).log();
                                                assert v.channel() == channelFutureAndFutureRequests.channelFutureFuture.future.getNow(null).channel();
                                                scheduleSendAfterChannelSetup(requestKey, channelFutureAndFutureRequests,
                                                        finalTunneledResponse, start, interval, packets);
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

    private void scheduleSendAfterChannelSetup(UniqueRequestKey requestKey,
                                               ChannelAndScheduledRequests channelFutureAndItsFutureRequests,
                                               StringTrackableCompletableFuture<AggregatedRawResponse> responseFuture,
                                               Instant start, Duration interval,
                                               Stream<ByteBuf> packets) {
        var cf = channelFutureAndItsFutureRequests.getInnerChannelFuture();
        log.trace("cf="+cf);
        cf.addListener(f->{
            log.atTrace().setMessage(()->"channel creation has finished initialized (success="+f.isSuccess()+")").log();
            if (!f.isSuccess()) {
                responseFuture.future.completeExceptionally(
                        new RuntimeException("channel was returned in a bad state", f.cause()));
            } else {
                channelFutureAndItsFutureRequests.scheduleSequencer.add(requestKey.requestIndex, ()->scheduleSend(
                        requestKey, channelFutureAndItsFutureRequests, responseFuture, start, interval, packets),
                        x->x.run());
            }
        });
    }

    private void scheduleSend(UniqueRequestKey requestKey,
                              ChannelAndScheduledRequests channelFutureAndFutureRequests,
                              StringTrackableCompletableFuture<AggregatedRawResponse> responseFuture,
                              Instant start, Duration interval, Stream<ByteBuf> packets) {
        var schedule = channelFutureAndFutureRequests.schedule;
        var counter = new AtomicInteger();
        var packetReceiverRef = new AtomicReference<NettyPacketToHttpConsumer>();

        var eventLoop = channelFutureAndFutureRequests.getInnerChannelFuture().channel().eventLoop();
        Runnable packetSender = () -> sendNextPartAndContinue(() ->
                        getPacketReceiver(requestKey, channelFutureAndFutureRequests.getInnerChannelFuture(), packetReceiverRef),
                        eventLoop, packets.iterator(), start, interval, counter, responseFuture);
        if (schedule.isEmpty()) {
            var scheduledFuture = eventLoop.schedule(packetSender, getDelayFromNowMs(start), TimeUnit.MILLISECONDS);
            scheduledFuture.addListener(f->{
                if (!f.isSuccess()) {
                    log.atError().setCause(f.cause()).setMessage(()->"Error scheduling task").log();
                }
            });
        } else {
            assert !start.isBefore(schedule.peekFirstItem().getKey()) :
                    "Per-connection TrafficStream ordering should force a time ordering on incoming requests";
        }
        responseFuture.map(f->f.whenComplete((v,t)->{
            var itemStartTimeOfPopped = schedule.removeFirstItem();
            assert start.equals(itemStartTimeOfPopped):
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
        }), ()->"Kicking off the next item");
        schedule.appendTask(start, packetSender);
        log.atTrace().setMessage(()->requestKey + " added a scheduled event at " + start + " packets=" + packets +
                "... " + schedule).log();
    }

    private Instant now() {
        return Instant.now();
    }

    private long getDelayFromNowMs(Instant to) {
        return Math.max(0, Duration.between(now(), to).toMillis());
    }

    @SneakyThrows
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
        counter.incrementAndGet();
        Runnable packetSender = () -> sendNextPartAndContinue(packetHandlerSupplier, eventLoop,
                iterator, start, interval, counter, responseFuture);
        var delayMs = Duration.between(Instant.now(), start.plus(interval.multipliedBy(counter.get()))).toMillis();
        eventLoop.schedule(packetSender, Math.min(0, delayMs), TimeUnit.MILLISECONDS);

        if (!iterator.hasNext()) {
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
