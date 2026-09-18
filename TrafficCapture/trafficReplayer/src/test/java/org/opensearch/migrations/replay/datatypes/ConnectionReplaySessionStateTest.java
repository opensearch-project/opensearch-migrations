package org.opensearch.migrations.replay.datatypes;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionReplaySessionStateTest extends InstrumentationTest {
    private LocalEventLoopGroup eventLoopGroup;
    private LocalEventLoopGroup serverGroup;
    private ChannelFuture serverChannel;

    @BeforeEach
    void createEventLoops() throws Exception {
        eventLoopGroup = new LocalEventLoopGroup(1, new DefaultThreadFactory("session-state-client"));
        serverGroup = new LocalEventLoopGroup(1, new DefaultThreadFactory("session-state-server"));
        serverChannel = new io.netty.bootstrap.ServerBootstrap()
            .group(serverGroup)
            .channel(LocalServerChannel.class)
            .childHandler(new ChannelInitializer<LocalChannel>() {
                @Override
                protected void initChannel(LocalChannel channel) {}
            })
            .bind(new LocalAddress("session-state-" + System.nanoTime()))
            .sync();
    }

    @AfterEach
    void closeEventLoops() throws Exception {
        if (serverChannel != null) {
            serverChannel.channel().close().sync();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().sync();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully().sync();
        }
    }

    @Test
    void channelStateMutationsStayOnOwnerAndRetirementRemovesTheLastGauge() throws Exception {
        var metrics = new RecordingMetrics();
        var transactionContext = rootContext.getTestConnectionRequestContext("channel-state", 0);
        var targetContext = transactionContext.createTargetRequestContext();
        var session = new ConnectionReplaySession(
            eventLoopGroup.next(),
            transactionContext.getChannelKeyContext(),
            this::connect,
            0,
            metrics
        );

        metrics.awaitCount(TargetExchangeState.ChannelState.ABSENT, 1);
        var channelFuture = session.getChannelFutureInActiveState(targetContext).get(Duration.ofSeconds(5));
        metrics.awaitCount(TargetExchangeState.ChannelState.ACTIVE, 1);

        session.markChannelClosing(channelFuture);
        metrics.awaitCount(TargetExchangeState.ChannelState.CLOSING, 1);
        channelFuture.channel().close().sync();
        metrics.awaitCount(TargetExchangeState.ChannelState.CLOSED, 1);

        session.retireMetrics();
        metrics.awaitAllZero();

        Assertions.assertTrue(metrics.onlyOwnerThreadCallbacks());
        Assertions.assertEquals(
            List.of(
                TargetExchangeState.ChannelState.ABSENT,
                TargetExchangeState.ChannelState.CONNECTING,
                TargetExchangeState.ChannelState.ACTIVE,
                TargetExchangeState.ChannelState.CLOSING,
                TargetExchangeState.ChannelState.CLOSED
            ),
            metrics.enteredStates()
        );
    }

    @Test
    void staleClosedChannelCannotOverwriteAReplacementChannelState() throws Exception {
        var metrics = new RecordingMetrics();
        var transactionContext = rootContext.getTestConnectionRequestContext("replacement-channel", 0);
        var targetContext = transactionContext.createTargetRequestContext();
        var eventLoop = eventLoopGroup.next();
        var firstChannel = new ControlledChannel(eventLoop);
        var secondChannel = new ControlledChannel(eventLoop);
        var channels = new ArrayDeque<>(List.of(firstChannel, secondChannel));
        var session = new ConnectionReplaySession(
            eventLoop,
            transactionContext.getChannelKeyContext(),
            (ignoredEventLoop, ignoredContext) -> TextTrackedFuture.completedFuture(
                channels.remove().connectFuture,
                () -> "controlled channel connection"
            ),
            0,
            metrics
        );

        var first = session.getChannelFutureInActiveState(targetContext).get(Duration.ofSeconds(5));
        metrics.awaitCount(TargetExchangeState.ChannelState.ACTIVE, 1);
        firstChannel.active.set(false);

        var second = session.getChannelFutureInActiveState(targetContext).get(Duration.ofSeconds(5));
        metrics.awaitCount(TargetExchangeState.ChannelState.ACTIVE, 1);
        firstChannel.closeFuture.setSuccess();
        eventLoop.submit(() -> {}).sync();

        Assertions.assertEquals(1, metrics.count(TargetExchangeState.ChannelState.ACTIVE));
        Assertions.assertEquals(0, metrics.count(TargetExchangeState.ChannelState.CLOSED));

        secondChannel.active.set(false);
        secondChannel.closeFuture.setSuccess();
        metrics.awaitCount(TargetExchangeState.ChannelState.CLOSED, 1);
        session.retireMetrics();
        metrics.awaitAllZero();
        Assertions.assertSame(firstChannel.connectFuture, first);
        Assertions.assertSame(secondChannel.connectFuture, second);
    }

    @Test
    void channelFactoryErrorSettlesAcquisitionAndAllowsRetry() throws Exception {
        var metrics = new RecordingMetrics();
        var transactionContext = rootContext.getTestConnectionRequestContext("factory-error", 0);
        var targetContext = transactionContext.createTargetRequestContext();
        var eventLoop = eventLoopGroup.next();
        var channel = new ControlledChannel(eventLoop);
        var attempts = new AtomicInteger();
        var factoryFailure = new AssertionError("channel factory failed");
        var session = new ConnectionReplaySession(
            eventLoop,
            transactionContext.getChannelKeyContext(),
            (ignoredEventLoop, ignoredContext, ignoredCancellation) -> {
                if (attempts.getAndIncrement() == 0) {
                    throw factoryFailure;
                }
                return TextTrackedFuture.completedFuture(
                    channel.connectFuture,
                    () -> "controlled channel connection"
                );
            },
            0,
            metrics
        );

        var failedAcquisition = session.getChannelFutureInActiveState(targetContext);
        var failure = Assertions.assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> failedAcquisition.get(Duration.ofSeconds(5))
        );
        Assertions.assertSame(factoryFailure, failure.getCause());
        metrics.awaitCount(TargetExchangeState.ChannelState.ABSENT, 1);

        Assertions.assertSame(
            channel.connectFuture,
            session.getChannelFutureInActiveState(targetContext).get(Duration.ofSeconds(5))
        );
        metrics.awaitCount(TargetExchangeState.ChannelState.ACTIVE, 1);

        session.cancelAndClose(new CancellationException("test complete")).get(Duration.ofSeconds(5));
        metrics.awaitCount(TargetExchangeState.ChannelState.CLOSED, 1);
        session.retireMetrics();
        metrics.awaitAllZero();
    }

    @Test
    void cancellationRejectsAndClosesAChannelDeliveredAfterAcquisition() throws Exception {
        var metrics = new RecordingMetrics();
        var transactionContext = rootContext.getTestConnectionRequestContext("cancel-acquisition", 0);
        var targetContext = transactionContext.createTargetRequestContext();
        var eventLoop = eventLoopGroup.next();
        var delayedAcquisition = new CompletableFuture<ChannelFuture>();
        var delayedChannel = new ControlledChannel(eventLoop);
        var connectorCancellations = new AtomicInteger();
        var session = new ConnectionReplaySession(
            eventLoop,
            transactionContext.getChannelKeyContext(),
            (ignoredEventLoop, ignoredContext, cancellationSignal) -> {
                var registration = cancellationSignal.register(connectorCancellations::incrementAndGet);
                delayedAcquisition.whenComplete((ignored, failure) -> registration.close());
                return new TextTrackedFuture<>(delayedAcquisition, "delayed channel connection");
            },
            0,
            metrics
        );
        var cancellation = new CancellationException("source reassigned");

        var acquisition = session.getChannelFutureInActiveState(targetContext);
        metrics.awaitCount(TargetExchangeState.ChannelState.CONNECTING, 1);
        Assertions.assertNull(session.cancelAndClose(cancellation).get(Duration.ofSeconds(5)));

        var acquisitionFailure = Assertions.assertThrows(
            CancellationException.class,
            () -> acquisition.get(Duration.ofSeconds(5))
        );
        Assertions.assertSame(cancellation, acquisitionFailure);
        Assertions.assertEquals(1, connectorCancellations.get());
        delayedAcquisition.complete(delayedChannel.connectFuture);

        await(() -> delayedChannel.closeCalls.get() == 1);
        metrics.awaitCount(TargetExchangeState.ChannelState.CLOSED, 1);
        Assertions.assertNull(session.getChannelFutureInAnyState().get(Duration.ofSeconds(5)));

        var postCancellation = session.getChannelFutureInActiveState(targetContext);
        var postCancellationFailure = Assertions.assertThrows(
            CancellationException.class,
            () -> postCancellation.get(Duration.ofSeconds(5))
        );
        Assertions.assertSame(cancellation, postCancellationFailure);
        Assertions.assertEquals(1, delayedChannel.closeCalls.get());
        session.retireMetrics();
        metrics.awaitAllZero();
    }

    @Test
    void cancellationWaitsForAnOwnedChannelToClose() throws Exception {
        var transactionContext = rootContext.getTestConnectionRequestContext("wait-for-close", 0);
        var targetContext = transactionContext.createTargetRequestContext();
        var eventLoop = eventLoopGroup.next();
        var controlledChannel = new ControlledChannel(eventLoop, false);
        var session = new ConnectionReplaySession(
            eventLoop,
            transactionContext.getChannelKeyContext(),
            (ignoredEventLoop, ignoredContext) ->
                TextTrackedFuture.completedFuture(
                    controlledChannel.connectFuture,
                    () -> "controlled channel connection"
                )
        );

        session.getChannelFutureInActiveState(targetContext).get(Duration.ofSeconds(5));
        var cancellation = session.cancelAndClose(new CancellationException("source reassigned"));

        await(() -> controlledChannel.closeCalls.get() == 1);
        Assertions.assertFalse(cancellation.future.isDone());
        controlledChannel.closeFuture.setSuccess();

        Assertions.assertSame(controlledChannel.channel, cancellation.get(Duration.ofSeconds(5)));
        Assertions.assertEquals(1, controlledChannel.closeCalls.get());
    }

    private TextTrackedFuture<ChannelFuture> connect(
        EventLoop eventLoop,
        org.opensearch.migrations.replay.tracing.IReplayContexts.ITargetRequestContext ignored
    ) {
        var address = serverChannel.channel().localAddress();
        var connect = new Bootstrap()
            .group(eventLoop)
            .channel(LocalChannel.class)
            .handler(new ChannelInitializer<LocalChannel>() {
                @Override
                protected void initChannel(LocalChannel channel) {}
            })
            .connect(address);
        var completion = new CompletableFuture<ChannelFuture>();
        connect.addListener(future -> {
            if (future.isSuccess()) {
                completion.complete(connect);
            } else {
                completion.completeExceptionally(future.cause());
            }
        });
        return new TextTrackedFuture<>(completion, "local channel connection");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        Assertions.assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    private static final class RecordingMetrics implements TargetExchangeState.Metrics {
        private final Map<TargetExchangeState.ChannelState, Integer> counts =
            new EnumMap<>(TargetExchangeState.ChannelState.class);
        private final List<TargetExchangeState.ChannelState> enteredStates = new ArrayList<>();
        private final List<Boolean> ownerThreadCallbacks = new ArrayList<>();

        @Override
        public synchronized void phaseChanged(TargetExchangeState.Phase phase, int delta) {}

        @Override
        public synchronized void channelStateChanged(TargetExchangeState.ChannelState state, int delta) {
            counts.merge(state, delta, Integer::sum);
            if (delta > 0) {
                enteredStates.add(state);
            }
            ownerThreadCallbacks.add(Thread.currentThread().getName().startsWith("session-state-client"));
        }

        synchronized int count(TargetExchangeState.ChannelState state) {
            return counts.getOrDefault(state, 0);
        }

        void awaitCount(TargetExchangeState.ChannelState state, int expected) throws InterruptedException {
            await(() -> count(state) == expected);
        }

        void awaitAllZero() throws InterruptedException {
            await(() -> {
                synchronized (this) {
                    return counts.values().stream().allMatch(value -> value == 0);
                }
            });
        }

        synchronized boolean onlyOwnerThreadCallbacks() {
            return !ownerThreadCallbacks.isEmpty() && ownerThreadCallbacks.stream().allMatch(Boolean::booleanValue);
        }

        synchronized List<TargetExchangeState.ChannelState> enteredStates() {
            return List.copyOf(enteredStates);
        }
    }

    private static final class ControlledChannel {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final Channel channel;
        private final DefaultChannelPromise connectFuture;
        private final DefaultChannelPromise closeFuture;
        private final boolean completeCloseImmediately;

        private ControlledChannel(EventLoop eventLoop) {
            this(eventLoop, true);
        }

        private ControlledChannel(EventLoop eventLoop, boolean completeCloseImmediately) {
            this.completeCloseImmediately = completeCloseImmediately;
            channel = mock(Channel.class);
            when(channel.isActive()).thenAnswer(ignored -> active.get());
            closeFuture = new DefaultChannelPromise(channel, eventLoop);
            when(channel.closeFuture()).thenReturn(closeFuture);
            when(channel.close()).thenAnswer(ignored -> {
                closeCalls.incrementAndGet();
                active.set(false);
                if (this.completeCloseImmediately) {
                    closeFuture.trySuccess();
                }
                return closeFuture;
            });
            connectFuture = new DefaultChannelPromise(channel, eventLoop);
            connectFuture.setSuccess();
        }
    }
}
