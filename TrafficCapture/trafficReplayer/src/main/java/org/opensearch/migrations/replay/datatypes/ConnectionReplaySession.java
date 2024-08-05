package org.opensearch.migrations.replay.datatypes;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.OnlineRadixSorter;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;

import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * This class contains everything that is needed to replay packets to a specific channel.
 * ConnectionClientPool and RequestSenderOrchestrator manage the data within these objects.
 * The ConnectionClientPool manages lifecycles, caching, and the underlying connection.  The
 * RequestSenderOrchestrator handles scheduling writes and requisite activities (prep, close)
 * that will go out on the channel.
 */
@Slf4j
public class ConnectionReplaySession {

    /**
     * This value is only applicable for retrying exception issues when creating a socket that were
     * NOT of type java.net.SocketException.  Socket Exceptions will be retried repeatedly.  This is
     * intentional so that the messages that are being attempted won't finish with an error,
     * especially due to a misconfiguration on the client or on the server.
     *
     * NB/TODO - IS THIS THE RIGHT THING TO DO?  I suspect NOT because not getting a connection for
     * any reason still means that you don't want it to propagate up and cause the replayer to
     * consume the messages from the message stream.
     */
    private static final int MAX_RETRIES = 4;
    private static final Duration MAX_WAIT_BETWEEN_CREATE_RETRIES = Duration.ofSeconds(30);

    /**
     * We need to store this separately from the channelFuture because the channelFuture itself is
     * vended by a CompletableFuture (e.g. possibly a rate limiter).  If the ChannelFuture hasn't
     * been created yet, there's nothing to hold the channel, nor the eventLoop.  We _need_ the
     * EventLoop so that we can route all calls for this object into that loop/thread.
     */
    public final EventLoop eventLoop;
    public final OnlineRadixSorter scheduleSequencer;
    @Getter
    private Supplier<TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory;
    private ChannelFuture cachedChannel; // only can be accessed from the eventLoop thread
    public final TimeToResponseFulfillmentFutureMap schedule;
    @Getter
    private final IReplayContexts.IChannelKeyContext channelKeyContext;

    @SneakyThrows
    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        Supplier<TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory
    ) {
        this.eventLoop = eventLoop;
        this.channelKeyContext = channelKeyContext;
        this.scheduleSequencer = new OnlineRadixSorter(0);
        this.schedule = new TimeToResponseFulfillmentFutureMap();
        this.channelFutureFutureFactory = channelFutureFutureFactory;
    }

    public TrackedFuture<String, ChannelFuture> getFutureThatReturnsChannelFutureInAnyState(
        boolean requireActiveChannel
    ) {
        TextTrackedFuture<ChannelFuture> eventLoopTrigger = new TextTrackedFuture<>("procuring a connection");
        eventLoop.submit(() -> {
            if (!requireActiveChannel || (cachedChannel != null && cachedChannel.channel().isActive())) {
                eventLoopTrigger.future.complete(cachedChannel);
            } else {
                createNewChannelFuture(requireActiveChannel, eventLoopTrigger);
            }
        });
        return eventLoopTrigger;
    }

    private void createNewChannelFuture(
        boolean requireActiveChannel,
        TextTrackedFuture<ChannelFuture> eventLoopTrigger
    ) {
        createNewChannelFuture(requireActiveChannel, MAX_RETRIES, eventLoopTrigger, Duration.ofMillis(1));
    }

    private void createNewChannelFuture(
        boolean requireActive,
        int retries,
        TextTrackedFuture<ChannelFuture> eventLoopTrigger,
        Duration waitBetweenRetryDuration
    ) {
        channelFutureFutureFactory.get().future.whenComplete((v, tWrapped) -> {
            var t = TrackedFuture.unwindPossibleCompletionException(tWrapped);
            if (requireActive &&
                ((t == null || exceptionIsRetryable(t)) && retries > 0)) {
                if (t != null || !v.channel().isActive()) {
                    if (t != null) {
                        channelKeyContext.addCaughtException(t);
                        log.atWarn()
                            .setMessage(() -> "Caught exception while trying to get an active channel")
                            .setCause(t)
                            .log();
                    }
                    var retriesLeft = retries - (t instanceof java.net.SocketException ? 0 : 1);
                    var doubledDuration = waitBetweenRetryDuration.multipliedBy(2);
                    var w = MAX_WAIT_BETWEEN_CREATE_RETRIES.minus(doubledDuration).isNegative()
                        ? MAX_WAIT_BETWEEN_CREATE_RETRIES
                        : doubledDuration;
                    eventLoop.schedule(() -> createNewChannelFuture(requireActive, retriesLeft, eventLoopTrigger, w),
                        w.toMillis(), TimeUnit.MILLISECONDS);
                } else {
                    cachedChannel = v;
                    eventLoopTrigger.future.complete(v);
                }
            } else if (t != null) {
                channelKeyContext.addTraceException(t, true);
                eventLoopTrigger.future.completeExceptionally(t);
            } else {
                eventLoopTrigger.future.complete(v);
            }
        });
    }

    private static boolean exceptionIsRetryable(@NonNull Throwable t) {
        return t instanceof IOException;
    }

    public boolean hasWorkRemaining() {
        return !scheduleSequencer.isEmpty() || schedule.hasPendingTransmissions();
    }

    public long calculateSizeSlowly() {
        return (long) schedule.timeToRunnableMap.size() + scheduleSequencer.size();
    }
}
