package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ActorMailbox ClientConnectionPool ConnectionReplaySession NettyEventLoopActorMailbox ReplayTransaction . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.io.IOException;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer;
import org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer.PacketSendOutcome;
import org.opensearch.migrations.replay.datatypes.AttemptPayload;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.OwnedPreparedRequest;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.lifecycle.ActorMailbox;
import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;
import org.opensearch.migrations.replay.lifecycle.NettyEventLoopActorMailbox;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.ProcessingCancellationResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseDiagnostic;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseKind;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.replay.lifecycle.ReplayTransactionRegistry;
import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestTurnResult;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.RefSafeHolder;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

*/
// REBUILD-LIMBO-END(G5)
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
// REBUILD-LIMBO-START(G5)
/*
@Slf4j
public class RequestSenderOrchestrator {
    public record ScheduledClose(
        @NonNull CompletionStage<Void> admissionAccepted,
        @NonNull TrackedFuture<String, SessionOutcome> termination
    ) {}

    @FunctionalInterface
    public interface FatalReplayHandler {
        void onFatal(Error failure);
    }

    public static final class EventLoopTerminatedError extends Error {
        public EventLoopTerminatedError(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface PacketConsumerFactory {
        TargetPacketConsumer create(
            ConnectionReplaySession session,
            IReplayContexts.IReplayerHttpTransactionContext context,
            Runnable firstTargetWriteSubmitted
        );
    }

    private final ClientConnectionPool clientConnectionPool;
    private final TargetAttemptPermitProvider permitProvider;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final PacketConsumerFactory packetConsumerFactory;
    private final Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger;
    private final TargetConnectionOwner.Metrics actorMetrics;
    private final TargetExchangeState.Metrics targetExchangeMetrics;
    private final ResourceOwnership.Metrics resourceOwnershipMetrics;
    private final FatalReplayHandler fatalReplayHandler;
    private final TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink;
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

*/
// REBUILD-LIMBO-END(G5)
    /**
     * Notice that the two arguments need to be in agreement with each other.  The clientConnectionPool will need to
     * be able to create/return ConnectionReplaySession objects with Channels (or, to be more exact, ChannelFutures
     * that resolve Channels) that can be utilized by the IPacketFinalizingConsumer objects.  For example, it TLS
     * is being used, either the clientConnectionPool will be responsible for configuring the channel with handlers
     * to do that or that functionality will need to be provided by the factory/packet consumer.
     * @param clientConnectionPool
     * @param packetConsumerFactory
     */
// REBUILD-LIMBO-START(G5)
/*
    public RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        TargetAttemptPermitProvider permitProvider,
        PacketConsumerFactory packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink,
        FatalReplayHandler fatalReplayHandler
    ) {
        this(
            clientConnectionPool,
            permitProvider,
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            TargetConnectionOwner.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ResourceOwnership.Metrics.NOOP,
            requestLifecycleSink,
            fatalReplayHandler
        );
    }

    public RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        TargetAttemptPermitProvider permitProvider,
        PacketConsumerFactory packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        TargetConnectionOwner.Metrics actorMetrics,
        TargetExchangeState.Metrics targetExchangeMetrics,
        ResourceOwnership.Metrics resourceOwnershipMetrics,
        TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink,
        FatalReplayHandler fatalReplayHandler
    ) {
        this(
            clientConnectionPool,
            permitProvider,
            Duration.ofMillis(100),
            Duration.ofSeconds(300),
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            actorMetrics,
            targetExchangeMetrics,
            resourceOwnershipMetrics,
            requestLifecycleSink,
            fatalReplayHandler
        );
    }

    RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        TargetAttemptPermitProvider permitProvider,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        PacketConsumerFactory packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink,
        FatalReplayHandler fatalReplayHandler
    ) {
        this(
            clientConnectionPool,
            permitProvider,
            initialRetryDelay,
            maxRetryDelay,
            packetConsumerFactory,
            sessionTerminationAcknowledger,
            TargetConnectionOwner.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ResourceOwnership.Metrics.NOOP,
            requestLifecycleSink,
            fatalReplayHandler
        );
    }

    RequestSenderOrchestrator(
        ClientConnectionPool clientConnectionPool,
        TargetAttemptPermitProvider permitProvider,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        PacketConsumerFactory packetConsumerFactory,
        Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
        TargetConnectionOwner.Metrics actorMetrics,
        TargetExchangeState.Metrics targetExchangeMetrics,
        ResourceOwnership.Metrics resourceOwnershipMetrics,
        TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink,
        FatalReplayHandler fatalReplayHandler
    ) {
        this.clientConnectionPool = clientConnectionPool;
        this.permitProvider = Objects.requireNonNull(permitProvider);
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.packetConsumerFactory = packetConsumerFactory;
        this.sessionTerminationAcknowledger = sessionTerminationAcknowledger;
        this.actorMetrics = actorMetrics;
        this.targetExchangeMetrics = targetExchangeMetrics;
        this.resourceOwnershipMetrics = resourceOwnershipMetrics;
        this.requestLifecycleSink = Objects.requireNonNull(requestLifecycleSink);
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

    private final class PreparedActorRequest implements TargetConnectionOwner.PreparedRequest {
        private final IReplayContexts.IReplayerHttpTransactionContext context;
        private final Instant start;
        private final Duration interval;
        private final OwnedPreparedRequest packetProducer;
        private final RetryVisitor<Object> visitor;
        private final IReplayContexts.IScheduledContext scheduledContext;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean connectionTurnResourcesReleased = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();

        private PreparedActorRequest(
            IReplayContexts.IReplayerHttpTransactionContext context,
            Instant start,
            Duration interval,
            OwnedPreparedRequest packetProducer,
            RetryVisitor<Object> visitor,
            IReplayContexts.IScheduledContext scheduledContext
        ) {
            this.context = context;
            this.start = start;
            this.interval = interval;
            this.packetProducer = packetProducer;
            this.visitor = visitor;
            this.scheduledContext = scheduledContext;
        }

        private void beginExecution() {
            if (started.compareAndSet(false, true)) {
                scheduledContext.close();
            }
        }

        @Override
        public void connectionTurnFinished() {
            if (connectionTurnResourcesReleased.compareAndSet(false, true)) {
                beginExecution();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                Throwable failure = null;
                failure = runCleanup(failure, visitor::close);
                failure = runCleanup(failure, packetProducer::close);
                failure = runCleanup(failure, this::connectionTurnFinished);
                throwCleanupFailure(failure);
            }
        }

        @SuppressWarnings("java:S1181") // Every resource must be attempted and cleanup failures aggregated.
        private Throwable runCleanup(Throwable previous, Runnable action) {
            try {
                action.run();
                return previous;
            } catch (Throwable failure) {
                if (previous == null) {
                    return failure;
                }
                if (failure != previous) {
                    previous.addSuppressed(failure);
                }
                return previous;
            }
        }

        private void throwCleanupFailure(Throwable failure) {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure != null) {
                throw new IllegalStateException("Prepared request cleanup failed", failure);
            }
        }
    }

    private final class ActorRuntime {
        private final ConnectionSessionKey key;
        private final PartitionGenerationId partitionGenerationId;
        private final ConnectionReplaySession session;
        private final ActorMailbox mailbox;
        private final TargetConnectionOwner<PreparedActorRequest, Object> actor;
        private final RuntimeTargetExchange exchange;
        private final ReplayTransactionRegistry transactions;
        private final CompletableFuture<SessionOutcome> terminationOwner = new CompletableFuture<>();
        private final CompletionStage<SessionOutcome> termination = terminationOwner.minimalCompletionStage();
        private boolean actorTerminated;

        private ActorRuntime(
            ConnectionSessionKey key,
            PartitionGenerationId partitionGenerationId,
            IReplayContexts.IChannelKeyContext channelContext
        ) {
            this.key = key;
            this.partitionGenerationId = partitionGenerationId;
            this.session = clientConnectionPool.getCachedSession(
                channelContext,
                key.sessionNumber(),
                key.sourceGeneration()
            );
            this.mailbox = new NettyEventLoopActorMailbox(session.eventLoop);
            this.transactions = new ReplayTransactionRegistry(key, mailbox);
            this.exchange = new RuntimeTargetExchange(this);
            this.actor = new TargetConnectionOwner<>(
                key,
                partitionGenerationId,
                mailbox,
                exchange,
                permitProvider,
                actorMetrics,
                RequestSenderOrchestrator.this::signalFatal,
                requestLifecycleSink
            );
            actor.termination().whenComplete((outcome, failure) ->
                executeRequired(
                    session.eventLoop,
                    "connection-owner termination for " + key,
                    () -> onActorTerminated(outcome, failure)
                )
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
            var fatalError = new EventLoopTerminatedError(
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
            if (actorFailure != null) {
                var cause = unwrap(actorFailure);
                signalFatal(cause instanceof Error error
                    ? error
                    : new Error("connection owner failed for " + key, cause));
                failTermination(cause);
                return;
            }
            if (outcome instanceof SessionOutcome.Failed failed) {
                signalFatal(new Error(
                    "connection owner reported a failed terminal outcome for " + key,
                    failed.cause()
                ));
                settleTermination(failed);
                return;
            }
            acknowledgeSourceTermination(outcome);
        }

*/
// REBUILD-LIMBO-END(G5)
        /**
         * Every terminal path has to drop the runtime, not just the successful one.  A retained entry
         * would keep the session in shutdown's set of live actors forever and would shadow any later
         * session that reuses the key.
         */
