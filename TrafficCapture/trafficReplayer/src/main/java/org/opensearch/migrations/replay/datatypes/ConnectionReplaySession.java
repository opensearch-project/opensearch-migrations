package org.opensearch.migrations.replay.datatypes;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.OnlineRadixSorter;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import lombok.Getter;
import lombok.Setter;
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
     * We need to store this separately from the channelFuture because the channelFuture itself is
     * vended by a CompletableFuture (e.g. possibly a rate limiter).  If the ChannelFuture hasn't
     * been created yet, there's nothing to hold the channel, nor the eventLoop.  We _need_ the
     * EventLoop so that we can route all calls for this object into that loop/thread.
     */
    public final EventLoop eventLoop;
    public final OnlineRadixSorter scheduleSequencer;
    @Getter
    private final BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory;
    private ChannelFuture cachedChannel; // only can be accessed from the eventLoop thread
    public final TimeToResponseFulfillmentFutureMap schedule;
    @Getter
    private final IReplayContexts.IChannelKeyContext channelKeyContext;
    /** Generation of the Kafka consumer assignment when this session was created. */
    public final int generation;
    /** Called when the session's channel is closed (regardless of cause). */
    public final Consumer<ConnectionReplaySession> onClose;
    /**
     * When true, this session has been cancelled due to a traffic source reader interruption.
     * {@link #getChannelFutureInActiveState} will return a failed future rather than reconnecting,
     * preventing self-healing reconnects after a partition reassignment cancel.
     */
    @Setter
    private volatile boolean cancelled = false;

    /**
     * Tracks completion of the second-to-last request on this connection.
     * Used to gate transformation of request N on completion of request N-2,
     * giving the transform a full request's worth of lead time while keeping
     * signatures fresh (not queued behind multiple requests).
     * Only accessed from the event loop thread.
     */
    private CompletableFuture<Void> previousRequestComplete = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> currentRequestComplete = CompletableFuture.completedFuture(null);

    /**
     * Result of {@link #getTransformGateAndAdvance()}: the gate to wait on before transforming,
     * and the signal to complete when this request finishes.
     */
    @lombok.AllArgsConstructor
    @lombok.Getter
    public static class TransformGate {
        private final CompletableFuture<Void> waitOn;
        private final CompletableFuture<Void> signalWhenDone;
    }

    /**
     * Returns a gate for the next request: a future to wait on (completes when request N-2 finishes)
     * and a future to signal when this request completes.
     * Must be called from the event loop thread, in request order.
     */
    public TransformGate getTransformGateAndAdvance() {
        var gate = previousRequestComplete;
        previousRequestComplete = currentRequestComplete;
        var myCompletion = new CompletableFuture<Void>();
        currentRequestComplete = myCompletion;
        return new TransformGate(gate, myCompletion);
    }

    @SneakyThrows
    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory
    ) {
        this(eventLoop, channelKeyContext, channelFutureFutureFactory, 0);
    }

    @SneakyThrows
    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory,
        int generation
    ) {
        this(eventLoop, channelKeyContext, channelFutureFutureFactory, generation, ignored -> {});
    }

    @SneakyThrows
    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory,
        int generation,
        Consumer<ConnectionReplaySession> onClose
    ) {
        this.eventLoop = eventLoop;
        this.channelKeyContext = channelKeyContext;
        this.scheduleSequencer = new OnlineRadixSorter(0);
        this.schedule = new TimeToResponseFulfillmentFutureMap();
        this.channelFutureFutureFactory = channelFutureFutureFactory;
        this.generation = generation;
        this.onClose = onClose;
    }

    public TrackedFuture<String, ChannelFuture> getChannelFutureInAnyState() {
        TextTrackedFuture<ChannelFuture> trigger = new TextTrackedFuture<>("procuring a connection");
        eventLoop.submit(() -> trigger.future.complete(cachedChannel));
        return trigger;
    }

    public TrackedFuture<String, ChannelFuture>
    getChannelFutureInActiveState(IReplayContexts.ITargetRequestContext ctx)
    {
        TextTrackedFuture<ChannelFuture> trigger = new TextTrackedFuture<>("procuring a connection");
        eventLoop.submit(() -> {
            if (cancelled) {
                trigger.future.completeExceptionally(
                    new IllegalStateException("Session cancelled due to traffic source reader interruption — not reconnecting"));
                return;
            }
            if (cachedChannel != null && cachedChannel.channel().isActive()) {
                trigger.future.complete(cachedChannel);
            } else {
                channelFutureFutureFactory.apply(eventLoop, ctx)
                    .whenComplete((v, t) -> {
                        if (t == null) {
                            trigger.future.complete(v);
                        } else {
                            trigger.future.completeExceptionally(TrackedFuture.unwindPossibleCompletionException(t));
                        }
                    }, () -> "working to signal back to an event loop trigger")
                    .whenComplete((v,t) -> cachedChannel = v, () -> "Setting cached channel");
            }
        });
        return trigger;
    }

    public boolean hasWorkRemaining() {
        return !scheduleSequencer.isEmpty() || schedule.hasPendingTransmissions();
    }

    public long calculateSizeSlowly() {
        return (long) schedule.timeToRunnableMap.size() + scheduleSequencer.size();
    }
}
