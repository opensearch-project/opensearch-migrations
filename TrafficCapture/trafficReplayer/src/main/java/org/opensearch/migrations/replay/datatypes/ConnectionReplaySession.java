package org.opensearch.migrations.replay.datatypes;

import java.util.function.BiFunction;

import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
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
    @Getter
    private final BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory;
    private ChannelFuture cachedChannel; // only can be accessed from the eventLoop thread
    private ChannelFuture observedChannel;
    @Getter
    private final IReplayContexts.IChannelKeyContext channelKeyContext;
    private final TargetExchangeState.Metrics metrics;
    private TargetExchangeState.ChannelState channelState;
    private boolean metricsRetired;
    /** Generation of the Kafka consumer assignment when this session was created. */
    public final int generation;
    /**
     * When true, this session has been cancelled due to a traffic source reader interruption.
     * {@link #getChannelFutureInActiveState} will return a failed future rather than reconnecting,
     * preventing self-healing reconnects after a partition reassignment cancel.
     */
    @Getter
    @Setter
    private boolean cancelled;

    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory
    ) {
        this(
            eventLoop,
            channelKeyContext,
            channelFutureFutureFactory,
            0,
            TargetExchangeState.Metrics.NOOP
        );
    }

    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory,
        int generation
    ) {
        this(
            eventLoop,
            channelKeyContext,
            channelFutureFutureFactory,
            generation,
            TargetExchangeState.Metrics.NOOP
        );
    }

    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory,
        int generation,
        @NonNull TargetExchangeState.Metrics metrics
    ) {
        this.eventLoop = eventLoop;
        this.channelKeyContext = channelKeyContext;
        this.channelFutureFutureFactory = channelFutureFutureFactory;
        this.generation = generation;
        this.metrics = metrics;
        this.channelState = TargetExchangeState.ChannelState.ABSENT;
        runOnEventLoop(() -> metrics.channelStateChanged(channelState, 1));
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
        eventLoop.submit(() -> acquireActiveChannel(ctx, trigger));
        return trigger;
    }

    public void markChannelClosing(ChannelFuture channelFuture) {
        runOnEventLoop(() -> {
            if (channelFuture == cachedChannel) {
                transitionChannelState(TargetExchangeState.ChannelState.CLOSING);
            }
        });
    }

    public void retireMetrics() {
        runOnEventLoop(() -> {
            if (!metricsRetired) {
                metricsRetired = true;
                metrics.channelStateChanged(channelState, -1);
            }
        });
    }

    private void acquireActiveChannel(
        IReplayContexts.ITargetRequestContext context,
        TextTrackedFuture<ChannelFuture> trigger
    ) {
        if (cancelled) {
            trigger.future.completeExceptionally(
                new IllegalStateException(
                    "Session cancelled due to traffic source reader interruption — not reconnecting"
                )
            );
            return;
        }
        if (cachedChannel != null && cachedChannel.channel().isActive()) {
            transitionChannelState(TargetExchangeState.ChannelState.ACTIVE);
            trigger.future.complete(cachedChannel);
            return;
        }

        cachedChannel = null;
        observedChannel = null;
        transitionChannelState(TargetExchangeState.ChannelState.CONNECTING);
        TrackedFuture<String, ChannelFuture> acquisition;
        try {
            acquisition = channelFutureFutureFactory.apply(eventLoop, context);
        } catch (Throwable t) {
            transitionChannelState(TargetExchangeState.ChannelState.ABSENT);
            trigger.future.completeExceptionally(t);
            return;
        }
        acquisition.future.whenComplete((channelFuture, failure) ->
            runOnEventLoop(() -> onChannelAcquired(trigger, channelFuture, failure))
        );
    }

    private void onChannelAcquired(
        TextTrackedFuture<ChannelFuture> trigger,
        ChannelFuture channelFuture,
        Throwable failure
    ) {
        if (failure != null) {
            transitionChannelState(TargetExchangeState.ChannelState.ABSENT);
            trigger.future.completeExceptionally(TrackedFuture.unwindPossibleCompletionException(failure));
            return;
        }
        if (channelFuture == null) {
            transitionChannelState(TargetExchangeState.ChannelState.ABSENT);
            trigger.future.completeExceptionally(
                new NullPointerException("channel factory completed without a ChannelFuture")
            );
            return;
        }

        cachedChannel = channelFuture;
        observedChannel = channelFuture;
        channelFuture.addListener(ignored ->
            runOnEventLoop(() -> onConnectSettled(channelFuture))
        );
        channelFuture.channel().closeFuture().addListener(ignored ->
            runOnEventLoop(() -> onChannelClosed(channelFuture))
        );
        trigger.future.complete(channelFuture);
    }

    private void onConnectSettled(ChannelFuture channelFuture) {
        if (channelFuture != observedChannel) {
            return;
        }
        transitionChannelState(
            channelFuture.isSuccess() && channelFuture.channel().isActive()
                ? TargetExchangeState.ChannelState.ACTIVE
                : TargetExchangeState.ChannelState.INACTIVE
        );
    }

    private void onChannelClosed(ChannelFuture channelFuture) {
        if (channelFuture != observedChannel) {
            return;
        }
        observedChannel = null;
        if (cachedChannel == channelFuture) {
            cachedChannel = null;
        }
        transitionChannelState(TargetExchangeState.ChannelState.CLOSED);
    }

    private void transitionChannelState(TargetExchangeState.ChannelState nextState) {
        if (metricsRetired || channelState == nextState) {
            return;
        }
        metrics.channelStateChanged(channelState, -1);
        channelState = nextState;
        metrics.channelStateChanged(channelState, 1);
    }

    private void runOnEventLoop(Runnable command) {
        if (eventLoop.inEventLoop()) {
            command.run();
        } else {
            eventLoop.execute(command);
        }
    }
}
