package org.opensearch.migrations.replay.datatypes;

import java.io.IOException;
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

    private static final int MAX_CHANNEL_CREATE_RETRIES = 16;

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
        TextTrackedFuture<ChannelFuture> eventLoopFuture = new TextTrackedFuture<>("procuring a connection");
        eventLoop.submit(() -> {
            if (!requireActiveChannel || (cachedChannel != null && cachedChannel.channel().isActive())) {
                eventLoopFuture.future.complete(cachedChannel);
            } else {
                createNewChannelFuture(requireActiveChannel, eventLoopFuture);
            }
        });
        return eventLoopFuture;
    }

    private void createNewChannelFuture(
        boolean requireActiveChannel,
        TextTrackedFuture<ChannelFuture> eventLoopFuture
    ) {
        createNewChannelFuture(requireActiveChannel, MAX_CHANNEL_CREATE_RETRIES, eventLoopFuture);
    }

    private void createNewChannelFuture(
        boolean requireActiveChannel,
        int retries,
        TextTrackedFuture<ChannelFuture> eventLoopFuture
    ) {
        channelFutureFutureFactory.get().future.whenComplete((v, t) -> {
            if (requireActiveChannel && retries > 0 && (t == null || exceptionIsRetryable(t))) {
                if (t != null || !v.channel().isActive()) {
                    if (t != null) {
                        channelKeyContext.addCaughtException(t);
                        log.atWarn()
                            .setMessage(() -> "Caught exception while trying to get an active channel")
                            .setCause(t)
                            .log();
                    }
                    createNewChannelFuture(requireActiveChannel, retries - 1, eventLoopFuture);
                } else {
                    cachedChannel = v;
                    eventLoopFuture.future.complete(v);
                }
            } else if (t != null) {
                channelKeyContext.addTraceException(t, true);
                eventLoopFuture.future.completeExceptionally(t);
            } else {
                eventLoopFuture.future.complete(v);
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
