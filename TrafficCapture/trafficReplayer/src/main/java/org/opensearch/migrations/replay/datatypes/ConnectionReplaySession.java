package org.opensearch.migrations.replay.datatypes;

import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;

import org.opensearch.migrations.NettyFutureBinders;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import lombok.Getter;
import lombok.NonNull;
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

    @FunctionalInterface
    public interface ChannelFutureFactory {
        TrackedFuture<String, ChannelFuture> apply(
            EventLoop eventLoop,
            IReplayContexts.ITargetRequestContext context,
            CancellationSignal cancellationSignal
        );
    }

    public static final class CancellationSignal {
        @FunctionalInterface
        public interface Registration extends AutoCloseable {
            @Override
            void close();
        }

        private CancellationException cause;
        private Runnable activeCancellation;

        public CancellationException cause() {
            synchronized (this) {
                return cause;
            }
        }

        public Registration register(@NonNull Runnable cancellation) {
            CancellationException existingCause;
            synchronized (this) {
                existingCause = cause;
                if (existingCause == null) {
                    if (activeCancellation != null) {
                        throw new IllegalStateException("another channel operation is already registered");
                    }
                    activeCancellation = cancellation;
                    return () -> unregister(cancellation);
                }
            }
            cancellation.run();
            return () -> {};
        }

        private void cancel(CancellationException cancellationCause) {
            Runnable cancellation;
            synchronized (this) {
                if (cause == null) {
                    cause = cancellationCause;
                }
                cancellation = activeCancellation;
                activeCancellation = null;
            }
            if (cancellation != null) {
                cancellation.run();
            }
        }

        private void unregister(Runnable cancellation) {
            synchronized (this) {
                if (activeCancellation == cancellation) {
                    activeCancellation = null;
                }
            }
        }
    }

    /**
     * We need to store this separately from the channelFuture because the channelFuture itself is
     * vended by a CompletableFuture (e.g. possibly a rate limiter).  If the ChannelFuture hasn't
     * been created yet, there's nothing to hold the channel, nor the eventLoop.  We _need_ the
     * EventLoop so that we can route all calls for this object into that loop/thread.
     */
    public final EventLoop eventLoop;
    @Getter
    private final ChannelFutureFactory channelFutureFutureFactory;
    private final CancellationSignal cancellationSignal = new CancellationSignal();
    private ChannelFuture cachedChannel; // only can be accessed from the eventLoop thread
    private ChannelFuture observedChannel;
    @Getter
    private final IReplayContexts.IChannelKeyContext channelKeyContext;
    private final TargetExchangeState.Metrics metrics;
    private TargetExchangeState.ChannelState channelState;
    private boolean metricsRetired;
    private TextTrackedFuture<ChannelFuture> activeAcquisitionResult;
    /** Generation of the Kafka consumer assignment when this session was created. */
    public final int generation;
    /**
     * When non-null, this session has been cancelled due to a traffic source reader interruption.
     * {@link #getChannelFutureInActiveState} will return a failed future rather than reconnecting,
     * preventing self-healing reconnects after a partition reassignment cancel.
     */
    private CancellationException cancellationCause;

    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory
    ) {
        this(
            eventLoop,
            channelKeyContext,
            adapt(channelFutureFutureFactory),
            0,
            TargetExchangeState.Metrics.NOOP
        );
    }

    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        ChannelFutureFactory channelFutureFutureFactory
    ) {
        this(eventLoop, channelKeyContext, channelFutureFutureFactory, 0, TargetExchangeState.Metrics.NOOP);
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
            adapt(channelFutureFutureFactory),
            generation,
            TargetExchangeState.Metrics.NOOP
        );
    }

    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        ChannelFutureFactory channelFutureFutureFactory,
        int generation
    ) {
        this(eventLoop, channelKeyContext, channelFutureFutureFactory, generation, TargetExchangeState.Metrics.NOOP);
    }

    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelFutureFutureFactory,
        int generation,
        @NonNull TargetExchangeState.Metrics metrics
    ) {
        this(eventLoop, channelKeyContext, adapt(channelFutureFutureFactory), generation, metrics);
    }

    public ConnectionReplaySession(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext channelKeyContext,
        ChannelFutureFactory channelFutureFutureFactory,
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

    private static ChannelFutureFactory adapt(
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> factory
    ) {
        return (eventLoop, context, ignoredCancellation) -> factory.apply(eventLoop, context);
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

    public boolean isCancelled() {
        return cancellationCause != null;
    }

    /**
     * Cancels this session and closes the channel currently owned by it. An acquisition that
     * completes after this stage settles is rejected and its channel is closed on the event loop.
     */
    public TrackedFuture<String, Channel> cancelAndClose(@NonNull CancellationException cause) {
        TextTrackedFuture<Channel> completion = new TextTrackedFuture<>("cancelling and closing a replay session");
        runOnEventLoop(() -> cancelAndCloseOnOwner(cause, completion));
        return completion;
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

    // An Error must still settle the trigger and reset acquisition state or future callers remain stranded.
    @SuppressWarnings("java:S1181")
    private void acquireActiveChannel(
        IReplayContexts.ITargetRequestContext context,
        TextTrackedFuture<ChannelFuture> trigger
    ) {
        if (cancellationCause != null) {
            trigger.future.completeExceptionally(cancellationCause);
            return;
        }
        if (cachedChannel != null && cachedChannel.channel().isActive()) {
            transitionChannelState(TargetExchangeState.ChannelState.ACTIVE);
            trigger.future.complete(cachedChannel);
            return;
        }
        if (activeAcquisitionResult != null) {
            activeAcquisitionResult.future.whenComplete((channelFuture, failure) ->
                completeAcquisitionResult(trigger, channelFuture, failure)
            );
            return;
        }

        cachedChannel = null;
        observedChannel = null;
        transitionChannelState(TargetExchangeState.ChannelState.CONNECTING);
        activeAcquisitionResult = trigger;
        TrackedFuture<String, ChannelFuture> acquisition;
        try {
            acquisition = channelFutureFutureFactory.apply(eventLoop, context, cancellationSignal);
        } catch (RuntimeException | Error e) {
            activeAcquisitionResult = null;
            transitionChannelState(TargetExchangeState.ChannelState.ABSENT);
            trigger.future.completeExceptionally(e);
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
        if (activeAcquisitionResult == trigger) {
            activeAcquisitionResult = null;
        }
        if (cancellationCause != null) {
            trigger.future.completeExceptionally(cancellationCause);
            if (channelFuture == null) {
                transitionChannelState(TargetExchangeState.ChannelState.ABSENT);
            } else {
                closeRejectedChannel(channelFuture);
            }
            return;
        }
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
        observeChannel(channelFuture);
        trigger.future.complete(channelFuture);
    }

    private void completeAcquisitionResult(
        TextTrackedFuture<ChannelFuture> trigger,
        ChannelFuture channelFuture,
        Throwable failure
    ) {
        if (failure == null) {
            trigger.future.complete(channelFuture);
        } else {
            trigger.future.completeExceptionally(TrackedFuture.unwindPossibleCompletionException(failure));
        }
    }

    private void cancelAndCloseOnOwner(
        CancellationException cause,
        TextTrackedFuture<Channel> completion
    ) {
        if (cancellationCause == null) {
            cancellationCause = cause;
            cancellationSignal.cancel(cause);
        }
        if (activeAcquisitionResult != null) {
            activeAcquisitionResult.future.completeExceptionally(cancellationCause);
        }

        var channelFuture = cachedChannel;
        cachedChannel = null;
        if (channelFuture == null) {
            transitionChannelState(TargetExchangeState.ChannelState.ABSENT);
            completion.future.complete(null);
            return;
        }

        transitionChannelState(TargetExchangeState.ChannelState.CLOSING);
        NettyFutureBinders.bindNettyFutureToCompletableFuture(channelFuture.channel().close())
            .whenComplete((ignored, failure) -> runOnEventLoop(() -> {
                if (failure == null) {
                    completion.future.complete(channelFuture.channel());
                } else {
                    completion.future.completeExceptionally(
                        TrackedFuture.unwindPossibleCompletionException(failure)
                    );
                }
            }));
    }

    private void closeRejectedChannel(ChannelFuture channelFuture) {
        observeChannel(channelFuture);
        transitionChannelState(TargetExchangeState.ChannelState.CLOSING);
        channelFuture.channel().close().addListener(closeFuture -> {
            if (!closeFuture.isSuccess()) {
                log.atWarn()
                    .setCause(closeFuture.cause())
                    .setMessage("Failed to close a channel acquired after cancellation for {}")
                    .addArgument(channelKeyContext)
                    .log();
            }
        });
    }

    private void observeChannel(ChannelFuture channelFuture) {
        observedChannel = channelFuture;
        channelFuture.addListener(ignored ->
            runOnEventLoop(() -> onConnectSettled(channelFuture))
        );
        channelFuture.channel().closeFuture().addListener(ignored ->
            runOnEventLoop(() -> onChannelClosed(channelFuture))
        );
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