// REBUILD-LIMBO-START(G5)
/*
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
                signalFatal(new Error(
                    "source termination acknowledgement could not start for " + key,
                    t
                ));
                failTermination(t);
                return;
            }
            acknowledgement.whenComplete((ignored, failure) ->
                executeRequired(session.eventLoop, "source termination acknowledgement for " + key, () -> {
                    log.atDebug()
                        .setMessage("Source termination acknowledgement settled for {}; failure={}")
                        .addArgument(key)
                        .addArgument(failure)
                        .log();
                    if (failure != null) {
                        var cause = unwrap(failure);
                        signalFatal(new Error(
                            "source termination acknowledgement failed for " + key,
                            cause
                        ));
                        failTermination(cause);
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

    private void executeRequired(EventLoop eventLoop, String operation, Runnable command) {
        executeRequired(eventLoop, operation, command, ignored -> {});
    }

    private void executeRequired(
        EventLoop eventLoop,
        String operation,
        Runnable command,
        Consumer<RejectedExecutionException> rejectedSubmissionHandler
    ) {
        Runnable guarded = () -> {
            try {
                command.run();
            } catch (Error failure) {
                signalFatal(failure);
            } catch (Throwable failure) {
                signalFatal(new Error(
                    "Required event-loop operation failed during " + operation,
                    failure
                ));
            }
        };
        if (eventLoop.inEventLoop()) {
            guarded.run();
            return;
        }
        try {
            eventLoop.execute(guarded);
        } catch (RejectedExecutionException rejection) {
            try {
                rejectedSubmissionHandler.accept(rejection);
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != rejection) {
                    rejection.addSuppressed(cleanupFailure);
                }
            }
            signalFatal(new Error(
                "Required event-loop submission was rejected during " + operation,
                rejection
            ));
        }
    }

    private final class RuntimeTargetExchange implements TargetConnectionOwner.TargetExchange<PreparedActorRequest, Object> {
        private final ActorRuntime runtime;
        private final Map<ScheduledFuture<?>, CompletableFuture<Void>> cancellableSchedules = new LinkedHashMap<>();
        private final AtomicReference<AttemptPayload> activeAttempt = new AtomicReference<>();
        private final AtomicReference<TargetAttemptPermitProvider.Permit> activePermit =
            new AtomicReference<>();
        private CompletableFuture<RequestTurnResult<Object>> activeExchange;
        private TargetPacketConsumer activePacketReceiver;
        private CancellationException cancellationCause;
        private TargetExchangeState.Phase phase;

        private RuntimeTargetExchange(ActorRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        @SuppressWarnings("java:S1181") // Startup failure must reach the connection owner's fatal boundary.
        public CompletionStage<RequestTurnResult<Object>> execute(
            ReplayRequestId requestId,
            PreparedActorRequest preparedRequest,
            TargetAttemptPermitProvider.Permit firstAttemptPermit,
            TargetConnectionOwner.AttemptPermitRequester retryPermitRequester
        ) {
            preparedRequest.beginExecution();
            if (cancellationCause != null) {
                firstAttemptPermit.close();
                return CompletableFuture.completedFuture(new RequestTurnResult.Cancelled<>(cancellationCause));
            }
            try {
                return normalizeExchange(
                    startExchange(
                        requestId,
                        preparedRequest,
                        firstAttemptPermit,
                        retryPermitRequester
                    )
                );
            } catch (Throwable t) {
                addSuppressed(t, closeResource(firstAttemptPermit));
                clearPhase();
                return CompletableFuture.failedFuture(unwrap(t));
            }
        }

        @SuppressWarnings("unchecked")
        private TrackedFuture<String, DeterminedTransformedResponse<Object>> startExchange(
            ReplayRequestId requestId,
            PreparedActorRequest preparedRequest,
            TargetAttemptPermitProvider.Permit firstAttemptPermit,
            TargetConnectionOwner.AttemptPermitRequester retryPermitRequester
        ) {
            var firstWriteReported = new AtomicBoolean();
            Runnable firstTargetWriteSubmitted = () -> {
                if (firstWriteReported.compareAndSet(false, true)) {
                    runtime.actor.firstTargetWriteSubmitted(requestId);
                }
            };
            return (TrackedFuture<String, DeterminedTransformedResponse<Object>>)
                (TrackedFuture<?, ?>) runRetrySequence(
                () -> packetConsumerFactory.create(
                    runtime.session,
                    preparedRequest.context,
                    firstTargetWriteSubmitted
                ),
                runtime.session.eventLoop,
                preparedRequest.packetProducer,
                preparedRequest.start,
                preparedRequest.interval,
                preparedRequest.visitor,
                firstAttemptPermit,
                retryPermitRequester
            );
        }

        private CompletionStage<RequestTurnResult<Object>> normalizeExchange(
            TrackedFuture<String, DeterminedTransformedResponse<Object>> exchange
        ) {
            var normalized = new CompletableFuture<RequestTurnResult<Object>>();
            activeExchange = normalized;
            exchange.future.whenComplete((result, failure) ->
                submitRequiredContinuation(
                    runtime.session.eventLoop,
                    "settling the target exchange for " + runtime.key,
                    normalized,
                    () -> settleNormalizedExchange(normalized, result, failure)
                )
            );
            normalized.whenComplete((value, failure) ->
                executeRequired(
                    runtime.session.eventLoop,
                    "target exchange retirement for " + runtime.key,
                    () -> retireNormalizedExchange(normalized)
                )
            );
            return normalized;
        }

        private void settleNormalizedExchange(
            CompletableFuture<RequestTurnResult<Object>> normalized,
            DeterminedTransformedResponse<Object> result,
            Throwable failure
        ) {
            if (failure != null) {
                var cause = unwrap(failure);
                if (cancellationCause != null) {
                    normalized.complete(new RequestTurnResult.Cancelled<>(cancellationCause));
                } else {
                    completeUnexpectedExchangeFailure(normalized, cause);
                }
                return;
            }
            if (result == null) {
                completeUnexpectedExchangeFailure(
                    normalized,
                    new IllegalStateException("target exchange completed without a result")
                );
                return;
            }
            try {
                result.transferOwnership();
            } catch (RuntimeException transferFailure) {
                completeUnexpectedExchangeFailure(normalized, transferFailure);
                return;
            }
            if (!normalized.complete(new RequestTurnResult.Completed<>(result.value))) {
                result.releaseTransferredValue();
            }
        }

        private void completeUnexpectedExchangeFailure(
            CompletableFuture<RequestTurnResult<Object>> normalized,
            Throwable failure
        ) {
            if (!normalized.completeExceptionally(failure)) {
                signalFatal(new Error(
                    "A late target exchange failure arrived after settlement for " + runtime.key,
                    failure
                ));
            }
        }

        private void retireNormalizedExchange(CompletableFuture<RequestTurnResult<Object>> normalized) {
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
            Throwable cleanupFailure = cancelScheduledWork(cancellationCause);
            cleanupFailure = combineCleanupFailures(
                cleanupFailure,
                cancelActivePacketReceiver(cancellationCause)
            );
            cleanupFailure = combineCleanupFailures(cleanupFailure, releaseActivePermit());
            cleanupFailure = combineCleanupFailures(cleanupFailure, releaseActiveAttempt());
            var exchangeToJoin = activeExchange;
            if (exchangeToJoin != null) {
                exchangeToJoin.complete(new RequestTurnResult.Cancelled<>(cancellationCause));
            }
            var localCleanupFailure = cleanupFailure;
            return cancelRuntimeChannel()
                .handle((ignored, channelFailure) -> {
                    var failure = channelFailure == null ? null : unwrap(channelFailure);
                    failure = combineCleanupFailures(failure, localCleanupFailure);
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

        @SuppressWarnings("java:S1181") // Abort must continue through every remaining cleanup action.
        private Throwable cancelActivePacketReceiver(CancellationException cause) {
            var packetReceiver = activePacketReceiver;
            activePacketReceiver = null;
            if (packetReceiver != null) {
                try {
                    packetReceiver.abort(cause);
                } catch (Throwable failure) {
                    return failure;
                }
            }
            return null;
        }

        @SuppressWarnings("java:S1181") // Abort must continue through every remaining scheduled item.
        private Throwable cancelScheduledWork(CancellationException cause) {
            Throwable failure = null;
            var schedules = List.copyOf(cancellableSchedules.entrySet());
            cancellableSchedules.clear();
            for (var entry : schedules) {
                try {
                    entry.getKey().cancel(false);
                } catch (Throwable cancellationFailure) {
                    failure = combineCleanupFailures(failure, cancellationFailure);
                }
                try {
                    entry.getValue().completeExceptionally(cause);
                } catch (Throwable completionFailure) {
                    failure = combineCleanupFailures(failure, completionFailure);
                }
            }
            return failure;
        }

        private CompletionStage<Void> closeRuntimeChannel() {
            return clientConnectionPool.closeChannelForSession(runtime.session).future.handle((channel, failure) -> {
                if (failure != null) {
                    throw new CompletionException(unwrap(failure));
                }
                return null;
            });
        }

        private <T> TrackedFuture<String, DeterminedTransformedResponse<T>> runRetrySequence(
            Supplier<TargetPacketConsumer> senderSupplier,
            EventLoop eventLoop,
            OwnedPreparedRequest packetProducer,
            Instant referenceStartTime,
            Duration interval,
            RetryVisitor<T> visitor,
            TargetAttemptPermitProvider.Permit firstAttemptPermit,
            TargetConnectionOwner.AttemptPermitRequester retryPermitRequester
        ) {
            return new RetrySequence<>(
                senderSupplier,
                eventLoop,
                packetProducer,
                referenceStartTime,
                interval,
                visitor,
                firstAttemptPermit,
                retryPermitRequester
            ).start();
        }

        private <T> TrackedFuture<String, DeterminedTransformedResponse<T>> sendSingleRequestAttempt(
            Supplier<TargetPacketConsumer> senderSupplier,
            EventLoop eventLoop,
            OwnedPreparedRequest packetProducer,
            Instant referenceStartTime,
            Duration interval,
            RetryVisitor<T> visitor,
            TargetAttemptPermitProvider.Permit permit
        ) {
            transitionPhase(TargetExchangeState.Phase.STARTING_ATTEMPT);
            TrackedFuture<String, DeterminedTransformedResponse<T>> startRejection =
                rejectUnavailableAttemptStart(eventLoop);
            if (startRejection != null) {
                var permitReleaseFailure = closeResource(permit);
                if (permitReleaseFailure != null) {
                    return TextTrackedFuture.failedFuture(
                        permitReleaseFailure,
                        () -> "target attempt could not release an unused permit"
                    );
                }
                return startRejection;
            }
            if (!activePermit.compareAndSet(null, permit)) {
                var overlappingPermitFailure = new IllegalStateException(
                    "another target-attempt permit is still active"
                );
                addSuppressed(overlappingPermitFailure, closeResource(permit));
                return TextTrackedFuture.failedFuture(
                    overlappingPermitFailure,
                    () -> "target attempt rejected an overlapping permit"
                );
            }
            final AttemptPayload attempt;
            try {
                attempt = packetProducer.newAttempt();
            } catch (Throwable failure) {
                addSuppressed(failure, releasePermit(permit));
                return TextTrackedFuture.failedFuture(
                    failure,
                    () -> "target attempt payload creation failed"
                );
            }
            if (!activeAttempt.compareAndSet(null, attempt)) {
                var overlapFailure =
                    new IllegalStateException("another target request attempt is still active");
                addSuppressed(overlapFailure, closeResource(attempt));
                addSuppressed(overlapFailure, releasePermit(permit));
                return TextTrackedFuture.failedFuture(
                    overlapFailure,
                    () -> "sendRequestWithRetries rejected overlapping request attempts"
                );
            }
            final TargetPacketConsumer packetReceiver;
            try {
                packetReceiver = Objects.requireNonNull(senderSupplier.get(), "sender supplier returned null");
            } catch (Throwable t) {
                addSuppressed(t, releasePermit(permit));
                addSuppressed(t, releaseAttempt(attempt));
                return TextTrackedFuture.failedFuture(
                    t,
                    () -> "sendRequestWithRetries failed while creating the target request sender"
                );
            }
            var byteBufList = attempt.packets();
            final TrackedFuture<String, TargetAttemptOutcome<AggregatedRawResponse>> sendFuture;
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
                addSuppressed(t, releasePermit(permit));
                addSuppressed(t, releaseAttempt(attempt));
                return TextTrackedFuture.failedFuture(
                    t,
                    () -> "sendRequestWithRetries failed while starting the packet send"
                );
            }
            var permitReleased = continueOnEventLoop(
                eventLoop,
                sendFuture,
                "releasing the target-attempt permit",
                (outcome, failure) -> {
                    var releaseFailure = releasePermit(permit);
                    if (failure != null) {
                        var cause = unwrap(failure);
                        addSuppressed(cause, releaseFailure);
                        return TextTrackedFuture.failedFuture(
                            cause,
                            () -> "target attempt failed before producing a typed outcome"
                        );
                    }
                    if (releaseFailure != null) {
                        return TextTrackedFuture.failedFuture(
                            releaseFailure,
                            () -> "target-attempt permit release failed"
                        );
                    }
                    return TextTrackedFuture.completedFuture(
                        outcome,
                        () -> "target-attempt permit released after raw outcome"
                    );
                },
                () -> releasePermitAfterRejectedContinuation(permit)
            );
            var evaluated = continueOnEventLoop(
                eventLoop,
                permitReleased,
                "evaluating the target attempt response",
                (outcome, failure) -> {
                    if (cancellationCause != null) {
                        return TextTrackedFuture.failedFuture(
                            cancellationCause,
                            () -> "request exchange was cancelled before evaluating its response"
                        );
                    }
                    if (failure != null) {
                        return TextTrackedFuture.failedFuture(
                            unwrap(failure),
                            () -> "target attempt failed outside the typed no-response boundary"
                        );
                    }
                    transitionPhase(TargetExchangeState.Phase.EVALUATING_RETRY);
                    try (var requestBytesHolder = RefSafeHolder.create(byteBufList.asCompositeByteBufRetained())) {
                        return visitor.visit(
                            requestBytesHolder.get(),
                            outcome
                        );
                    }
                }
            );
            var released = continueOnEventLoop(
                eventLoop,
                evaluated,
                "releasing the request attempt payload",
                (response, failure) -> releaseAttemptAfterEvaluation(
                    attempt,
                    packetReceiver,
                    response,
                    failure
                )
            );
            return released;
        }

        private <T> TrackedFuture<String, DeterminedTransformedResponse<T>> releaseAttemptAfterEvaluation(
            AttemptPayload attempt,
            TargetPacketConsumer packetReceiver,
            DeterminedTransformedResponse<T> response,
            Throwable failure
        ) {
            var releaseFailure = releaseAttempt(attempt);
            if (activePacketReceiver == packetReceiver) {
                activePacketReceiver = null;
            }
            if (failure != null) {
                var cause = unwrap(failure);
                addSuppressed(cause, releaseFailure);
                return TextTrackedFuture.failedFuture(
                    cause,
                    () -> "target attempt evaluation failed"
                );
            }
            if (releaseFailure != null) {
                return TextTrackedFuture.failedFuture(
                    releaseFailure,
                    () -> "target attempt payload release failed"
                );
            }
            return TextTrackedFuture.completedFuture(
                response,
                () -> "target attempt evaluation and payload release completed"
            );
        }

        private <I, O> TrackedFuture<String, O> continueOnEventLoop(
            EventLoop eventLoop,
            TrackedFuture<String, I> source,
            String operation,
            BiFunction<? super I, Throwable, ? extends TrackedFuture<String, O>> continuation
        ) {
            return continueOnEventLoop(
                eventLoop,
                source,
                operation,
                continuation,
                () -> {}
            );
        }

        private <I, O> TrackedFuture<String, O> continueOnEventLoop(
            EventLoop eventLoop,
            TrackedFuture<String, I> source,
            String operation,
            BiFunction<? super I, Throwable, ? extends TrackedFuture<String, O>> continuation,
            Runnable rejectedSubmissionCleanup
        ) {
            var completion = new CompletableFuture<O>();
            source.future.whenComplete((value, failure) ->
                submitRequiredContinuation(
                    eventLoop,
                    operation,
                    completion,
                    () -> {
                        var next = Objects.requireNonNull(
                            continuation.apply(value, failure),
                            operation + " returned no tracked future"
                        );
                        next.future.whenComplete((nextValue, nextFailure) -> {
                            if (nextFailure == null) {
                                completion.complete(nextValue);
                            } else {
                                completion.completeExceptionally(nextFailure);
                            }
                        });
                    },
                    rejectedSubmissionCleanup
                )
            );
            return new TextTrackedFuture<>(completion, () -> operation);
        }

        private <T> void submitRequiredContinuation(
            EventLoop eventLoop,
            String operation,
            CompletableFuture<T> completion,
            Runnable command
        ) {
            submitRequiredContinuation(
                eventLoop,
                operation,
                completion,
                command,
                () -> {}
            );
        }

        private <T> void submitRequiredContinuation(
            EventLoop eventLoop,
            String operation,
            CompletableFuture<T> completion,
            Runnable command,
            Runnable rejectedSubmissionCleanup
        ) {
            Runnable guarded = () -> {
                try {
                    command.run();
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            };
            if (eventLoop.inEventLoop()) {
                guarded.run();
                return;
            }
            try {
                eventLoop.execute(guarded);
            } catch (RejectedExecutionException rejection) {
                try {
                    rejectedSubmissionCleanup.run();
                } catch (Throwable cleanupFailure) {
                    addSuppressed(rejection, cleanupFailure);
                }
                completion.completeExceptionally(rejection);
                signalFatal(new Error(
                    "Required target-exchange continuation was rejected during " + operation,
                    rejection
                ));
            }
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

        private final class RetrySequence<T> {
            private final Supplier<TargetPacketConsumer> senderSupplier;
            private final EventLoop eventLoop;
            private final OwnedPreparedRequest packetProducer;
            private final Duration interval;
            private final RetryVisitor<T> visitor;
            private final TargetAttemptPermitProvider.Permit firstAttemptPermit;
            private final TargetConnectionOwner.AttemptPermitRequester retryPermitRequester;
            private final CompletableFuture<DeterminedTransformedResponse<T>> completion =
                new CompletableFuture<>();
            private Instant referenceStartTime;
            private Duration nextRetryDelay = initialRetryDelay;

            private RetrySequence(
                Supplier<TargetPacketConsumer> senderSupplier,
                EventLoop eventLoop,
                OwnedPreparedRequest packetProducer,
                Instant referenceStartTime,
                Duration interval,
                RetryVisitor<T> visitor,
                TargetAttemptPermitProvider.Permit firstAttemptPermit,
                TargetConnectionOwner.AttemptPermitRequester retryPermitRequester
            ) {
                this.senderSupplier = senderSupplier;
                this.eventLoop = eventLoop;
                this.packetProducer = packetProducer;
                this.referenceStartTime = referenceStartTime;
                this.interval = interval;
                this.visitor = visitor;
                this.firstAttemptPermit = firstAttemptPermit;
                this.retryPermitRequester = retryPermitRequester;
            }

            private TrackedFuture<String, DeterminedTransformedResponse<T>> start() {
                startAttempt(firstAttemptPermit);
                return new TextTrackedFuture<>(
                    completion,
                    () -> "running the explicit target retry sequence"
                );
            }

            private void startAttempt(TargetAttemptPermitProvider.Permit permit) {
                if (completion.isDone()) {
                    closeResource(permit);
                    return;
                }
                var attempt = sendSingleRequestAttempt(
                    senderSupplier,
                    eventLoop,
                    packetProducer,
                    referenceStartTime,
                    interval,
                    visitor,
                    permit
                );
                attempt.future.whenComplete((result, failure) ->
                    submitRequiredContinuation(
                        eventLoop,
                        "determining whether the target request must be retried",
                        completion,
                        () -> onAttemptDecision(result, failure)
                    )
                );
            }

            private void onAttemptDecision(
                DeterminedTransformedResponse<T> result,
                Throwable failure
            ) {
                if (cancellationCause != null) {
                    addSuppressed(cancellationCause, closeResult(result));
                    completion.completeExceptionally(cancellationCause);
                    return;
                }
                if (failure != null) {
                    var cause = unwrap(failure);
                    addSuppressed(cause, closeResult(result));
                    completion.completeExceptionally(cause);
                    return;
                }
                if (result == null) {
                    completion.completeExceptionally(
                        new IllegalStateException("target attempt completed without a retry decision")
                    );
                    return;
                }
                if (result.directive != RetryDirective.RETRY) {
                    completion.complete(result);
                    return;
                }
                var releaseFailure = closeResult(result);
                if (releaseFailure != null) {
                    completion.completeExceptionally(releaseFailure);
                    return;
                }

                var computedStartTime = referenceStartTime.plus(nextRetryDelay);
                var currentTime = Instant.now();
                var newStartTime = computedStartTime.isBefore(currentTime)
                    ? currentTime.plus(nextRetryDelay)
                    : computedStartTime;
                log.atDebug().setMessage("Making request scheduled at {}").addArgument(newStartTime).log();
                var schedulingDelay = Duration.between(Instant.now(), newStartTime);
                referenceStartTime = newStartTime;
                nextRetryDelay = doubleRetryDelayCapped(nextRetryDelay);
                transitionPhase(TargetExchangeState.Phase.RETRY_DELAY);
                var schedule = scheduleCancellable(eventLoop, schedulingDelay, "retry");
                schedule.future.whenComplete((ignored, scheduleFailure) ->
                    submitRequiredContinuation(
                        eventLoop,
                        "starting a scheduled target retry",
                        completion,
                        () -> {
                            if (scheduleFailure != null) {
                                completion.completeExceptionally(unwrap(scheduleFailure));
                            } else {
                                requestRetryPermit();
                            }
                        }
                    )
                );
            }

            private void requestRetryPermit() {
                final CompletionStage<TargetAttemptPermitProvider.Permit> permit;
                try {
                    permit = Objects.requireNonNull(
                        retryPermitRequester.request(),
                        "retry permit requester returned no completion stage"
                    );
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                    return;
                }
                permit.whenComplete((acquiredPermit, permitFailure) ->
                    submitRequiredContinuation(
                        eventLoop,
                        "starting a permitted target retry",
                        completion,
                        () -> {
                            if (permitFailure != null) {
                                completion.completeExceptionally(unwrap(permitFailure));
                            } else if (acquiredPermit == null) {
                                completion.completeExceptionally(
                                    new NullPointerException(
                                        "retry permit acquisition completed without a permit"
                                    )
                                );
                            } else {
                                startAttempt(acquiredPermit);
                            }
                        },
                        () -> releasePermitAfterRejectedContinuation(acquiredPermit)
                    )
                );
            }
        }

        private void releasePermitAfterRejectedContinuation(
            TargetAttemptPermitProvider.Permit permit
        ) {
            var failure = permit == null ? null : closeResource(permit);
            if (failure != null) {
                signalFatal(new Error(
                    "Target-attempt permit cleanup failed after event-loop rejection",
                    failure
                ));
            }
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
                var failure = new IllegalStateException("event loop is already shutting down");
                signalFatal(new Error(
                    "Required " + operation + " schedule could not be admitted",
                    failure
                ));
                return TextTrackedFuture.failedFuture(
                    failure,
                    () -> operation + " schedule was rejected because the event loop is shutting down"
                );
            }

            var completion = new CompletableFuture<Void>();
            var delayMillis = Math.max(0, delay.toMillis());
            final ScheduledFuture<?> scheduled;
            try {
                scheduled = eventLoop.schedule(() -> completion.complete(null), delayMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                signalFatal(new Error(
                    "Required " + operation + " schedule was rejected",
                    e
                ));
                return TextTrackedFuture.failedFuture(
                    e,
                    () -> operation + " schedule was rejected because the event loop already terminated"
                );
            }
            // The task body is the only thing that completes this gate on the happy path, and an event
            // loop that shuts down cancels its pending scheduled tasks without ever running them.  The
            // isShuttingDown check above races with shutdown, so the cancellation itself has to complete
            // the gate or the whole exchange would wait on it forever.
            scheduled.addListener(f -> {
                if (f.isCancelled()) {
                    var explicitCancellation = cancellationCause;
                    if (explicitCancellation == null) {
                        var failure = new IllegalStateException(
                            operation + " schedule was cancelled without a request cancellation"
                        );
                        signalFatal(new Error(
                            "Required " + operation + " schedule was cancelled",
                            failure
                        ));
                        completion.completeExceptionally(failure);
                    } else {
                        completion.completeExceptionally(explicitCancellation);
                    }
                } else if (!f.isSuccess()) {
                    completion.completeExceptionally(f.cause());
                }
            });
            cancellableSchedules.put(scheduled, completion);
            completion.whenComplete((ignored, failure) -> {
                if (eventLoop.inEventLoop()) {
                    cancellableSchedules.remove(scheduled);
                } else {
                    executeRequired(
                        eventLoop,
                        operation + " schedule retirement",
                        () -> cancellableSchedules.remove(scheduled)
                    );
                }
            });
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

        private Throwable releaseActivePermit() {
            var permit = activePermit.getAndSet(null);
            return permit == null ? null : closeResource(permit);
        }

        private Throwable releasePermit(TargetAttemptPermitProvider.Permit permit) {
            return activePermit.compareAndSet(permit, null) ? closeResource(permit) : null;
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

        private Throwable combineCleanupFailures(Throwable first, Throwable additional) {
            if (first == null) {
                return additional;
            }
            addSuppressed(first, additional);
            return first;
        }

        private TrackedFuture<String, TargetAttemptOutcome<AggregatedRawResponse>> sendPackets(
            TargetPacketConsumer packetReceiver,
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
            final TrackedFuture<String, PacketSendOutcome> consumeFuture;
            try {
                consumeFuture = Objects.requireNonNull(
                    packetReceiver.sendPacket(packet),
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
                return continueOnEventLoop(
                    eventLoop,
                    consumeFuture,
                    "settling a non-final target packet send",
                    (packetOutcome, failure) -> {
                        if (failure != null) {
                            return TextTrackedFuture.failedFuture(
                                unwrap(failure),
                                () -> "target packet send failed unexpectedly"
                            );
                        }
                        return packetOutcome.visit(new PacketSendOutcome.Visitor<>() {
                            @Override
                            public TrackedFuture<String, TargetAttemptOutcome<AggregatedRawResponse>>
                            onPacketSubmitted(PacketSendOutcome.PacketSubmitted submitted) {
                                return scheduleCancellable(
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
                                );
                            }

                            @Override
                            public TrackedFuture<String, TargetAttemptOutcome<AggregatedRawResponse>>
                            onNoTargetResponseObtained(PacketSendOutcome.NoTargetResponseObtained noResponse) {
                                return finalizeNoResponseAttempt(packetReceiver, noResponse);
                            }
                        });
                    }
                );
            }
            return continueOnEventLoop(
                eventLoop,
                consumeFuture,
                "settling the final target packet send",
                (packetOutcome, failure) -> {
                    if (failure != null) {
                        return TextTrackedFuture.failedFuture(
                            unwrap(failure),
                            () -> "final target packet send failed unexpectedly"
                        );
                    }
                    return packetOutcome.visit(new PacketSendOutcome.Visitor<>() {
                        @Override
                        public TrackedFuture<String, TargetAttemptOutcome<AggregatedRawResponse>>
                        onPacketSubmitted(PacketSendOutcome.PacketSubmitted submitted) {
                            transitionPhase(TargetExchangeState.Phase.WAITING_FOR_RESPONSE);
                            try {
                                return Objects.requireNonNull(
                                    packetReceiver.finalizeRequest(),
                                    "packet finalizer returned null"
                                ).thenApply(
                                    RequestSenderOrchestrator::classifyTargetAttemptOutcome,
                                    () -> "classifying the finalized target response"
                                );
                            } catch (Throwable finalizationFailure) {
                                return TextTrackedFuture.failedFuture(
                                    finalizationFailure,
                                    () -> "target response finalization failed synchronously"
                                );
                            }
                        }

                        @Override
                        public TrackedFuture<String, TargetAttemptOutcome<AggregatedRawResponse>>
                        onNoTargetResponseObtained(PacketSendOutcome.NoTargetResponseObtained noResponse) {
                            return finalizeNoResponseAttempt(packetReceiver, noResponse);
                        }
                    });
                }
            );
        }

        private TrackedFuture<String, TargetAttemptOutcome<AggregatedRawResponse>> finalizeNoResponseAttempt(
            TargetPacketConsumer packetReceiver,
            PacketSendOutcome.NoTargetResponseObtained noResponse
        ) {
            try {
                return Objects.requireNonNull(
                    packetReceiver.finalizeRequest(),
                    "packet finalizer returned null after no-response outcome"
                ).thenApply(
                    finalizedResponse -> {
                        Objects.requireNonNull(
                            finalizedResponse,
                            "packet finalizer completed with null after no-response outcome"
                        );
                        return new TargetAttemptOutcome.NoTargetResponseObtained<>(
                            noResponse.diagnostic()
                        );
                    },
                    () -> "finalizing target transport state after no-response outcome"
                );
            } catch (Throwable finalizationFailure) {
                return TextTrackedFuture.failedFuture(
                    finalizationFailure,
                    () -> "target no-response cleanup failed synchronously"
                );
            }
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
                executeRequired(
                    runtime.session.eventLoop,
                    "target exchange state transition for " + runtime.key,
                    command
                );
            }
        }
    }

    private final class PreparationCoordinator<T>
        implements TargetConnectionOwner.RequestPreparation<PreparedActorRequest> {
        private final ActorRuntime runtime;
        private final ReplayRequestId requestId;
        private final IReplayContexts.IReplayerHttpTransactionContext context;
        private final Instant preparationStart;
        private final Instant sendStart;
        private final Instant sendEnd;
        private final Supplier<TrackedFuture<String, TransformedOutputAndResult<ByteBufListProducer>>> preparation;
        private final Function<
            TransformedOutputAndResult<ByteBufListProducer>,
            RetryVisitor<T>
        > retryVisitorFactory;
        private final Function<HttpRequestTransformationStatus, T> filteredResultFactory;
        private final AtomicReference<T> filteredResult;
        private final CompletableFuture<PreparationOutcome<PreparedActorRequest>> completion =
            new CompletableFuture<>();
        private final CompletableFuture<Void> cancellationCompletion = new CompletableFuture<>();
        private final AtomicBoolean cancellationStarted = new AtomicBoolean();
        private final AtomicReference<TransformedOutputAndResult<ByteBufListProducer>>
            pendingTransformationDelivery = new AtomicReference<>();
        private final IReplayContexts.IScheduledContext preparationScheduledContext;
        private final IReplayContexts.IScheduledContext sendScheduledContext;
        private CompletableFuture<?> transformationCompletion;
        private boolean timerReady;
        private boolean preparationStarted;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean preparationContextClosed = new AtomicBoolean();
        private final AtomicBoolean sendContextTransferred = new AtomicBoolean();

        private PreparationCoordinator(
            ActorRuntime runtime,
            ReplayRequestId requestId,
            IReplayContexts.IReplayerHttpTransactionContext context,
            Instant preparationStart,
            Instant sendStart,
            Instant sendEnd,
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
            this.preparation = preparation;
            this.retryVisitorFactory = retryVisitorFactory;
            this.filteredResultFactory = filteredResultFactory;
            this.filteredResult = filteredResult;
            this.preparationScheduledContext = context.createScheduledContext(preparationStart);
            this.sendScheduledContext = context.createScheduledContext(sendStart);
        }

        @Override
        public CompletionStage<PreparationOutcome<PreparedActorRequest>> completion() {
            return completion.minimalCompletionStage();
        }

        @Override
        public void begin() {
            if (terminal.get()) {
                return;
            }
            closePreparationContext();
            timerReady = true;
            tryStartPreparation();
        }

        private void tryStartPreparation() {
            if (terminal.get() || preparationStarted || !timerReady) {
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
            transformed.future.whenComplete(this::deliverTransformationCompletion);
        }

        private void deliverTransformationCompletion(
            TransformedOutputAndResult<ByteBufListProducer> transformed,
            Throwable failure
        ) {
            if (transformed != null) {
                pendingTransformationDelivery.set(transformed);
            }
            if (terminal.get()) {
                releasePendingTransformationDelivery(transformed);
                return;
            }
            executeRequired(
                runtime.session.eventLoop,
                "request transformation completion for " + requestId,
                () -> {
                    if (transformed != null
                        && !pendingTransformationDelivery.compareAndSet(transformed, null)) {
                        return;
                    }
                    onTransformed(transformed, failure);
                },
                rejection -> {
                    emergencyFailDelivery(
                        rejection,
                        releasePendingTransformationDelivery(transformed)
                    );
                }
            );
        }

        private void onTransformed(
            TransformedOutputAndResult<ByteBufListProducer> transformed,
            Throwable failure
        ) {
            transformationCompletion = null;
            if (terminal.get()) {
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
            if (!sendContextTransferred.compareAndSet(false, true)) {
                Throwable transferFailure = new IllegalStateException(
                    "preparation resources were concurrently released for " + requestId
                );
                try {
                    packetProducer.close();
                } catch (Throwable closeFailure) {
                    transferFailure.addSuppressed(closeFailure);
                }
                if (!terminal.get()) {
                    failPreparation(transferFailure);
                }
                return;
            }
            var prepared = new PreparedActorRequest(
                context,
                sendStart,
                interval,
                packetProducer,
                actorVisitor,
                sendScheduledContext
            );
            if (!terminal.compareAndSet(false, true)) {
                prepared.close();
                return;
            }
            if (!completion.complete(new PreparationOutcome.Prepared<>(prepared))) {
                prepared.close();
            }
        }

        private void failPreparation(Throwable failure) {
            completeWithoutPreparedRequest(
                new PreparationOutcome.Failed<>(unwrap(failure))
            );
        }

        private void completeWithoutPreparedRequest(PreparationOutcome<PreparedActorRequest> outcome) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            closePreparationContext();
            closeSendContext();
            completion.complete(outcome);
        }

        @Override
        public CompletionStage<Void> cancel(CancellationException cause) {
            if (!cancellationStarted.compareAndSet(false, true)) {
                return cancellationCompletion.minimalCompletionStage();
            }
            if (!terminal.compareAndSet(false, true)) {
                cancellationCompletion.complete(null);
                return cancellationCompletion.minimalCompletionStage();
            }
            Throwable cleanupFailure = null;
            cleanupFailure = runCancellationCleanup(cleanupFailure, this::closePreparationContext);
            cleanupFailure = runCancellationCleanup(cleanupFailure, this::closeSendContext);
            var pendingTransformation = transformationCompletion;
            transformationCompletion = null;
            if (pendingTransformation != null) {
                cleanupFailure = runCancellationCleanup(
                    cleanupFailure,
                    () -> pendingTransformation.cancel(false)
                );
            }
            cleanupFailure = combineCleanupFailures(
                cleanupFailure,
                releasePendingTransformationDelivery(
                    pendingTransformationDelivery.get()
                )
            );
            completion.complete(new PreparationOutcome.Cancelled<>(cause));
            if (cleanupFailure == null) {
                cancellationCompletion.complete(null);
            } else {
                cancellationCompletion.completeExceptionally(cleanupFailure);
            }
            return cancellationCompletion.minimalCompletionStage();
        }

        private Throwable runCancellationCleanup(Throwable previous, Runnable action) {
            try {
                action.run();
                return previous;
            } catch (Throwable failure) {
                return combineCleanupFailures(previous, failure);
            }
        }

        private Throwable combineCleanupFailures(Throwable first, Throwable additional) {
            if (first == null) {
                return additional;
            }
            if (additional != null && additional != first) {
                first.addSuppressed(additional);
            }
            return first;
        }

        private void rethrowCancellationCleanupFailure(Throwable failure) {
            if (failure == null) {
                return;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException(
                "Preparation cancellation cleanup failed for " + requestId,
                failure
            );
        }

        private void closePreparationContext() {
            if (preparationContextClosed.compareAndSet(false, true)) {
                preparationScheduledContext.close();
            }
        }

        private void closeSendContext() {
            if (sendContextTransferred.compareAndSet(false, true)) {
                sendScheduledContext.close();
            }
        }

        private void emergencyFailDelivery(Throwable deliveryFailure, Throwable resourceFailure) {
            terminal.set(true);
            var failure = combineCleanupFailures(deliveryFailure, resourceFailure);
            failure = runCancellationCleanup(failure, this::closePreparationContext);
            failure = runCancellationCleanup(failure, this::closeSendContext);
            completion.complete(new PreparationOutcome.Failed<>(failure));
        }

        private Throwable releasePendingTransformationDelivery(
            TransformedOutputAndResult<ByteBufListProducer> transformed
        ) {
            if (transformed == null
                || !pendingTransformationDelivery.compareAndSet(transformed, null)) {
                return null;
            }
            return releaseLateTransformationFailure(transformed);
        }

        private void releaseLateTransformation(
            TransformedOutputAndResult<ByteBufListProducer> transformed
        ) {
            var failure = releaseLateTransformationFailure(transformed);
            rethrowCancellationCleanupFailure(failure);
        }

        private Throwable releaseLateTransformationFailure(
            TransformedOutputAndResult<ByteBufListProducer> transformed
        ) {
            if (transformed != null && transformed.transformedOutput != null) {
                var packetProducer = transformed.transformedOutput;
                try {
                    packetProducer.trackOwnership(resourceOwnershipMetrics);
                } catch (Throwable trackingFailure) {
                    try {
                        packetProducer.close();
                    } catch (Throwable closeFailure) {
                        trackingFailure.addSuppressed(closeFailure);
                    }
                    return trackingFailure;
                }
                try {
                    packetProducer.close();
                } catch (Throwable closeFailure) {
                    return closeFailure;
                }
            }
            return null;
        }
    }

    static TargetAttemptOutcome<AggregatedRawResponse> classifyTargetAttemptOutcome(
        AggregatedRawResponse response
    ) {
        if (response == null) {
            throw new IllegalStateException("target attempt completed without a response value");
        }
        var failure = response.getError();
        if (failure != null) {
            var cause = TrackedFuture.unwindPossibleCompletionException(failure);
            if (!isExpectedNoResponseFailure(cause)) {
                if (cause instanceof Error error) {
                    throw error;
                }
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new CompletionException(cause);
            }
            var kind = cause instanceof ReadTimeoutException
                ? NoTargetResponseKind.READ_TIMEOUT
                : NoTargetResponseKind.TRANSPORT_FAILURE;
            return new TargetAttemptOutcome.NoTargetResponseObtained<>(
                NoTargetResponseDiagnostic.fromCause(kind, cause)
            );
        }
        if (response.getRawResponse() != null) {
            return new TargetAttemptOutcome.TargetResponseObtained<>(response);
        }
        return new TargetAttemptOutcome.NoTargetResponseObtained<>(
            new NoTargetResponseDiagnostic(
                NoTargetResponseKind.MISSING_HTTP_RESPONSE,
                "target attempt completed without an HTTP response"
            )
        );
    }

    private static boolean isExpectedNoResponseFailure(Throwable failure) {
        return failure instanceof IOException || failure instanceof ReadTimeoutException;
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

        public RetryDirective directive() {
            return directive;
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
*/
// REBUILD-LIMBO-END(G5)
        /**
         * Return null to continue trying according to
         * @param arr
         * @return
         */
