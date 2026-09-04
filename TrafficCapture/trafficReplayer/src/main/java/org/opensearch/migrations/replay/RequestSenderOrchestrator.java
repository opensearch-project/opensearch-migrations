package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opensearch.migrations.NettyFutureBinders;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.ChannelTask;
import org.opensearch.migrations.replay.datatypes.ChannelTaskType;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.IndexedChannelInteraction;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ConnectionActor;
import org.opensearch.migrations.replay.lifecycle.NettyEventLoopActorMailbox;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.RefSafeHolder;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * This class deals with scheduling different HTTP connection/request activities on a Netty Event Loop.
 * There are 4 public methods for this class.  scheduleAtFixedRate serves as a utility function and the
 * other 3 schedule methods.  scheduleWork handles any preparatory work that may need to be performed
 * (like transformation).  scheduleRequest will send the request and wait for the response, retrying
 * as necessary (with the same pacing, though it should probably be as fast as possible for retries - TODO).
 * scheduleClose will close the connection, if still open, used to send requests for the specified channel.<br><br>
 *
 * Notice that if the channel doesn't exist or isn't active when sending any request, a new one will be
 * created.  That channel (a socket connection to the server) is managed by theClientConnectionPool that's
 * passed into the constructor.  The pool itself will create a connection (Channel/ChannelFuture) via a
 * static factory method.  That connection is ready to hand off to packet consumer that's created from
 * the IPacketConsumer factory passed to the constructor.  Of course, the connection may be reused by multiple
 * IPacketConsumer objects (multiple requests on one connection) OR there could be multiple retries with new
 * connections for one request.  So the coupling is actually between the IPacketConsumer, which is for a single
 * request, and the ConnectionReplaySession, which can recreate (reconnect) a channel if it hasn't already or
 * if its previously created one is no longer functional.<br><br>
 *
 *
 */
@Slf4j
public class RequestSenderOrchestrator {

    private final ClientConnectionPool clientConnectionPool;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory;
    private final ConcurrentHashMap<ConnectionSessionKey, ActorRuntime> actorRuntimes = new ConcurrentHashMap<>();

