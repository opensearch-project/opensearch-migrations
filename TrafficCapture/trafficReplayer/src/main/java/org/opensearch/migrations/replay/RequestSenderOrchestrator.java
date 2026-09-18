package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.AttemptPayload;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.OwnedPreparedRequest;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.lifecycle.ActorMailbox;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ConnectionActor;
import org.opensearch.migrations.replay.lifecycle.NettyEventLoopActorMailbox;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.replay.lifecycle.ReplayTransactionRegistry;
import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.RefSafeHolder;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the per-connection actor runtimes that prepare, order, send, retry, and close target traffic.
 * Request and close admission occurs before asynchronous preparation, so the actor's FIFO is the
 * connection ordering mechanism.
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
    @FunctionalInterface
    public interface FatalReplayHandler {
        void onFatal(Error failure);
    }

    private static FatalReplayHandler productionFatalHandler(ReplayProcessFatalHandler.Metrics fatalMetrics) {
        return new ReplayProcessFatalHandler(
            ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED,
            fatalMetrics,
            Runtime.getRuntime()::halt
        );
    }

    private final ClientConnectionPool clientConnectionPool;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory;
    private final Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger;
    private final ConnectionActor.Metrics actorMetrics;
    private final TargetExchangeState.Metrics targetExchangeMetrics;
    private final ResourceOwnership.Metrics resourceOwnershipMetrics;
    private final FatalReplayHandler fatalReplayHandler;
    private final ConcurrentHashMap<ConnectionSessionKey, ActorRuntime> actorRuntimes = new ConcurrentHashMap<>();
    private final Object actorLifecycleLock = new Object();
    private final AtomicReference<ReplayTransaction.RunwayLossReason> globalRunwayLossReason =
        new AtomicReference<>();
    private final AtomicReference<Error> fatalFailure = new AtomicReference<>();
    private ActorShutdown actorShutdown;

    private static final class ActorShutdown {
        private final CancellationException cause;
        private final CompletableFuture<Void> completion;

        private ActorShutdown(CancellationException cause, CompletableFuture<Void> completion) {
            this.cause = cause;
            this.completion = completion;
        }
    }

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
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        ReplayProcessFatalHandler.Metrics fatalMetrics
    ) {
        this(
            clientConnectionPool,
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            ConnectionActor.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ResourceOwnership.Metrics.NOOP,
            fatalMetrics
        );
    }

    RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        ConnectionActor.Metrics actorMetrics,
        TargetExchangeState.Metrics targetExchangeMetrics,
        ReplayProcessFatalHandler.Metrics fatalMetrics
    ) {
        this(
            clientConnectionPool,
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            actorMetrics,
            targetExchangeMetrics,
            ResourceOwnership.Metrics.NOOP,
            fatalMetrics
        );
    }

    public RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        ConnectionActor.Metrics actorMetrics,
        TargetExchangeState.Metrics targetExchangeMetrics,
        ResourceOwnership.Metrics resourceOwnershipMetrics,
        ReplayProcessFatalHandler.Metrics fatalMetrics
    ) {
        this(
            clientConnectionPool,
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            actorMetrics,
            targetExchangeMetrics,
            resourceOwnershipMetrics,
            productionFatalHandler(fatalMetrics)
        );
    }

    public RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        ConnectionActor.Metrics actorMetrics,
        TargetExchangeState.Metrics targetExchangeMetrics,
        ResourceOwnership.Metrics resourceOwnershipMetrics,
        FatalReplayHandler fatalReplayHandler
    ) {
        this(
            clientConnectionPool,
            Duration.ofMillis(100),
            Duration.ofSeconds(300),
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            actorMetrics,
            targetExchangeMetrics,
            resourceOwnershipMetrics,
            fatalReplayHandler
        );
    }

    RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        ReplayProcessFatalHandler.Metrics fatalMetrics
    ) {
        this(
            clientConnectionPool,
            initialRetryDelay,
            maxRetryDelay,
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            ConnectionActor.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ResourceOwnership.Metrics.NOOP,
            fatalMetrics
        );
    }

    RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        ConnectionActor.Metrics actorMetrics,
        TargetExchangeState.Metrics targetExchangeMetrics,
        ResourceOwnership.Metrics resourceOwnershipMetrics,
        ReplayProcessFatalHandler.Metrics fatalMetrics
    ) {
        this(
            clientConnectionPool,
            initialRetryDelay,
            maxRetryDelay,
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            actorMetrics,
            targetExchangeMetrics,
            resourceOwnershipMetrics,
            productionFatalHandler(fatalMetrics)
        );
    }

    RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        BiFunction<ConnectionReplaySession, IReplayContexts.IReplayerHttpTransactionContext, IPacketFinalizingConsumer<AggregatedRawResponse>> packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        ConnectionActor.Metrics actorMetrics,
        TargetExchangeState.Metrics targetExchangeMetrics,
        ResourceOwnership.Metrics resourceOwnershipMetrics,
        FatalReplayHandler fatalReplayHandler
    ) {
        this.clientConnectionPool = clientConnectionPool;
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.packetConsumerFactory = packetConsumerFactory;
        this.sessionTerminationAcknowledger = sessionTerminationAcknowledger;
        this.actorMetrics = actorMetrics;
        this.targetExchangeMetrics = targetExchangeMetrics;
        this.resourceOwnershipMetrics = resourceOwnershipMetrics;
        this.fatalReplayHandler = Objects.requireNonNull(fatalReplayHandler);
    }

    public static Function<ConnectionSessionKey, CompletionStage<Void>> noSourceTerminationObligations() {
        return ignored -> CompletableFuture.completedFuture(null);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable,
                                                  long initialDelay,
                                                  long delay,
                                                  TimeUnit timeUnit) {
        return clientConnectionPool.scheduleAtFixedRate(runnable, initialDelay, delay, timeUnit);
    }

    public enum RetryDirective {
        DONE, RETRY
    }

    private final class PreparedActorRequest implements AutoCloseable {
        private final IReplayContexts.IReplayerHttpTransactionContext context;
        private final Instant start;
        private final Duration interval;
        private final OwnedPreparedRequest packetProducer;
        private final RetryVisitor<Object> visitor;
        private final AsyncPermitPool.Permit permit;
        private final IReplayContexts.IScheduledContext scheduledContext;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();

        private PreparedActorRequest(
            IReplayContexts.IReplayerHttpTransactionContext context,
            Instant start,
            Duration interval,
            OwnedPreparedRequest packetProducer,
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
                        visitor.close();
                    } finally {
                        try {
                            packetProducer.close();
                        } finally {
                            permit.close();
                        }
                    }
                }
            }
        }
    }

    private final class ActorRuntime {
        private final ConnectionSessionKey key;
        private final ConnectionReplaySession session;
        private final ActorMailbox mailbox;
        private final ConnectionActor<PreparedActorRequest, Object> actor;
        private final RuntimeTargetExchange exchange;
        private final ReplayTransactionRegistry transactions;
        private final CompletableFuture<SessionOutcome> terminationOwner = new CompletableFuture<>();
        private final CompletionStage<SessionOutcome> termination = terminationOwner.minimalCompletionStage();
        private boolean actorTerminated;

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
            this.mailbox = new NettyEventLoopActorMailbox(session.eventLoop);
            this.transactions = new ReplayTransactionRegistry(key, mailbox);
            this.exchange = new RuntimeTargetExchange(this);
            this.actor = new ConnectionActor<>(
                key,
                mailbox,
                exchange,
                actorMetrics
            );
            actor.termination().whenComplete((outcome, failure) ->
                mailbox.execute(() -> onActorTerminated(outcome, failure))
            );
            // The event loop is both the channel's thread and the actor's mailbox, so once it terminates
            // nothing can legally advance this session. The process-fatal handler deliberately does not
            // transfer cleanup authority or enter normal shutdown.
            session.eventLoop.terminationFuture().addListener(ignored -> onEventLoopTerminated());
        }

        private void onEventLoopTerminated() {
            if (terminationOwner.isDone()) {
                return;
            }
            var fatalError = new Error(
                "the event loop for " + key + " terminated before the session finished"
            );
            signalFatal(fatalError);
        }

        private CompletionStage<SessionOutcome> termination() {
            return termination;
        }

        private void onActorTerminated(SessionOutcome outcome, Throwable actorFailure) {
            if (actorTerminated) {
                return;
            }
            actorTerminated = true;
            log.atDebug()
                .setMessage("Connection actor settled for {}; outcome={}; failure={}")
                .addArgument(key)
                .addArgument(outcome)
                .addArgument(actorFailure)
                .log();
            clientConnectionPool.invalidateSession(
                key.connection().connectionId(),
                key.sessionNumber(),
                key.sourceGeneration()
            );
            // A session that closed normally still has intake behind it, so its transactions can still
            // settle and the registry should wait for them.  An aborted or failed session has nothing
            // left to settle them, so they have to be cancelled or termination would never complete.
            if (actorFailure != null) {
                transactions.cancelOutstanding(
                    new CancellationException("connection session failed: " + unwrap(actorFailure))
                );
                failTermination(actorFailure);
                return;
            }
            if (outcome instanceof SessionOutcome.Aborted aborted) {
                transactions.cancelOutstanding(aborted.cause());
            } else if (outcome instanceof SessionOutcome.Failed failed) {
                transactions.cancelOutstanding(
                    new CancellationException("connection session failed: " + failed.cause())
                );
            }
            transactions.beginTermination().whenComplete((ignored, transactionFailure) ->
                mailbox.execute(() -> {
                    log.atDebug()
                        .setMessage("Transaction registry settled for {}; failure={}")
                        .addArgument(key)
                        .addArgument(transactionFailure)
                        .log();
                    if (transactionFailure != null) {
                        failTermination(transactionFailure);
                        return;
                    }
                    if (outcome instanceof SessionOutcome.Failed failed) {
                        settleTermination(failed);
                        return;
                    }
                    acknowledgeSourceTermination(outcome);
                })
            );
        }

        /**
         * Every terminal path has to drop the runtime, not just the successful one.  A retained entry
         * would keep the session in shutdown's set of live actors forever and would shadow any later
         * session that reuses the key.
         */
        private void settleTermination(SessionOutcome outcome) {
            actorRuntimes.remove(key, this);
            terminationOwner.complete(outcome);
        }

        private void failTermination(Throwable failure) {
            actorRuntimes.remove(key, this);
            terminationOwner.completeExceptionally(unwrap(failure));
        }

        private void acknowledgeSourceTermination(SessionOutcome outcome) {
            CompletionStage<Void> acknowledgement;
            try {
                log.atDebug()
                    .setMessage("Acknowledging source termination for {}; outcome={}")
                    .addArgument(key)
                    .addArgument(outcome)
                    .log();
                acknowledgement = Objects.requireNonNull(
                    sessionTerminationAcknowledger.apply(key),
                    "session termination acknowledger returned no completion stage"
                );
            } catch (Throwable t) {
                failTermination(t);
                return;
            }
            acknowledgement.whenComplete((ignored, failure) ->
                mailbox.execute(() -> {
                    log.atDebug()
                        .setMessage("Source termination acknowledgement settled for {}; failure={}")
                        .addArgument(key)
                        .addArgument(failure)
                        .log();
                    if (failure != null) {
                        failTermination(failure);
                        return;
                    }
                    settleTermination(outcome);
                })
            );
        }
    }

    private void signalFatal(Error failure) {
        if (!fatalFailure.compareAndSet(null, failure)) {
            return;
        }
        try {
            fatalReplayHandler.onFatal(failure);
        } catch (Throwable handlerFailure) {
            failure.addSuppressed(handlerFailure);
            log.atError()
                .setCause(handlerFailure)
                .setMessage("The fatal replay handler failed while processing {}")
                .addArgument(failure::getMessage)
                .log();
        }
    }

    private final class RuntimeTargetExchange implements ConnectionActor.TargetExchange<PreparedActorRequest, Object> {
        private final ActorRuntime runtime;
        private final Map<ScheduledFuture<?>, CompletableFuture<Void>> cancellableSchedules = new LinkedHashMap<>();
        private final AtomicReference<AttemptPayload> activeAttempt = new AtomicReference<>();
        private CompletableFuture<TargetOutcome<Object>> activeExchange;
        private IPacketFinalizingConsumer<AggregatedRawResponse> activePacketReceiver;
        private CancellationException cancellationCause;
        private TargetExchangeState.Phase phase;

        private RuntimeTargetExchange(ActorRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        @SuppressWarnings("java:S1181") // Startup failure must settle the target outcome even for Errors.
        public CompletionStage<TargetOutcome<Object>> execute(PreparedActorRequest preparedRequest) {
            preparedRequest.beginExecution();
            if (cancellationCause != null) {
                return CompletableFuture.completedFuture(new TargetOutcome.Cancelled<>(cancellationCause));
            }
            try {
                return normalizeExchange(startExchange(preparedRequest));
            } catch (Throwable t) {
                clearPhase();
                return CompletableFuture.completedFuture(new TargetOutcome.Failed<>(unwrap(t)));
            }
        }

        @SuppressWarnings("unchecked")
        private TrackedFuture<String, DeterminedTransformedResponse<Object>> startExchange(
            PreparedActorRequest preparedRequest
        ) {
            return (TrackedFuture<String, DeterminedTransformedResponse<Object>>)
                (TrackedFuture<?, ?>) sendRequestWithRetries(
                () -> packetConsumerFactory.apply(runtime.session, preparedRequest.context),
                runtime.session.eventLoop,
                preparedRequest.packetProducer,
                preparedRequest.start,
                initialRetryDelay,
                preparedRequest.interval,
                preparedRequest.visitor
            );
        }

        private CompletionStage<TargetOutcome<Object>> normalizeExchange(
            TrackedFuture<String, DeterminedTransformedResponse<Object>> exchange
        ) {
            var normalized = new CompletableFuture<TargetOutcome<Object>>();
            activeExchange = normalized;
            exchange.future.whenComplete((result, failure) -> settleNormalizedExchange(normalized, result, failure));
            normalized.whenComplete((value, failure) ->
                runtime.session.eventLoop.execute(() -> retireNormalizedExchange(normalized))
            );
            return normalized;
        }

        private void settleNormalizedExchange(
            CompletableFuture<TargetOutcome<Object>> normalized,
            DeterminedTransformedResponse<Object> result,
            Throwable failure
        ) {
            if (failure != null) {
                var cause = unwrap(failure);
                normalized.complete(
                    cause instanceof CancellationException cancellation
                        ? new TargetOutcome.Cancelled<>(cancellation)
                        : new TargetOutcome.Failed<>(cause)
                );
                return;
            }
            if (result == null) {
                normalized.complete(new TargetOutcome.Failed<>(
                    new IllegalStateException("target exchange completed without a result")
                ));
                return;
            }
            try {
                result.transferOwnership();
            } catch (RuntimeException transferFailure) {
                normalized.complete(new TargetOutcome.Failed<>(transferFailure));
                return;
            }
            if (!normalized.complete(new TargetOutcome.Succeeded<>(result.value))) {
                result.releaseTransferredValue();
            }
        }

        private void retireNormalizedExchange(CompletableFuture<TargetOutcome<Object>> normalized) {
            if (activeExchange == normalized) {
                activeExchange = null;
                if (cancellationCause == null) {
                    clearPhaseOnOwner();
                }
            }
        }

        @Override
        public CompletionStage<Void> close() {
            return closeRuntimeChannel();
        }

        @Override
        public CompletionStage<Void> abort(CancellationException cause) {
            if (cancellationCause == null) {
                cancellationCause = cause;
            }
            transitionPhase(TargetExchangeState.Phase.ABORTING);
            cancelScheduledWork(cancellationCause);
            cancelActivePacketReceiver(cancellationCause);
            var attemptReleaseFailure = releaseActiveAttempt();
            var exchangeToJoin = activeExchange;
            if (exchangeToJoin != null) {
                exchangeToJoin.complete(new TargetOutcome.Cancelled<>(cancellationCause));
            }
            return cancelRuntimeChannel()
                .handle((ignored, channelFailure) -> {
                    var failure = channelFailure == null ? null : unwrap(channelFailure);
                    if (failure == null) {
                        failure = attemptReleaseFailure;
                    } else {
                        addSuppressed(failure, attemptReleaseFailure);
                    }
                    if (failure != null) {
                        throw new CompletionException(failure);
                    }
                    return null;
                })
                .thenCompose(ignored ->
                    exchangeToJoin == null
                        ? CompletableFuture.<Void>completedFuture(null)
                        : exchangeToJoin.handle((outcome, failure) -> null)
                )
                .whenComplete((ignored, failure) -> clearPhase());
        }

        private CompletionStage<Void> cancelRuntimeChannel() {
            return runtime.session.cancelAndClose(cancellationCause).future.handle((channel, failure) -> {
                if (failure != null) {
                    throw new CompletionException(unwrap(failure));
                }
                return null;
            });
        }

        private void cancelActivePacketReceiver(CancellationException cause) {
            var packetReceiver = activePacketReceiver;
            activePacketReceiver = null;
            if (packetReceiver != null) {
                packetReceiver.abort(cause);
            }
        }

        private void cancelScheduledWork(CancellationException cause) {
            var schedules = List.copyOf(cancellableSchedules.entrySet());
            cancellableSchedules.clear();
            for (var entry : schedules) {
                entry.getKey().cancel(false);
                entry.getValue().completeExceptionally(cause);
            }
        }

        private CompletionStage<Void> closeRuntimeChannel() {
            return clientConnectionPool.closeChannelForSession(runtime.session).future.handle((channel, failure) -> {
                if (failure != null) {
                    throw new CompletionException(unwrap(failure));
                }
                return null;
            });
        }

        private <T> TrackedFuture<String, DeterminedTransformedResponse<T>> sendRequestWithRetries(
            Supplier<IPacketFinalizingConsumer<AggregatedRawResponse>> senderSupplier,
            EventLoop eventLoop,
            OwnedPreparedRequest packetProducer,
            Instant referenceStartTime,
            Duration nextRetryDelay,
            Duration interval,
            RetryVisitor<T> visitor
        ) {
            transitionPhase(TargetExchangeState.Phase.STARTING_ATTEMPT);
            TrackedFuture<String, DeterminedTransformedResponse<T>> startRejection =
                rejectUnavailableAttemptStart(eventLoop);
            if (startRejection != null) {
                return startRejection;
            }
            var attempt = packetProducer.newAttempt();
            if (!activeAttempt.compareAndSet(null, attempt)) {
                attempt.close();
                return TextTrackedFuture.failedFuture(
                    new IllegalStateException("another target request attempt is still active"),
                    () -> "sendRequestWithRetries rejected overlapping request attempts"
                );
            }
            final IPacketFinalizingConsumer<AggregatedRawResponse> packetReceiver;
            try {
                packetReceiver = Objects.requireNonNull(senderSupplier.get(), "sender supplier returned null");
            } catch (Throwable t) {
                addSuppressed(t, releaseAttempt(attempt));
                return TextTrackedFuture.failedFuture(
                    t,
                    () -> "sendRequestWithRetries failed while creating the target request sender"
                );
            }
            var byteBufList = attempt.packets();
            final TrackedFuture<String, AggregatedRawResponse> sendFuture;
            try {
                activePacketReceiver = packetReceiver;
                transitionPhase(TargetExchangeState.Phase.SENDING_REQUEST);
                sendFuture = Objects.requireNonNull(
                    sendPackets(
                        packetReceiver,
                        eventLoop,
                        byteBufList.streamUnretained().iterator(),
                        referenceStartTime,
                        interval,
                        new AtomicInteger()
                    ),
                    "packet sender returned null"
                );
            } catch (Throwable t) {
                if (activePacketReceiver == packetReceiver) {
                    activePacketReceiver = null;
                }
                addSuppressed(t, releaseAttempt(attempt));
                return TextTrackedFuture.failedFuture(
                    t,
                    () -> "sendRequestWithRetries failed while starting the packet send"
                );
            }
            return sendFuture
                .getDeferredFutureThroughHandle((response, t) -> {
                        if (cancellationCause != null) {
                            return TextTrackedFuture.failedFuture(
                                cancellationCause,
                                () -> "request exchange was cancelled before evaluating its response"
                            );
                        }
                        transitionPhase(TargetExchangeState.Phase.EVALUATING_RETRY);
                        try (var requestBytesHolder = RefSafeHolder.create(byteBufList.asCompositeByteBufRetained())) {
                            return visitor.visit(requestBytesHolder.get(), response, t);
                        }
                    },
                    () -> "checking response to determine if the request should be retried")
                .whenComplete((response, failure) -> {
                    var releaseFailure = releaseAttempt(attempt);
                    if (releaseFailure != null) {
                        if (failure == null) {
                            throw new CompletionException(releaseFailure);
                        }
                        addSuppressed(unwrap(failure), releaseFailure);
                    }
                    if (activePacketReceiver == packetReceiver) {
                        activePacketReceiver = null;
                    }
                }, () -> "releasing the request attempt payload")
                .getDeferredFutureThroughHandle((dtr, t) -> retryIfNeeded(
                    dtr,
                    t,
                    senderSupplier,
                    eventLoop,
                    packetProducer,
                    referenceStartTime,
                    nextRetryDelay,
                    interval,
                    visitor
                ), () -> "determining if the response must be retried or if it should be returned now");
        }

        private <T> TrackedFuture<String, DeterminedTransformedResponse<T>> rejectUnavailableAttemptStart(
            EventLoop eventLoop
        ) {
            if (cancellationCause != null) {
                return TextTrackedFuture.failedFuture(
                    cancellationCause,
                    () -> "request exchange was cancelled before another attempt could start"
                );
            }
            if (eventLoop.isShuttingDown()) {
                return TextTrackedFuture.failedFuture(
                    new IllegalStateException("EventLoop is shutting down"),
                    () -> "sendRequestWithRetries is failing due to the pending shutdown of the EventLoop"
                );
            }
            return null;
        }

        private <T> TrackedFuture<String, DeterminedTransformedResponse<T>> retryIfNeeded(
            DeterminedTransformedResponse<T> result,
            Throwable failure,
            Supplier<IPacketFinalizingConsumer<AggregatedRawResponse>> senderSupplier,
            EventLoop eventLoop,
            OwnedPreparedRequest packetProducer,
            Instant referenceStartTime,
            Duration nextRetryDelay,
            Duration interval,
            RetryVisitor<T> visitor
        ) {
            if (cancellationCause != null) {
                addSuppressed(cancellationCause, closeResult(result));
                return TextTrackedFuture.failedFuture(
                    cancellationCause,
                    () -> "request exchange was cancelled while evaluating a retry"
                );
            }
            if (failure != null) {
                var cause = unwrap(failure);
                addSuppressed(cause, closeResult(result));
                return TextTrackedFuture.failedFuture(cause, () -> "failed future");
            }
            if (result.directive != RetryDirective.RETRY) {
                return TextTrackedFuture.completedFuture(
                    result,
                    () -> "done retrying and returning received response"
                );
            }
            var releaseFailure = closeResult(result);
            if (releaseFailure != null) {
                return TextTrackedFuture.failedFuture(
                    releaseFailure,
                    () -> "failed to release a completed retry decision"
                );
            }

            var computedStartTime = referenceStartTime.plus(nextRetryDelay);
            var currentTime = Instant.now();
            var newStartTime = computedStartTime.isBefore(currentTime)
                ? currentTime.plus(nextRetryDelay)
                : computedStartTime;
            log.atDebug().setMessage("Making request scheduled at {}").addArgument(newStartTime).log();
            var schedulingDelay = Duration.between(Instant.now(), newStartTime);
            transitionPhase(TargetExchangeState.Phase.RETRY_DELAY);
            return scheduleCancellable(eventLoop, schedulingDelay, "retry")
                .thenCompose(
                    ignored -> sendRequestWithRetries(
                        senderSupplier,
                        eventLoop,
                        packetProducer,
                        newStartTime,
                        doubleRetryDelayCapped(nextRetryDelay),
                        interval,
                        visitor
                    ),
                    () -> "retrying request with delay of " + schedulingDelay
                );
        }

        private TrackedFuture<String, Void> scheduleCancellable(
            EventLoop eventLoop,
            Duration delay,
            String operation
        ) {
            if (cancellationCause != null) {
                return TextTrackedFuture.failedFuture(
                    cancellationCause,
                    () -> operation + " schedule was cancelled before admission"
                );
            }
            if (eventLoop.isShuttingDown()) {
                return TextTrackedFuture.failedFuture(
                    new CancellationException("event loop is already shutting down"),
                    () -> operation + " schedule was rejected because the event loop is shutting down"
                );
            }

            var completion = new CompletableFuture<Void>();
            var delayMillis = Math.max(0, delay.toMillis());
            final ScheduledFuture<?> scheduled;
            try {
                scheduled = eventLoop.schedule(() -> completion.complete(null), delayMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return TextTrackedFuture.failedFuture(
                    new CancellationException(operation + " schedule was rejected by a terminated event loop"),
                    () -> operation + " schedule was rejected because the event loop already terminated"
                );
            }
            // The task body is the only thing that completes this gate on the happy path, and an event
            // loop that shuts down cancels its pending scheduled tasks without ever running them.  The
            // isShuttingDown check above races with shutdown, so the cancellation itself has to complete
            // the gate or the whole exchange would wait on it forever.
            scheduled.addListener(f -> {
                if (f.isCancelled()) {
                    completion.completeExceptionally(
                        new CancellationException(operation + " schedule was cancelled before it could run")
                    );
                } else if (!f.isSuccess()) {
                    completion.completeExceptionally(f.cause());
                }
            });
            cancellableSchedules.put(scheduled, completion);
            completion.whenComplete((ignored, failure) -> cancellableSchedules.remove(scheduled));
            if (cancellationCause != null) {
                scheduled.cancel(false);
                completion.completeExceptionally(cancellationCause);
            }
            return new TextTrackedFuture<>(
                completion,
                () -> operation + " scheduled in " + delay + " (clipped: " + delayMillis + "ms)"
            );
        }

        private Duration doubleRetryDelayCapped(Duration delay) {
            return Duration.ofMillis(Math.min(delay.multipliedBy(2).toMillis(), maxRetryDelay.toMillis()));
        }

        private Throwable releaseActiveAttempt() {
            var attempt = activeAttempt.getAndSet(null);
            return attempt == null ? null : closeResource(attempt);
        }

        private Throwable releaseAttempt(AttemptPayload attempt) {
            return activeAttempt.compareAndSet(attempt, null) ? closeResource(attempt) : null;
        }

        private Throwable closeResult(DeterminedTransformedResponse<?> result) {
            return result == null ? null : closeResource(result);
        }

        @SuppressWarnings("java:S1181") // Cleanup failure is aggregated without abandoning later releases.
        private Throwable closeResource(AutoCloseable resource) {
            try {
                resource.close();
                return null;
            } catch (Exception | Error e) {
                return e;
            }
        }

        private void addSuppressed(Throwable failure, Throwable additionalFailure) {
            if (additionalFailure != null && additionalFailure != failure) {
                failure.addSuppressed(additionalFailure);
            }
        }

        private TrackedFuture<String, AggregatedRawResponse> sendPackets(
            IPacketFinalizingConsumer<AggregatedRawResponse> packetReceiver,
            EventLoop eventLoop,
            Iterator<ByteBuf> iterator,
            Instant referenceStartAt,
            Duration interval,
            AtomicInteger requestPacketCounter
        ) {
            if (cancellationCause != null) {
                return TextTrackedFuture.failedFuture(
                    cancellationCause,
                    () -> "packet send was cancelled before the next packet"
                );
            }
            final var oldCounter = requestPacketCounter.getAndIncrement();
            log.atTrace().setMessage("sendNextPartAndContinue: packetCounter={}").addArgument(oldCounter).log();
            assert iterator.hasNext() : "Should not have called this with no items to send";

            var packet = iterator.next().retainedDuplicate();
            final TrackedFuture<String, Void> consumeFuture;
            try {
                consumeFuture = Objects.requireNonNull(
                    packetReceiver.consumeBytes(packet),
                    "packet consumer returned null"
                );
            } catch (Throwable t) {
                try {
                    packet.release();
                } catch (Throwable releaseFailure) {
                    addSuppressed(t, releaseFailure);
                }
                return TextTrackedFuture.failedFuture(
                    t,
                    () -> "packet consumer failed synchronously"
                );
            }
            if (iterator.hasNext()) {
                return consumeFuture.thenCompose(
                    ignored -> scheduleCancellable(
                            eventLoop,
                            Duration.between(
                                Instant.now(),
                                referenceStartAt.plus(interval.multipliedBy(requestPacketCounter.get()))
                            ),
                            "next packet"
                        )
                        .thenCompose(
                            value -> sendPackets(
                                packetReceiver,
                                eventLoop,
                                iterator,
                                referenceStartAt,
                                interval,
                                requestPacketCounter
                            ),
                            () -> "sending next packet"
                        ),
                    () -> "recursing, once ready"
                );
            }
            return consumeFuture.getDeferredFutureThroughHandle(
                (value, failure) -> {
                    transitionPhase(TargetExchangeState.Phase.WAITING_FOR_RESPONSE);
                    return packetReceiver.finalizeRequest();
                },
                () -> "finalizing, once ready"
            );
        }

        private void transitionPhase(TargetExchangeState.Phase nextPhase) {
            runOnOwner(() -> {
                if (cancellationCause != null && nextPhase != TargetExchangeState.Phase.ABORTING) {
                    return;
                }
                if (phase == nextPhase) {
                    return;
                }
                clearPhaseOnOwner();
                phase = nextPhase;
                targetExchangeMetrics.phaseChanged(nextPhase, 1);
            });
        }

        private void clearPhase() {
            runOnOwner(this::clearPhaseOnOwner);
        }

        private void clearPhaseOnOwner() {
            if (phase != null) {
                targetExchangeMetrics.phaseChanged(phase, -1);
                phase = null;
            }
        }

        private void runOnOwner(Runnable command) {
            if (runtime.session.eventLoop.inEventLoop()) {
                command.run();
            } else {
                runtime.session.eventLoop.execute(command);
            }
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

        private Duration getDelayFromNowMs(Instant target) {
            return Duration.ofMillis(Math.max(0, Duration.between(Instant.now(), target).toMillis()));
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
            Duration interval;
            RetryVisitor<T> typedVisitor;
            try {
                packetProducer.trackOwnership(resourceOwnershipMetrics);
                var packetCount = packetProducer.numByteBufs();
                interval = packetCount > 1
                    ? Duration.between(sendStart, sendEnd).dividedBy(packetCount - 1L)
                    : Duration.ZERO;
                typedVisitor = Objects.requireNonNull(
                    retryVisitorFactory.apply(transformed),
                    "retry visitor factory returned null"
                );
            } catch (Throwable t) {
                try {
                    packetProducer.close();
                } catch (Throwable closeFailure) {
                    if (closeFailure != t) {
                        t.addSuppressed(closeFailure);
                    }
                }
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
                var packetProducer = transformed.transformedOutput;
                try {
                    packetProducer.trackOwnership(resourceOwnershipMetrics);
                } finally {
                    packetProducer.close();
                }
            }
        }
    }

    public static class DeterminedTransformedResponse<T> implements AutoCloseable {
        private enum OwnershipState {
            OWNED,
            TRANSFERRED,
            RELEASED
        }

        private final RetryDirective directive;
        private final T value;
        private final Consumer<? super T> valueReleaser;
        private final AtomicReference<OwnershipState> ownership =
            new AtomicReference<>(OwnershipState.OWNED);

        public DeterminedTransformedResponse(RetryDirective directive, T value) {
            this(directive, value, ignored -> {});
        }

        public DeterminedTransformedResponse(
            @NonNull RetryDirective directive,
            T value,
            @NonNull Consumer<? super T> valueReleaser
        ) {
            this.directive = directive;
            this.value = value;
            this.valueReleaser = valueReleaser;
        }

        public void transferOwnership() {
            if (!ownership.compareAndSet(OwnershipState.OWNED, OwnershipState.TRANSFERRED)) {
                throw new IllegalStateException("target response ownership was already settled");
            }
        }

        private void releaseTransferredValue() {
            if (!ownership.compareAndSet(OwnershipState.TRANSFERRED, OwnershipState.RELEASED)) {
                throw new IllegalStateException("transferred target response ownership was already settled");
            }
            valueReleaser.accept(value);
        }

        @Override
        public void close() {
            if (ownership.compareAndSet(OwnershipState.OWNED, OwnershipState.RELEASED)) {
                valueReleaser.accept(value);
            }
        }
    }

    public interface RetryVisitor<T> extends AutoCloseable {
        /**
         * Return null to continue trying according to
         * @param arr
         * @return
         */
        TrackedFuture<String,DeterminedTransformedResponse<T>>
        visit(ByteBuf requestBytes, AggregatedRawResponse arr, Throwable t);

        @Override
        default void close() {}
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

            @Override
            public CompletionStage<T> onClassifiedSkip(TargetOutcome.ClassifiedSkip<Object> classifiedSkip) {
                @SuppressWarnings("unchecked")
                var value = (T) classifiedSkip.value();
                return CompletableFuture.completedFuture(value);
            }
        }));
        return new TextTrackedFuture<>(
            result.toCompletableFuture(),
            () -> "waiting for the connection actor to settle " + requestId
        );
    }

    public TransactionRuntime transactionRuntime(
        @NonNull UniqueReplayerRequestKey requestKey,
        @NonNull IReplayContexts.IChannelKeyContext channelContext
    ) {
        var requestId = toReplayRequestId(requestKey);
        var runtime = actorRuntime(requestId.session(), channelContext);
        return new TransactionRuntime(requestId, runtime.mailbox, runtime.transactions);
    }

    public static final class TransactionRuntime {
        private final ReplayRequestId requestId;
        private final ActorMailbox mailbox;
        private final ReplayTransactionRegistry registry;

        private TransactionRuntime(
            ReplayRequestId requestId,
            ActorMailbox mailbox,
            ReplayTransactionRegistry registry
        ) {
            this.requestId = requestId;
            this.mailbox = mailbox;
            this.registry = registry;
        }

        public ReplayRequestId requestId() {
            return requestId;
        }

        public ActorMailbox mailbox() {
            return mailbox;
        }

        /** Registers bare completion; the transaction-typed overload is what production uses. */
        public CompletionStage<Void> register(CompletionStage<?> transactionCompletion) {
            return registry.register(requestId, transactionCompletion);
        }

        public CompletionStage<Void> register(ReplayTransaction<?> transaction) {
            return registry.register(requestId, transaction);
        }
    }

    public CompletionStage<Void> observeRunwayLost(
        @NonNull ConnectionSessionKey sessionKey,
        @NonNull ReplayTransaction.RunwayLossReason reason
    ) {
        var runtime = actorRuntimes.get(sessionKey);
        return runtime == null
            ? CompletableFuture.completedFuture(null)
            : runtime.transactions.observeRunwayLost(reason);
    }

    public CompletionStage<Void> observeAllRunwaysLost(
        @NonNull ReplayTransaction.RunwayLossReason reason
    ) {
        globalRunwayLossReason.compareAndSet(null, reason);
        var acceptedReason = globalRunwayLossReason.get();
        var acknowledgements = actorRuntimes.values()
            .stream()
            .map(runtime -> runtime.transactions.observeRunwayLost(acceptedReason).toCompletableFuture())
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(acknowledgements);
    }

    public CompletionStage<Void> shutdownActors(@NonNull CancellationException cause) {
        final List<ActorRuntime> runtimes;
        final ActorShutdown shutdown;
        synchronized (actorLifecycleLock) {
            if (actorShutdown != null) {
                return actorShutdown.completion.minimalCompletionStage();
            }
            globalRunwayLossReason.compareAndSet(null, ReplayTransaction.RunwayLossReason.SHUTDOWN);
            shutdown = new ActorShutdown(cause, new CompletableFuture<>());
            actorShutdown = shutdown;
            runtimes = List.copyOf(actorRuntimes.values());
        }

        var runwayReason = globalRunwayLossReason.get();
        var terminations = runtimes.stream()
            .map(runtime -> {
                runtime.transactions.observeRunwayLost(runwayReason);
                // Sessions that already terminated will not run their termination path again, so their
                // registries have to be told here; intake has stopped, so anything they are still
                // waiting on will never arrive.
                runtime.transactions.cancelOutstanding(cause);
                runtime.actor.abort(AbortReason.SHUTDOWN, cause);
                return runtime.termination()
                    .thenCompose(RequestSenderOrchestrator::mapAbortOutcome)
                    .toCompletableFuture();
            })
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(terminations).whenComplete((ignored, failure) -> {
            if (failure == null) {
                shutdown.completion.complete(null);
            } else {
                shutdown.completion.completeExceptionally(unwrap(failure));
            }
        });
        return shutdown.completion.minimalCompletionStage();
    }

    /** Connection sessions whose actor has not reached termination, for shutdown diagnostics. */
    public Set<String> describeUnterminatedSessions() {
        return actorRuntimes.values().stream()
            .filter(runtime -> !runtime.terminationOwner.isDone())
            .map(runtime -> runtime.key.toString())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    public TrackedFuture<String, SessionOutcome> scheduleActorClose(
        @NonNull IReplayContexts.IChannelKeyContext context,
        int sessionNumber,
        @NonNull Instant timestamp
    ) {
        var sessionKey = toConnectionSessionKey(context, sessionNumber);
        var runtime = actorRuntime(sessionKey, context);
        runtime.actor.admitClose(timestamp);
        return new TextTrackedFuture<>(
            runtime.termination().toCompletableFuture(),
            () -> "waiting for ordered actor close for " + sessionKey
        );
    }

    public TrackedFuture<String, Void> abortActor(
        @NonNull IReplayContexts.IChannelKeyContext context,
        int sessionNumber,
        @NonNull AbortReason reason,
        @NonNull CancellationException cause
    ) {
        var sessionKey = toConnectionSessionKey(context, sessionNumber);
        var runtime = actorRuntimes.get(sessionKey);
        if (runtime == null) {
            log.atDebug()
                .setMessage("Aborting replay session {} without an existing actor")
                .addArgument(sessionKey)
                .log();
            CompletionStage<Void> acknowledgement;
            try {
                acknowledgement = Objects.requireNonNull(
                    sessionTerminationAcknowledger.apply(sessionKey),
                    "session termination acknowledger returned no completion stage"
                );
            } catch (Throwable t) {
                acknowledgement = CompletableFuture.failedFuture(t);
            }
            return new TextTrackedFuture<>(
                acknowledgement.toCompletableFuture(),
                () -> "acknowledging that no actor existed for " + sessionKey
            );
        }
        log.atDebug()
            .setMessage("Aborting replay session {} through its connection actor")
            .addArgument(sessionKey)
            .log();
        runtime.actor.abort(reason, cause);
        var result = runtime.termination().thenCompose(RequestSenderOrchestrator::mapAbortOutcome);
        return new TextTrackedFuture<>(
            result.toCompletableFuture(),
            () -> "waiting for actor abort for " + sessionKey
        );
    }

    private ActorRuntime actorRuntime(
        ConnectionSessionKey key,
        IReplayContexts.IChannelKeyContext channelContext
    ) {
        synchronized (actorLifecycleLock) {
            if (actorShutdown != null) {
                throw actorShutdown.cause;
            }
            var runtime = actorRuntimes.computeIfAbsent(key, ignored -> new ActorRuntime(key, channelContext));
            var runwayLossReason = globalRunwayLossReason.get();
            if (runwayLossReason != null) {
                runtime.transactions.observeRunwayLost(runwayLossReason);
            }
            return runtime;
        }
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

}