// REBUILD-LIMBO-START(G5)
/*
        TrackedFuture<String,DeterminedTransformedResponse<T>>
        visit(
            ByteBuf requestBytes,
            TargetAttemptOutcome<AggregatedRawResponse> outcome
        );

        @Override
        default void close() {}
    }

    public <T> TrackedFuture<String, T> scheduleRequestLifecycle(
        @NonNull UniqueReplayerRequestKey requestKey,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull IReplayContexts.IReplayerHttpTransactionContext context,
        @NonNull Instant preparationStart,
        @NonNull Instant sendStart,
        @NonNull Instant sendEnd,
        @NonNull Supplier<TrackedFuture<String, TransformedOutputAndResult<ByteBufListProducer>>> preparation,
        @NonNull Function<TransformedOutputAndResult<ByteBufListProducer>, RetryVisitor<T>> retryVisitorFactory,
        @NonNull Function<HttpRequestTransformationStatus, T> filteredResultFactory,
        @NonNull TargetConnectionOwner.RequestProcessingRegistration processingRegistration
    ) {
        var requestId = toReplayRequestId(requestKey);
        var runtime = actorRuntime(
            requestId.session(),
            partitionGenerationId,
            context.getChannelKeyContext()
        );
        var filteredResult = new AtomicReference<T>();
        var coordinator = new PreparationCoordinator<>(
            runtime,
            requestId,
            context,
            preparationStart,
            sendStart,
            sendEnd,
            preparation,
            retryVisitorFactory,
            filteredResultFactory,
            filteredResult
        );
        var admission = runtime.actor.admitRequestWithAcceptance(
            partitionGenerationId,
            requestId,
            requestKey.getSourceRequestIndex(),
            preparationStart,
            sendStart,
            coordinator,
            processingRegistration
        );
        admission.admissionResult().whenComplete((result, failure) -> {
            if (failure != null) {
                signalFatal(new Error(
                    "Request admission result failed for " + requestId,
                    unwrap(failure)
                ));
                return;
            }
            if (result instanceof TargetConnectionOwner.RequestAdmissionRejected rejected) {
                var preparationCleanup = coordinator.cancel(rejected.cause());
                var processingCleanup = processingRegistration.rejectAdmission(rejected.cause());
                CompletableFuture.allOf(
                    preparationCleanup.toCompletableFuture(),
                    processingCleanup.toCompletableFuture()
                ).whenComplete((ignored, cleanupFailure) -> {
                    if (cleanupFailure != null) {
                        signalFatal(new Error(
                            "Rejected request admission cleanup failed for " + requestId,
                            unwrap(cleanupFailure)
                        ));
                    }
                });
            }
        });
        var targetTurn = admission.turnCompletion();
        CompletionStage<T> result = targetTurn.thenCompose(outcome -> {
            if (outcome instanceof RequestTurnResult.Completed<?> completed) {
                @SuppressWarnings("unchecked")
                var value = (T) completed.value();
                return CompletableFuture.completedFuture(value);
            }
            if (outcome instanceof RequestTurnResult.Cancelled<?> cancelled) {
                return CompletableFuture.failedFuture(cancelled.cause());
            }
            if (outcome instanceof RequestTurnResult.PreparationFiltered<?>) {
                return CompletableFuture.completedFuture(filteredResult.get());
            }
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Unknown request-turn result " + outcome
            ));
        });
        return new TextTrackedFuture<>(
            result.toCompletableFuture(),
            () -> "waiting for the connection actor to settle " + requestId
        );
    }

    public TransactionRuntime transactionRuntime(
        @NonNull UniqueReplayerRequestKey requestKey,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull IReplayContexts.IChannelKeyContext channelContext
    ) {
        var requestId = toReplayRequestId(requestKey);
        var runtime = actorRuntime(requestId.session(), partitionGenerationId, channelContext);
        return new TransactionRuntime(requestId, runtime, runtime.transactions);
    }

    public static final class TransactionRuntime {
        private final ReplayRequestId requestId;
        private final ActorRuntime runtime;
        private final ReplayTransactionRegistry transactions;

        private TransactionRuntime(
            ReplayRequestId requestId,
            ActorRuntime runtime,
            ReplayTransactionRegistry transactions
        ) {
            this.requestId = requestId;
            this.runtime = runtime;
            this.transactions = transactions;
        }

        public ReplayRequestId requestId() {
            return requestId;
        }

        public ActorMailbox mailbox() {
            return runtime.mailbox;
        }

        public TargetConnectionOwner.RequestProcessingRegistration processingRegistration(
            ReplayTransaction<?> transaction
        ) {
            var processingCompletion =
                new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
            transactions.register(requestId, transaction).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    processingCompletion.completeExceptionally(unwrap(failure));
                }
            });
            var cancellationCause = new AtomicReference<CancellationException>();
            var cancellationDecision =
                new CompletableFuture<ProcessingCancellationResult>();
            transaction.completion().whenComplete((outcome, failure) -> {
                if (failure != null) {
                    var processingFailure = unwrap(failure);
                    var requestedCancellation = cancellationCause.get();
                    if (requestedCancellation == null) {
                        processingCompletion.completeExceptionally(processingFailure);
                    } else {
                        cancellationDecision.whenComplete((decision, decisionFailure) -> {
                            if (decisionFailure != null) {
                                processingCompletion.completeExceptionally(
                                    unwrap(decisionFailure)
                                );
                            } else if (decision
                                instanceof ProcessingCancellationResult.CancellationWon) {
                                processingCompletion.complete(
                                    new TargetConnectionOwner.RequestProcessingOutcome
                                        .RequestCleanupFinished(requestedCancellation)
                                );
                            } else {
                                processingCompletion.completeExceptionally(processingFailure);
                            }
                        });
                    }
                    return;
                }
                try {
                    processingCompletion.complete(
                        outcome.evidenceOutcome().visit(new EvidenceOutcome.Visitor<>() {
                            @Override
                            public TargetConnectionOwner.RequestProcessingOutcome onDurable(
                                EvidenceOutcome.Durable durable
                            ) {
                                return new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable();
                            }

                            @Override
                            public TargetConnectionOwner.RequestProcessingOutcome onFailed(
                                EvidenceOutcome.Failed failed
                            ) {
                                throw new CompletionException(failed.cause());
                            }

                            @Override
                            public TargetConnectionOwner.RequestProcessingOutcome onNotRequired(
                                EvidenceOutcome.NotRequired notRequired
                            ) {
                                if (outcome.targetCancellation() != null) {
                                    return new TargetConnectionOwner.RequestProcessingOutcome
                                        .RequestCleanupFinished(outcome.targetCancellation());
                                }
                                throw new CompletionException(new IllegalStateException(
                                    "evidence was not required without typed target cancellation"
                                ));
                            }
                        })
                    );
                } catch (Throwable mappingFailure) {
                    processingCompletion.completeExceptionally(unwrap(mappingFailure));
                }
            });
            return TargetConnectionOwner.RequestProcessingRegistration.withTypedCancellation(
                processingCompletion,
                cause -> {
                    cancellationCause.compareAndSet(null, cause);
                    var decisionStage = transaction.requestCancellation(cause);
                    decisionStage.whenComplete((decision, failure) -> {
                        if (failure == null) {
                            cancellationDecision.complete(decision);
                        } else {
                            cancellationDecision.completeExceptionally(unwrap(failure));
                        }
                    });
                    return decisionStage;
                }
            );
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

*/
// REBUILD-LIMBO-END(G5)
    /** Connection sessions whose actor has not reached termination, for shutdown diagnostics. */