    /**
     * Notice that the two arguments need to be in agreement with each other.  The clientConnectionPool will need to
     * be able to create/return ConnectionReplaySession objects with Channels (or, to be more exact, ChannelFutures
     * that resolve Channels) that can be utilized by the IPacketFinalizingConsumer objects.  For example, it TLS
     * is being used, either the clientConnectionPool will be responsible for configuring the channel with handlers
     * to do that or that functionality will need to be provided by the factory/packet consumer.
     * @param clientConnectionPool
     * @param packetConsumerFactory
     */
    public RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory
    ) {
        this(clientConnectionPool, Duration.ofMillis(100), Duration.ofSeconds(300), packetConsumerFactory);
    }

    public RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory
    ) {
        this.clientConnectionPool = clientConnectionPool;
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.packetConsumerFactory = packetConsumerFactory;
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable,
                                                  long initialDelay,
                                                  long delay,
                                                  TimeUnit timeUnit) {
        return clientConnectionPool.scheduleAtFixedRate(runnable, initialDelay, delay, timeUnit);
    }

    public <T> TrackedFuture<String, T> scheduleWork(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant timestamp,
        Supplier<TrackedFuture<String, T>> task
    ) {
        var connectionSession = clientConnectionPool.getCachedSession(
            ctx.getChannelKeyContext(),
            ctx.getReplayerRequestKey().sourceRequestIndexSessionIdentifier
        );
        log.atDebug().setMessage("Scheduling work for {} at time {}")
            .addArgument(ctx::getConnectionId)
            .addArgument(timestamp)
            .log();
        var scheduledContext = ctx.createScheduledContext(timestamp);
        // This method doesn't use the scheduling that scheduleRequest and scheduleClose use because
        // doing work associated with a connection is considered to be preprocessing work independent
        // of the underlying network connection itself, so it's fair to be able to do this without
        // first needing to wait for a connection to succeed.
        //
        // This means that this method might run transformation work "out-of-order" from the natural
        // ordering of the requests (defined by their original captured order). However, the final
        // order will be preserved once they're sent since sending requires the channelInteractionIndex,
        // which is the caller's responsibility to track and pass. This method doesn't need it to
        // schedule work to happen on the channel's thread at some point in the future.
        //
        // Making them more independent means that the work item being enqueued is lighter-weight and
        // less likely to cause a connection timeout.
        var timerFuture = bindNettyScheduleToCompletableFuture(
            connectionSession.eventLoop, timestamp);
        connectionSession.addPendingTransformationTimer(timerFuture.future);
        timerFuture.future.whenComplete((v, t) -> {
            if (t == null) {
                connectionSession.removePendingTransformationTimer(timerFuture.future);
            }
        });
        return timerFuture
            .getDeferredFutureThroughHandle((nullValue, scheduleFailure) -> {
                scheduledContext.close();
                if (scheduleFailure != null) {
                    return TextTrackedFuture.failedFuture(scheduleFailure, () -> "netty scheduling failure");
                } else if (connectionSession.isCancelled()) {
                    return TextTrackedFuture.failedFuture(
                        new java.util.concurrent.CancellationException(
                            "Session cancelled before transformation work could start"),
                        () -> "session cancelled for " + ctx);
                } else {
                    return task.get();
                }
            }, () -> "The scheduled callback is running work for " + ctx);
    }

    public enum RetryDirective {
        DONE, RETRY
    }

    private final class PreparedActorRequest implements AutoCloseable {
        private final IReplayContexts.IReplayerHttpTransactionContext context;
        private final Instant start;
        private final Duration interval;
        private final ByteBufListProducer packetProducer;
        private final RetryVisitor<Object> visitor;
        private final AsyncPermitPool.Permit permit;
        private final IReplayContexts.IScheduledContext scheduledContext;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();

        private PreparedActorRequest(
            IReplayContexts.IReplayerHttpTransactionContext context,
            Instant start,
            Duration interval,
            ByteBufListProducer packetProducer,
            RetryVisitor<Object> visitor,
            AsyncPermitPool.Permit permit,
            IReplayContexts.IScheduledContext scheduledContext
        ) {
            this.context = context;
            this.start = start;
            this.interval = interval;
            this.packetProducer = packetProducer;
            this.visitor = visitor;
            this.permit = permit;
            this.scheduledContext = scheduledContext;
        }

        private void beginExecution() {
            if (started.compareAndSet(false, true)) {
                scheduledContext.close();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    beginExecution();
                } finally {
                    try {
                        packetProducer.release();
                    } finally {
                        permit.close();
                    }
                }
            }
        }
    }

    private final class ActorRuntime {
        private final ConnectionSessionKey key;
        private final ConnectionReplaySession session;
        private final ConnectionActor<PreparedActorRequest, Object> actor;

        private ActorRuntime(
            ConnectionSessionKey key,
            IReplayContexts.IChannelKeyContext channelContext
        ) {
            this.key = key;
            this.session = clientConnectionPool.getCachedSession(
                channelContext,
                key.sessionNumber(),
                key.sourceGeneration()
            );
            this.actor = new ConnectionActor<>(
                key,
                new NettyEventLoopActorMailbox(session.eventLoop),
                new RuntimeTargetExchange(this)
            );
            actor.termination().whenComplete((outcome, failure) -> {
                actorRuntimes.remove(key, this);
                clientConnectionPool.invalidateSession(
                    key.connection().connectionId(),
                    key.sessionNumber(),
                    key.sourceGeneration()
                );
            });
        }
    }

    private final class RuntimeTargetExchange implements ConnectionActor.TargetExchange<PreparedActorRequest, Object> {
        private final ActorRuntime runtime;
        private CompletionStage<TargetOutcome<Object>> activeExchange;

        private RuntimeTargetExchange(ActorRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public CompletionStage<TargetOutcome<Object>> execute(PreparedActorRequest preparedRequest) {
            preparedRequest.beginExecution();
            @SuppressWarnings("unchecked")
            var exchange = (TrackedFuture<String, Object>) (TrackedFuture<?, ?>) sendRequestWithRetries(
                () -> packetConsumerFactory.apply(runtime.session, preparedRequest.context),
                runtime.session.eventLoop,
                preparedRequest.packetProducer,
                preparedRequest.start,
                initialRetryDelay,
                preparedRequest.interval,
                preparedRequest.visitor
            );
            CompletableFuture<TargetOutcome<Object>> normalized = exchange.future.handle((value, failure) -> {
                if (failure == null) {
                    return new TargetOutcome.Succeeded<Object>(value);
                }
                var cause = unwrap(failure);
                if (cause instanceof CancellationException cancellation) {
                    return new TargetOutcome.Cancelled<Object>(cancellation);
                }
                return new TargetOutcome.Failed<Object>(cause);
            });
            activeExchange = normalized;
            normalized.whenComplete((value, failure) -> {
                runtime.session.eventLoop.execute(() -> {
                    if (activeExchange == normalized) {
                        activeExchange = null;
                    }
                });
            });
            return normalized;
        }

        @Override
        public CompletionStage<Void> close() {
            return closeRuntimeChannel(runtime);
        }

        @Override
        public CompletionStage<Void> abort(CancellationException cause) {
            runtime.session.setCancelled(true);
            var exchangeToJoin = activeExchange;
            return closeRuntimeChannel(runtime).thenCompose(ignored ->
                exchangeToJoin == null
                    ? CompletableFuture.completedFuture(null)
                    : exchangeToJoin.handle((outcome, failure) -> null)
            );
        }
    }

    private final class PreparationCoordinator<T> {
        private final ActorRuntime runtime;
        private final ReplayRequestId requestId;
        private final IReplayContexts.IReplayerHttpTransactionContext context;
        private final Instant preparationStart;
        private final Instant sendStart;
        private final Instant sendEnd;
        private final AsyncPermitPool permitPool;
        private final Supplier<TrackedFuture<String, TransformedOutputAndResult<ByteBufListProducer>>> preparation;
        private final Function<
            TransformedOutputAndResult<ByteBufListProducer>,
            RetryVisitor<T>
        > retryVisitorFactory;
        private final Function<HttpRequestTransformationStatus, T> filteredResultFactory;
        private final AtomicReference<T> filteredResult;
        private final CompletableFuture<PreparationOutcome<PreparedActorRequest>> completion =
            new CompletableFuture<>();
        private final IReplayContexts.IScheduledContext preparationScheduledContext;
        private final IReplayContexts.IScheduledContext sendScheduledContext;
        private ScheduledFuture<?> preparationTimer;
        private CompletableFuture<?> transformationCompletion;
        private AsyncPermitPool.Permit permit;
        private boolean permitReady;
        private boolean timerReady;
        private boolean preparationStarted;
        private boolean terminal;
        private boolean preparationContextClosed;
        private boolean sendContextTransferred;

        private PreparationCoordinator(
            ActorRuntime runtime,
            ReplayRequestId requestId,
            IReplayContexts.IReplayerHttpTransactionContext context,
            Instant preparationStart,
            Instant sendStart,
            Instant sendEnd,
            AsyncPermitPool permitPool,
            Supplier<TrackedFuture<String, TransformedOutputAndResult<ByteBufListProducer>>> preparation,
            Function<TransformedOutputAndResult<ByteBufListProducer>, RetryVisitor<T>> retryVisitorFactory,
            Function<HttpRequestTransformationStatus, T> filteredResultFactory,
            AtomicReference<T> filteredResult
        ) {
            this.runtime = runtime;
            this.requestId = requestId;
            this.context = context;
            this.preparationStart = preparationStart;
            this.sendStart = sendStart;
            this.sendEnd = sendEnd;
            this.permitPool = permitPool;
            this.preparation = preparation;
            this.retryVisitorFactory = retryVisitorFactory;
            this.filteredResultFactory = filteredResultFactory;
            this.filteredResult = filteredResult;
            this.preparationScheduledContext = context.createScheduledContext(preparationStart);
            this.sendScheduledContext = context.createScheduledContext(sendStart);
        }

        private CompletionStage<PreparationOutcome<PreparedActorRequest>> stage() {
            return completion;
        }

        private void start() {
            completion.whenComplete((outcome, failure) -> {
                if (completion.isCancelled()) {
                    runtime.session.eventLoop.execute(() ->
                        cancel(new CancellationException("Preparation cancelled for " + requestId))
                    );
                }
            });

            permitPool.acquire(requestId, 1).whenComplete((acquiredPermit, failure) ->
                runtime.session.eventLoop.execute(() -> onPermitSettled(acquiredPermit, failure))
            );

            try {
                var delay = getDelayFromNowMs(preparationStart);
                preparationTimer = runtime.session.eventLoop.schedule(
                    this::onPreparationDue,
                    delay.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            } catch (Throwable t) {
                runtime.session.eventLoop.execute(() -> failPreparation(t));
            }
        }

        private void onPermitSettled(AsyncPermitPool.Permit acquiredPermit, Throwable failure) {
            if (terminal) {
                if (acquiredPermit != null) {
                    acquiredPermit.close();
                }
                return;
            }
            if (failure != null) {
                failPreparation(unwrap(failure));
                return;
            }
            permit = acquiredPermit;
            permitReady = true;
            tryStartPreparation();
        }

        private void onPreparationDue() {
            if (terminal) {
                return;
            }
            preparationTimer = null;
            closePreparationContext();
            timerReady = true;
            tryStartPreparation();
        }

        private void tryStartPreparation() {
            if (terminal || preparationStarted || !permitReady || !timerReady) {
                return;
            }
            preparationStarted = true;
            TrackedFuture<String, TransformedOutputAndResult<ByteBufListProducer>> transformed;
            try {
                transformed = Objects.requireNonNull(
                    preparation.get(),
                    "preparation returned no future"
                );
            } catch (Throwable t) {
                failPreparation(t);
                return;
            }
            transformationCompletion = transformed.future;
            transformed.future.whenComplete((result, failure) ->
                runtime.session.eventLoop.execute(() -> onTransformed(result, failure))
            );
        }

        private void onTransformed(
            TransformedOutputAndResult<ByteBufListProducer> transformed,
            Throwable failure
        ) {
            transformationCompletion = null;
            if (terminal) {
                releaseLateTransformation(transformed);
                return;
            }
            if (failure != null) {
                failPreparation(unwrap(failure));
                return;
            }
            if (transformed == null) {
                failPreparation(new NullPointerException("preparation completed without a result"));
                return;
            }
            if (transformed.transformedOutput == null) {
                try {
                    filteredResult.set(filteredResultFactory.apply(transformed.transformationStatus));
                    completeWithoutPreparedRequest(
                        new PreparationOutcome.Filtered<>(
                            transformed.transformationStatus.getClass().getSimpleName()
                        )
                    );
                } catch (Throwable t) {
                    failPreparation(t);
                }
                return;
            }

            var packetProducer = transformed.transformedOutput;
            var packetCount = packetProducer.numByteBufs();
            var interval = packetCount > 1
                ? Duration.between(sendStart, sendEnd).dividedBy(packetCount - 1L)
                : Duration.ZERO;
            RetryVisitor<T> typedVisitor;
            try {
                typedVisitor = Objects.requireNonNull(
                    retryVisitorFactory.apply(transformed),
                    "retry visitor factory returned null"
                );
            } catch (Throwable t) {
                packetProducer.release();
                failPreparation(t);
                return;
            }

            @SuppressWarnings("unchecked")
            var actorVisitor = (RetryVisitor<Object>) (RetryVisitor<?>) typedVisitor;
            var prepared = new PreparedActorRequest(
                context,
                sendStart,
                interval,
                packetProducer,
                actorVisitor,
                permit,
                sendScheduledContext
            );
            permit = null;
            sendContextTransferred = true;
            terminal = true;
            if (!completion.complete(new PreparationOutcome.Prepared<>(prepared))) {
                prepared.close();
            }
        }

        private void failPreparation(Throwable failure) {
            var cause = unwrap(failure);
            if (cause instanceof CancellationException cancellation) {
                completeWithoutPreparedRequest(new PreparationOutcome.Cancelled<>(cancellation));
            } else {
                completeWithoutPreparedRequest(new PreparationOutcome.Failed<>(cause));
            }
        }

        private void completeWithoutPreparedRequest(PreparationOutcome<PreparedActorRequest> outcome) {
            if (terminal) {
                return;
            }
            terminal = true;
            cancelPreparationTimer();
            closePreparationContext();
            closeSendContext();
            releasePermit();
            completion.complete(outcome);
        }

        private void cancel(CancellationException cause) {
            if (terminal) {
                return;
            }
            terminal = true;
            cancelPreparationTimer();
            closePreparationContext();
            closeSendContext();
            if (transformationCompletion != null) {
                transformationCompletion.cancel(false);
                transformationCompletion = null;
            }
            permitPool.cancel(requestId::equals, cause);
            releasePermit();
        }

        private void cancelPreparationTimer() {
            if (preparationTimer != null) {
                preparationTimer.cancel(false);
                preparationTimer = null;
            }
        }

        private void closePreparationContext() {
            if (!preparationContextClosed) {
                preparationContextClosed = true;
                preparationScheduledContext.close();
            }
        }

        private void closeSendContext() {
            if (!sendContextTransferred) {
                sendContextTransferred = true;
                sendScheduledContext.close();
            }
        }

        private void releasePermit() {
            if (permit != null) {
                permit.close();
                permit = null;
            }
        }

        private void releaseLateTransformation(
            TransformedOutputAndResult<ByteBufListProducer> transformed
        ) {
            if (transformed != null && transformed.transformedOutput != null) {
                transformed.transformedOutput.release();
            }
        }
    }

    @AllArgsConstructor
    public static class DeterminedTransformedResponse<T> {
        RetryDirective directive;
        T value;
    }

    public interface RetryVisitor<T> {
        /**
         * Return null to continue trying according to
         * @param arr
         * @return
         */
        TrackedFuture<String,DeterminedTransformedResponse<T>>
        visit(ByteBuf requestBytes, AggregatedRawResponse arr, Throwable t);
    }

    public <T> TrackedFuture<String, T> scheduleRequestLifecycle(
        @NonNull UniqueReplayerRequestKey requestKey,
        @NonNull IReplayContexts.IReplayerHttpTransactionContext context,
        @NonNull Instant preparationStart,
        @NonNull Instant sendStart,
        @NonNull Instant sendEnd,
        @NonNull AsyncPermitPool permitPool,
        @NonNull Supplier<TrackedFuture<String, TransformedOutputAndResult<ByteBufListProducer>>> preparation,
        @NonNull Function<TransformedOutputAndResult<ByteBufListProducer>, RetryVisitor<T>> retryVisitorFactory,
        @NonNull Function<HttpRequestTransformationStatus, T> filteredResultFactory
    ) {
        var requestId = toReplayRequestId(requestKey);
        var runtime = actorRuntime(requestId.session(), context.getChannelKeyContext());
        var filteredResult = new AtomicReference<T>();
        var coordinator = new PreparationCoordinator<>(
            runtime,
            requestId,
            context,
            preparationStart,
            sendStart,
            sendEnd,
            permitPool,
            preparation,
            retryVisitorFactory,
            filteredResultFactory,
            filteredResult
        );
        var targetOutcome = runtime.actor.admitRequest(requestId, sendStart, coordinator.stage());
        coordinator.start();
        CompletionStage<T> result = targetOutcome.thenCompose(outcome ->
            outcome.visit(new TargetOutcome.Visitor<Object, CompletionStage<T>>() {
            @Override
            public CompletionStage<T> onSucceeded(TargetOutcome.Succeeded<Object> succeeded) {
                @SuppressWarnings("unchecked")
                var value = (T) succeeded.value();
                return CompletableFuture.completedFuture(value);
            }

            @Override
            public CompletionStage<T> onFailed(TargetOutcome.Failed<Object> failed) {
                return CompletableFuture.failedFuture(failed.cause());
            }

            @Override
            public CompletionStage<T> onCancelled(TargetOutcome.Cancelled<Object> cancelled) {
                return CompletableFuture.failedFuture(cancelled.cause());
            }

            @Override
            public CompletionStage<T> onFiltered(TargetOutcome.Filtered<Object> filtered) {
                return CompletableFuture.completedFuture(filteredResult.get());
            }
        }));
        return new TextTrackedFuture<>(
            result.toCompletableFuture(),
            () -> "waiting for the connection actor to settle " + requestId
        );
    }

    public TrackedFuture<String, Void> scheduleActorClose(
        @NonNull IReplayContexts.IChannelKeyContext context,
        int sessionNumber,
        @NonNull Instant timestamp
    ) {
        var sessionKey = toConnectionSessionKey(context, sessionNumber);
        var runtime = actorRuntime(sessionKey, context);
        var result = runtime.actor.admitClose(timestamp).thenCompose(RequestSenderOrchestrator::mapSessionOutcome);
        return new TextTrackedFuture<>(
            result.toCompletableFuture(),
            () -> "waiting for ordered actor close for " + sessionKey
        );
    }

    public TrackedFuture<String, Void> abortActor(
        @NonNull IReplayContexts.IChannelKeyContext context,
        int sessionNumber,
        @NonNull CancellationException cause
    ) {
        var sessionKey = toConnectionSessionKey(context, sessionNumber);
        var runtime = actorRuntimes.get(sessionKey);
        if (runtime == null) {
            return TextTrackedFuture.completedFuture(null, () -> "no actor existed for " + sessionKey);
        }
        var result = runtime.actor.abort(cause).thenCompose(RequestSenderOrchestrator::mapAbortOutcome);
        return new TextTrackedFuture<>(
            result.toCompletableFuture(),
            () -> "waiting for actor abort for " + sessionKey
        );
    }

    public <T> TrackedFuture<String, T> scheduleRequest(
        UniqueReplayerRequestKey requestKey,
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Instant start,
        Duration interval,
        ByteBufListProducer packetProducer,
        RetryVisitor<T> visitor
    ) {
        var sessionNumber = requestKey.sourceRequestIndexSessionIdentifier;
        var channelInteractionNum = requestKey.getReplayerRequestIndex();
        var generation = requestKey.trafficStreamKey.getSourceGeneration();
        // TODO: Separate socket connection from the first bytes sent.
        // Ideally, we would match the relative timestamps of when connections were being initiated
        // as well as the period between connection and the first bytes sent. However, this code is a
        // bit too cavalier. It should be tightened at some point by adding a first packet that is empty.
        // Thankfully, given the trickiness of this class, that would be something that should be tracked
        // upstream and should be handled transparently by this class.
        return submitUnorderedWorkToEventLoop(
            ctx.getLogicalEnclosingScope(),
            sessionNumber,
            channelInteractionNum,
            generation,
            connectionReplaySession -> scheduleSendRequestOnConnectionReplaySession(
                ctx,
                connectionReplaySession,
                start,
                interval,
                packetProducer,
                visitor
            )
        );
    }

    /**
     * Immediately cancels a connection without going through the OnlineRadixSorter.
     * Delegates directly to {@link ClientConnectionPool#cancelConnection}.
     */
    public TrackedFuture<String, Void> cancelConnection(IReplayContexts.IChannelKeyContext ctx, int sessionNumber) {
        return clientConnectionPool.cancelConnection(ctx, sessionNumber);
    }

    public TrackedFuture<String, Void> scheduleClose(
        IReplayContexts.IChannelKeyContext ctx,
        int sessionNumber,
        int channelInteractionNum,
        Instant timestamp
    ) {
        var channelKey = ctx.getChannelKey();
        var channelInteraction = new IndexedChannelInteraction(channelKey, channelInteractionNum);
        log.atDebug().setMessage("Scheduling CLOSE for {} at time {}")
            .addArgument(channelInteraction)
            .addArgument(timestamp)
            .log();
        return submitUnorderedWorkToEventLoop(
            ctx,
            sessionNumber,
            channelInteractionNum,
            connectionReplaySession -> scheduleCloseOnConnectionReplaySession(
                ctx,
                connectionReplaySession,
                timestamp,
                sessionNumber,
                channelInteractionNum,
                channelInteraction
            )
        );
    }

    private TrackedFuture<String, Void> bindNettyScheduleToCompletableFuture(EventLoop eventLoop, Instant timestamp) {
        return NettyFutureBinders.bindNettyScheduleToCompletableFuture(eventLoop, getDelayFromNowMs(timestamp));
    }

    private TextTrackedFuture<Void> bindNettyScheduleToCompletableFuture(
        EventLoop eventLoop,
        Instant timestamp,
        TrackedFuture<String, Void> existingFuture
    ) {
        var delayMs = getDelayFromNowMs(timestamp);
        NettyFutureBinders.bindNettyScheduleToCompletableFuture(eventLoop, delayMs, existingFuture.future);
        return new TextTrackedFuture<>(
            existingFuture.future,
            "scheduling to run next send at " + timestamp + " in " + delayMs + "ms"
        );
    }

    private CompletableFuture<Void> bindNettyScheduleToCompletableFuture(
        EventLoop eventLoop,
        Instant timestamp,
        CompletableFuture<Void> cf
    ) {
        return NettyFutureBinders.bindNettyScheduleToCompletableFuture(eventLoop, getDelayFromNowMs(timestamp), cf);
    }

    /**
     * This method will run the callback on the connection's dedicated thread such that all of the executions
     * of the callbacks sent for the connection are in the order defined by channelInteractionNumber, whose
     * values must be of the entire set of ints [0,N] for N work items (so, 0,1,2.  no gaps, no dups).  The
     * onSessionCallback task passed will be called only after all callbacks for previous channelInteractionNumbers
     * have been called.  This method isn't concerned with scheduling items to run at a specific time, that is
     * left up to the callback.
     */
    private <T> TrackedFuture<String, T> submitUnorderedWorkToEventLoop(
        IReplayContexts.IChannelKeyContext ctx,
        int sessionNumber,
        int channelInteractionNumber,
        Function<ConnectionReplaySession, TrackedFuture<String, T>> onSessionCallback
    ) {
        return submitUnorderedWorkToEventLoop(ctx, sessionNumber, channelInteractionNumber, 0, onSessionCallback);
    }

    private <T> TrackedFuture<String, T> submitUnorderedWorkToEventLoop(
        IReplayContexts.IChannelKeyContext ctx,
        int sessionNumber,
        int channelInteractionNumber,
        int generation,
        Function<ConnectionReplaySession, TrackedFuture<String, T>> onSessionCallback
    ) {
        final var replaySession = clientConnectionPool.getCachedSession(ctx, sessionNumber, generation);
        return NettyFutureBinders.bindNettySubmitToTrackableFuture(replaySession.eventLoop)
            .getDeferredFutureThroughHandle((v, t) -> {
                log.atTrace().setMessage("adding work item at slot {} for {} with {}")
                    .addArgument(channelInteractionNumber)
                    .addArgument(replaySession::getChannelKeyContext)
                    .addArgument(replaySession.scheduleSequencer)
                    .log();
                return replaySession.scheduleSequencer.addFutureForWork(
                    channelInteractionNumber,
                    f -> f.thenCompose(
                        voidValue -> {
                            if (replaySession.isCancelled()) {
                                return TextTrackedFuture.failedFuture(
                                    new java.util.concurrent.CancellationException(
                                        "Session cancelled — not scheduling work"
                                            + " for slot " + channelInteractionNumber),
                                    () -> "cancelled session for " + ctx);
                            }
                            return onSessionCallback.apply(replaySession);
                        },
                        () -> "Work callback on replay session"
                    )
                );
            }, () -> "Waiting for sequencer to finish for slot " + channelInteractionNumber);
    }

    private <T> TrackedFuture<String, T> scheduleSendRequestOnConnectionReplaySession(
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        ConnectionReplaySession connectionReplaySession,
        Instant startTime,
        Duration interval,
        ByteBufListProducer packetProducer,
        RetryVisitor<T> visitor
    ) {
        var eventLoop = connectionReplaySession.eventLoop;
        var scheduledContext = ctx.createScheduledContext(startTime);
        int channelInterationNum = ctx.getReplayerRequestKey().getSourceRequestIndex();
        var diagnosticCtx = new IndexedChannelInteraction(
            ctx.getLogicalEnclosingScope().getChannelKey(),
            channelInterationNum
        );
        packetProducer.retain();
        var scheduledContextClosed = new AtomicBoolean(false);
        return scheduleOnConnectionReplaySession(
            diagnosticCtx,
            connectionReplaySession,
            startTime,
            new ChannelTask<>(ChannelTaskType.TRANSMIT, trigger -> trigger.thenCompose(voidVal -> {
                scheduledContextClosed.set(true);
                scheduledContext.close();
                final Supplier<IPacketFinalizingConsumer<AggregatedRawResponse>> senderSupplier =
                    () -> packetConsumerFactory.apply(connectionReplaySession, ctx);
                return sendRequestWithRetries(senderSupplier, eventLoop, packetProducer, startTime, initialRetryDelay,
                    interval, visitor);
            }, () -> "sending packets for request"))
        )
            .whenComplete((v,t) -> {
                if (t != null && !scheduledContextClosed.get()) { scheduledContext.close(); }
                packetProducer.release();
            }, () -> "releasing resources after request completes or is cancelled");
    }

    private TrackedFuture<String, Void> scheduleCloseOnConnectionReplaySession(
        IReplayContexts.IChannelKeyContext ctx,
        ConnectionReplaySession connectionReplaySession,
        Instant timestamp,
        int connectionReplaySessionNum,
        int channelInteractionNum,
        IndexedChannelInteraction channelInteraction
    ) {
        var diagnosticCtx = new IndexedChannelInteraction(ctx.getChannelKey(), channelInteractionNum);
        return scheduleOnConnectionReplaySession(
            diagnosticCtx,
            connectionReplaySession,
            timestamp,
            new ChannelTask<>(ChannelTaskType.CLOSE, tf -> tf.whenComplete((v, t) -> {
                log.atTrace().setMessage("Calling closeConnection at slot {}").addArgument(channelInteraction).log();
                clientConnectionPool.closeConnection(ctx, connectionReplaySessionNum);
            }, () -> "Close connection"))
        );
    }

    private <T> TrackedFuture<String, T> scheduleOnConnectionReplaySession(
        IndexedChannelInteraction channelInteraction,
        ConnectionReplaySession channelFutureAndRequestSchedule,
        Instant atTime,
        ChannelTask<T> task
    ) {
        log.atDebug().setMessage("{} scheduling {} at {}")
            .addArgument(channelInteraction)
            .addArgument(task.kind)
            .addArgument(atTime)
            .log();

        var schedule = channelFutureAndRequestSchedule.schedule;
        var eventLoop = channelFutureAndRequestSchedule.eventLoop;

        var wasEmpty = schedule.isEmpty();
        assert wasEmpty || !atTime.isBefore(schedule.peekFirstItem().startTime)
            : "Per-connection TrafficStream ordering should force a time ordering on incoming requests";
        var workPointTrigger = schedule.appendTaskTrigger(atTime, task.kind).scheduleFuture;
        var workFuture = task.getRunnable().apply(workPointTrigger);
        log.atTrace().setMessage("{} added a scheduled event at {}... {}")
            .addArgument(channelInteraction)
            .addArgument(atTime)
            .addArgument(schedule)
            .log();
        if (wasEmpty) {
            bindNettyScheduleToCompletableFuture(eventLoop, atTime, workPointTrigger.future);
        }

        workFuture.map(f -> f.whenComplete((v, t) -> {
            var itemStartTimeOfPopped = schedule.removeFirstItem();
            if (itemStartTimeOfPopped == null) {
                // Already drained by drainWithCancellation — nothing to reschedule.
                return;
            }
            assert atTime.equals(itemStartTimeOfPopped)
                : "Expected to have popped the item to match the start time for the responseFuture that finished";
            log.atDebug().setMessage("{} responseFuture completed - checking {} for the next item to schedule")
                .addArgument(channelInteraction::toString)
                .addArgument(schedule)
                .log();
            Optional.ofNullable(schedule.peekFirstItem())
                .ifPresent(kvp -> bindNettyScheduleToCompletableFuture(eventLoop, kvp.startTime, kvp.scheduleFuture));
        }), () -> "");

        return workFuture;
    }

    private Instant now() {
        return Instant.now();
    }

    private Duration getDelayFromNowMs(Instant to) {
        return Duration.ofMillis(Math.max(0, Duration.between(now(), to).toMillis()));
    }

    private Duration doubleRetryDelayCapped(Duration d) {
        return Duration.ofMillis(Math.min(d.multipliedBy(2).toMillis(), maxRetryDelay.toMillis()));
    }

    private ActorRuntime actorRuntime(
        ConnectionSessionKey key,
        IReplayContexts.IChannelKeyContext channelContext
    ) {
        return actorRuntimes.computeIfAbsent(key, ignored -> new ActorRuntime(key, channelContext));
    }

    private static ReplayRequestId toReplayRequestId(UniqueReplayerRequestKey requestKey) {
        return new ReplayRequestId(
            new ConnectionSessionKey(
                new SourceConnectionKey(
                    requestKey.trafficStreamKey.getNodeId(),
                    requestKey.trafficStreamKey.getConnectionId()
                ),
                requestKey.sourceRequestIndexSessionIdentifier,
                requestKey.trafficStreamKey.getSourceGeneration()
            ),
            requestKey.getReplayerRequestIndex()
        );
    }

    private static ConnectionSessionKey toConnectionSessionKey(
        IReplayContexts.IChannelKeyContext context,
        int sessionNumber
    ) {
        return new ConnectionSessionKey(
            new SourceConnectionKey(context.getNodeId(), context.getConnectionId()),
            sessionNumber,
            context.getChannelKey().getSourceGeneration()
        );
    }

    private CompletionStage<Void> closeRuntimeChannel(ActorRuntime runtime) {
        return clientConnectionPool.closeChannelForSession(runtime.session).future.handle((channel, failure) -> {
            if (failure != null) {
                throw new CompletionException(unwrap(failure));
            }
            return null;
        });
    }

    private static CompletionStage<Void> mapSessionOutcome(SessionOutcome outcome) {
        return outcome.visit(new SessionOutcome.Visitor<>() {
            @Override
            public CompletionStage<Void> onClosed(SessionOutcome.Closed closed) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> onAborted(SessionOutcome.Aborted aborted) {
                return CompletableFuture.failedFuture(aborted.cause());
            }

            @Override
            public CompletionStage<Void> onFailed(SessionOutcome.Failed failed) {
                return CompletableFuture.failedFuture(failed.cause());
            }
        });
    }

    private static CompletionStage<Void> mapAbortOutcome(SessionOutcome outcome) {
        return outcome.visit(new SessionOutcome.Visitor<>() {
            @Override
            public CompletionStage<Void> onClosed(SessionOutcome.Closed closed) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> onAborted(SessionOutcome.Aborted aborted) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> onFailed(SessionOutcome.Failed failed) {
                return CompletableFuture.failedFuture(failed.cause());
            }
        });
    }

    private static Throwable unwrap(Throwable throwable) {
        var current = throwable;
        while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null)
        {
            current = current.getCause();
        }
        return current;
    }

    private <T> TrackedFuture<String, T>
    sendRequestWithRetries(Supplier<IPacketFinalizingConsumer<AggregatedRawResponse>> senderSupplier,
                           EventLoop eventLoop,
                           ByteBufListProducer packetProducer,
                           Instant referenceStartTime,
                           Duration nextRetryDelay,
                           Duration interval,
                           RetryVisitor<T> visitor)
    {
        if (eventLoop.isShuttingDown()) {
            return TextTrackedFuture.failedFuture(new IllegalStateException("EventLoop is shutting down"),
                () -> "sendRequestWithRetries is failing due to the pending shutdown of the EventLoop");
        }
        var attempt = packetProducer.newAttempt();
        var byteBufList = attempt.packets();
        return sendPackets(
            senderSupplier.get(),
            eventLoop,
            byteBufList.streamUnretained().iterator(),
            referenceStartTime,
            interval,
            new AtomicInteger()
        )
            .getDeferredFutureThroughHandle((response, t) -> {
                    try (var requestBytesHolder = RefSafeHolder.create(byteBufList.asCompositeByteBufRetained())) {
                        return visitor.visit(requestBytesHolder.get(), response, t);
                    }
                },
                () -> "checking response to determine if the request should be retried")
            .whenComplete((response, failure) -> attempt.close(), () -> "releasing the request attempt payload")
            .getDeferredFutureThroughHandle((dtr,t) -> {
                if (t != null) {
                    return TextTrackedFuture.failedFuture(t, () -> "failed future");
                }
                if (dtr.directive == RetryDirective.RETRY) {
                    var computedStartTime = referenceStartTime.plus(nextRetryDelay);
                    // Ensure retry is not scheduled in the past to prevent tight retry loops
                    // that monopolize event loop threads when referenceStartTime is far in the past
                    var now = now();
                    var newStartTime = computedStartTime.isBefore(now)
                        ? now.plus(nextRetryDelay)
                        : computedStartTime;
                    log.atDebug().setMessage("Making request scheduled at {}").addArgument(newStartTime).log();
                    var schedulingDelay = Duration.between(now(), newStartTime);
                    return NettyFutureBinders.bindNettyScheduleToCompletableFuture(
                        eventLoop, schedulingDelay)
                        .thenCompose(
                            v -> sendRequestWithRetries(senderSupplier, eventLoop, packetProducer, newStartTime,
                                doubleRetryDelayCapped(nextRetryDelay), interval, visitor),
                            () -> "retrying request with delay of " + schedulingDelay);
                } else {
                    return TextTrackedFuture.completedFuture(dtr.value,
                        () -> "done retrying and returning received response");
                }
            }, () -> "determining if the response must be retried or if it should be returned now");
    }

    private TrackedFuture<String, AggregatedRawResponse> sendPackets(
        IPacketFinalizingConsumer<AggregatedRawResponse> packetReceiver,
        EventLoop eventLoop,
        Iterator<ByteBuf> iterator,
        Instant referenceStartAt,
        Duration interval,
        AtomicInteger requestPacketCounter
    ) {
        final var oldCounter = requestPacketCounter.getAndIncrement();
        log.atTrace().setMessage("sendNextPartAndContinue: packetCounter={}").addArgument(oldCounter).log();
        assert iterator.hasNext() : "Should not have called this with no items to send";

        var consumeFuture = packetReceiver.consumeBytes(iterator.next().retainedDuplicate());
        if (iterator.hasNext()) {
            return consumeFuture.thenCompose(
                tf -> NettyFutureBinders.bindNettyScheduleToCompletableFuture(
                        eventLoop,
                        Duration.between(now(), referenceStartAt.plus(interval.multipliedBy(requestPacketCounter.get())))
                    )
                    .thenCompose(
                        v -> sendPackets(packetReceiver, eventLoop, iterator, referenceStartAt, interval, requestPacketCounter),
                        () -> "sending next packet"
                    ),
                () -> "recursing, once ready"
            );
        } else {
            return consumeFuture.getDeferredFutureThroughHandle(
                (v, t) -> packetReceiver.finalizeRequest(),
                () -> "finalizing, once ready"
            );
        }
    }
}