// REBUILD-LIMBO-START(G5)
/*
    public Set<String> describeUnterminatedSessions() {
        return actorRuntimes.values().stream()
            .filter(runtime -> !runtime.terminationOwner.isDone())
            .map(runtime -> runtime.key.toString())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    public TrackedFuture<String, SessionOutcome> scheduleActorClose(
        @NonNull IReplayContexts.IChannelKeyContext context,
        int sessionNumber,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull Instant timestamp
    ) {
        return scheduleActorCloseWithAcceptance(
            context,
            sessionNumber,
            partitionGenerationId,
            timestamp
        ).termination();
    }

    public ScheduledClose scheduleActorCloseWithAcceptance(
        @NonNull IReplayContexts.IChannelKeyContext context,
        int sessionNumber,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull Instant timestamp
    ) {
        return scheduleActorCloseWithAcceptance(
            context,
            sessionNumber,
            partitionGenerationId,
            null,
            timestamp
        );
    }

    public ScheduledClose scheduleActorCloseWithAcceptance(
        @NonNull IReplayContexts.IChannelKeyContext context,
        int sessionNumber,
        @NonNull PartitionGenerationId partitionGenerationId,
        long capturedOrdinal,
        @NonNull Instant timestamp
    ) {
        return scheduleActorCloseWithAcceptance(
            context,
            sessionNumber,
            partitionGenerationId,
            Long.valueOf(capturedOrdinal),
            timestamp
        );
    }

    private ScheduledClose scheduleActorCloseWithAcceptance(
        IReplayContexts.IChannelKeyContext context,
        int sessionNumber,
        PartitionGenerationId partitionGenerationId,
        Long capturedOrdinal,
        Instant timestamp
    ) {
        var sessionKey = toConnectionSessionKey(context, sessionNumber);
        var runtime = actorRuntime(sessionKey, partitionGenerationId, context);
        var admission = capturedOrdinal == null
            ? runtime.actor.admitCloseWithAcceptance(partitionGenerationId, timestamp)
            : runtime.actor.admitCloseWithAcceptance(
                partitionGenerationId,
                capturedOrdinal,
                timestamp
            );
        return new ScheduledClose(
            admission.admissionAccepted(),
            new TextTrackedFuture<>(
                runtime.termination().toCompletableFuture(),
                () -> "waiting for ordered actor close for " + sessionKey
            )
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
        PartitionGenerationId partitionGenerationId,
        IReplayContexts.IChannelKeyContext channelContext
    ) {
        synchronized (actorLifecycleLock) {
            if (actorShutdown != null) {
                throw actorShutdown.cause;
            }
            final ActorRuntime runtime;
            try {
                runtime = actorRuntimes.computeIfAbsent(
                    key,
                    ignored -> new ActorRuntime(key, partitionGenerationId, channelContext)
                );
            } catch (Error failure) {
                signalFatal(failure);
                throw failure;
            } catch (RuntimeException failure) {
                signalFatal(new Error(
                    "connection owner could not be created for " + key,
                    failure
                ));
                throw failure;
            }
            if (!runtime.partitionGenerationId.equals(partitionGenerationId)) {
                var failure = new IllegalStateException(
                    "connection runtime "
                        + key
                        + " is bound to "
                        + runtime.partitionGenerationId
                        + " but received "
                        + partitionGenerationId
                );
                signalFatal(new Error(
                    "Connection runtime generation mismatch for " + key,
                    failure
                ));
                throw failure;
            }
            var runwayLossReason = globalRunwayLossReason.get();
            if (runwayLossReason != null) {
                runtime.transactions.observeRunwayLost(runwayLossReason);
            }
            return runtime;
        }
    }

    private static ReplayRequestId toReplayRequestId(UniqueReplayerRequestKey requestKey) {
        return ReplayIdentity.replayRequestId(requestKey);
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

*/
// REBUILD-LIMBO-END(G5)