package org.opensearch.migrations.replay;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;
import org.opensearch.migrations.replay.lifecycle.RecordWorkTracker;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplaySessionWorkId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.TerminalSourceConnectionId;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeOwner;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController.WorkToken;
import org.opensearch.migrations.replay.lifecycle.ReplayReadGate;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.sink.ThreadLocalTupleWriter;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class TrafficReplayerCore extends RequestTransformerAndSender<TransformedTargetRequestAndResponseList> {

    public interface IWorkTracker<T> {
        void put(UniqueReplayerRequestKey uniqueReplayerRequestKey, TrackedFuture<String, T> completableFuture);

        void remove(UniqueReplayerRequestKey uniqueReplayerRequestKey);

        boolean isEmpty();

        int size();
    }

    private static final class TargetExchangeResult {
        private final TransformedTargetRequestAndResponseList summary;
        private final Throwable failure;
        private final CancellationException cancellation;
        private final TargetResponseClassifier.TargetResponseClassification classification;

        private TargetExchangeResult(
            TransformedTargetRequestAndResponseList summary,
            Throwable failure,
            CancellationException cancellation,
            TargetResponseClassifier.TargetResponseClassification classification
        ) {
            this.summary = summary;
            this.failure = failure;
            this.cancellation = cancellation;
            this.classification = classification;
        }
    }

    static CompletionStage<Void> acceptOrderedCloseOutcome(SessionOutcome outcome) {
        return outcome.visit(new SessionOutcome.Visitor<>() {
            @Override
            public CompletionStage<Void> onClosed(SessionOutcome.Closed closed) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> onAborted(SessionOutcome.Aborted aborted) {
                return aborted.reason() == AbortReason.SOURCE_REASSIGNMENT
                    || aborted.reason() == AbortReason.SHUTDOWN
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(aborted.cause());
            }

            @Override
            public CompletionStage<Void> onFailed(SessionOutcome.Failed failed) {
                return CompletableFuture.failedFuture(failed.cause());
            }
        });
    }

    static void settleProgressAfterProcessingLifecycleIsHandled(
        @NonNull org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestProcessingRegistration
            processingRegistration,
        @NonNull WorkToken progressToken
    ) {
        processingRegistration.completion().whenComplete((outcome, failure) -> {
            if (failure != null) {
                progressToken.close();
            } else {
                processingRegistration.lifecycleHandled().whenComplete(
                    (ignored, lifecycleFailure) -> progressToken.close()
                );
            }
        });
    }

    private final PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory;
    protected final int maxConcurrentTargetAttempts;
    protected final AtomicInteger successfulRequestCount;
    protected final AtomicInteger exceptionRequestCount;
    public final IRootReplayerContext topLevelContext;
    protected final IWorkTracker<Void> requestWorkTracker;
    protected IJsonTransformer responsePostProcessor;
    private final TargetResponseClassifier targetResponseClassifier;


    protected volatile ReplayIntakeOwner intakeOwner;

    protected TrafficReplayerCore(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformer,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        int maxConcurrentTargetAttempts,
        IWorkTracker<Void> requestWorkTracker,
        IRetryVisitorFactory retryVisitorFactory
    ) {
        this(
            context,
            serverUri,
            authTransformer,
            jsonTransformerSupplier,
            maxConcurrentTargetAttempts,
            requestWorkTracker,
            retryVisitorFactory,
            new TargetResponseClassifier(
                new org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier(),
                ExceptionTypeAllowlist.empty()
            )
        );
    }

    protected TrafficReplayerCore(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformer,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        int maxConcurrentTargetAttempts,
        IWorkTracker<Void> requestWorkTracker,
        IRetryVisitorFactory retryVisitorFactory,
        TargetResponseClassifier targetResponseClassifier
    ) {
        super(retryVisitorFactory);
        this.topLevelContext = context;

        if (serverUri.getPort() < 0) {
            throw new IllegalArgumentException("Port not present for URI: " + serverUri);
        }
        if (serverUri.getHost() == null) {
            throw new IllegalArgumentException("Hostname not present for URI: " + serverUri);
        }
        if (serverUri.getScheme() == null) {
            throw new IllegalArgumentException("Scheme (http|https) is not present for URI: " + serverUri);
        }
        if (maxConcurrentTargetAttempts <= 0) {
            throw new IllegalArgumentException("maxConcurrentTargetAttempts must be positive");
        }
        this.maxConcurrentTargetAttempts = maxConcurrentTargetAttempts;
        this.requestWorkTracker = requestWorkTracker;
        inputRequestTransformerFactory = new PacketToTransformingHttpHandlerFactory(jsonTransformerSupplier, authTransformer);
        successfulRequestCount = new AtomicInteger();
        exceptionRequestCount = new AtomicInteger();
        this.targetResponseClassifier = Objects.requireNonNull(targetResponseClassifier);
    }

    protected abstract CompletableFuture<Void> shutdown(Error error);

    class TrafficReplayerAccumulationCallbacks implements AccumulationCallbacks {
        private final ReplayEngine replayEngine;
        private final ThreadLocalTupleWriter tupleWriter;
        /** Legacy synchronous tuple consumer (Log4J path). Mutually exclusive with tupleWriter. */
        private final Consumer<SourceTargetCaptureTuple> resultTupleConsumer;
        @lombok.Setter
        private Consumer<SourceTargetCaptureTuple> tupleObserver;
        private ITrafficCaptureSource trafficCaptureSource;
        /** How long to delay the first request on a resumed connection. Configurable via CLI. */
        private final Duration quiescentDuration;
        private final RecordWorkTracker recordWorkTracker;
        private final SourceReconstructionPolicy sourceReconstructionPolicy;
        private final Map<ConnectionSessionKey, SourcePartitionKey> sessionPartitions = new ConcurrentHashMap<>();

        TrafficReplayerAccumulationCallbacks(
            ReplayEngine replayEngine,
            ThreadLocalTupleWriter tupleWriter,
            Consumer<SourceTargetCaptureTuple> resultTupleConsumer,
            Consumer<SourceTargetCaptureTuple> tupleObserver,
            ITrafficCaptureSource trafficCaptureSource,
            Duration quiescentDuration,
            RecordWorkTracker recordWorkTracker
        ) {
            this.replayEngine = replayEngine;
            this.tupleWriter = tupleWriter;
            this.resultTupleConsumer = resultTupleConsumer;
            this.tupleObserver = tupleObserver;
            this.trafficCaptureSource = trafficCaptureSource;
            this.quiescentDuration = quiescentDuration;
            this.sourceReconstructionPolicy = new SourceReconstructionPolicy(
                trafficCaptureSource.usesStructuralExpiration()
            );
            this.recordWorkTracker = recordWorkTracker;
        }

        private final class TransactionEvidenceState {
            private final IReplayContexts.IReplayerHttpTransactionContext context;
            private RequestResponsePacketPair source;
            private TransformedTargetRequestAndResponseList target;
            private Throwable targetFailure;
            private final ReplayRequestId replayRequestId;

            private TransactionEvidenceState(
                IReplayContexts.IReplayerHttpTransactionContext context,
                ReplayRequestId replayRequestId
            ) {
                this.context = context;
                this.replayRequestId = replayRequestId;
            }
        }

        SourcePartitionLifecycleListener sourcePartitionLifecycleListener() {
            return new SourcePartitionLifecycleListener() {
                @Override
                public void onAssigned(java.util.Collection<SourcePartitionKey> partitions) {}

                @Override
                public void onRevoked(java.util.Collection<SourcePartitionKey> partitions) {
                    var revoked = java.util.Set.copyOf(partitions);
                    sessionPartitions.forEach((sessionKey, partition) -> {
                        if (!revoked.contains(partition)) {
                            return;
                        }
                        replayEngine.observeRunwayLost(
                            sessionKey,
                            ReplayTransaction.RunwayLossReason.SOURCE_REASSIGNMENT
                        ).whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                failReplayForSessionLifecycle(sessionKey, unwrap(failure));
                            }
                        });
                    });
                }

                @Override
                public void onRetired(java.util.Collection<SourcePartitionKey> partitions) {
                    var retired = java.util.Set.copyOf(partitions);
                    sessionPartitions.entrySet().removeIf(
                        entry -> retired.contains(entry.getValue())
                    );
                }
            };
        }

        @Override
        public Consumer<RequestResponsePacketPair> onRequestReceived(
            @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
            @NonNull HttpMessageAndTimestamp request,
            boolean isResumedConnection
        ) {
            // quiescentDuration is passed to ReplayEngine which applies it relative to the
            // time-shifted start, not relative to now
            var quiescentDurationForRequest = isResumedConnection ? quiescentDuration : null;
            replayEngine.setFirstTimestamp(request.getFirstPacketTimestamp());

            var requestKey = ctx.getReplayerRequestKey();

            var finishedAccumulatingResponseFuture = new TextTrackedFuture<RequestResponsePacketPair>(
                () -> "waiting for response to be accumulated for " + ctx
            );
            finishedAccumulatingResponseFuture.future.whenComplete(
                (v, t) -> {
                    if (t == null) {
                        log.atDebug()
                            .setMessage("Done receiving captured stream for {}:{}")
                            .addArgument(ctx)
                            .addArgument(v.requestData)
                            .log();
                    }
                }
            );

            var partition = trafficCaptureSource.sourcePartitionFor(requestKey.trafficStreamKey);
            var runtime = replayEngine.transactionRuntime(
                partition.partitionGenerationId(),
                ctx
            );
            sessionPartitions.put(runtime.requestId().session(), partition);
            var recordId = trafficCaptureSource.recordIdFor(requestKey.trafficStreamKey);
            var owner = Objects.requireNonNull(
                TrafficReplayerCore.this.intakeOwner,
                "replay-intake owner"
            );
            var generationRegistration = owner.submitRequiredHandled(
                new ReplayIntakeOwner.RegisterRequestGeneration(
                    partition.partitionGenerationId(),
                    runtime.requestId(),
                    recordId instanceof KafkaRecordId
                )
            );
            var progressAdmission = replayEngine.admitWork(
                partition,
                runtime.requestId(),
                request.getFirstPacketTimestamp()
            );
            var progressToken = generationRegistration.thenCombine(
                progressAdmission,
                (ignored, token) -> token
            );
            var evidenceState = new TransactionEvidenceState(
                ctx,
                recordId instanceof KafkaRecordId ? ReplayIdentity.replayRequestId(requestKey) : null
            );
            var transaction = new ReplayTransaction<TargetExchangeResult>(
                runtime.requestId(),
                runtime.mailbox(),
                (requestId, sourceOutcome, targetResult) ->
                    writeTransactionEvidence(evidenceState),
                List.of(ctx),
                topLevelContext.getReplayTransactionMetrics()
            );
            var targetCompletion = new CompletableFuture<TransformedTargetRequestAndResponseList>();
            var targetFuture = new TextTrackedFuture<>(
                targetCompletion,
                () -> "waiting to admit and replay target request " + runtime.requestId()
            );
            settleTransactionTarget(
                transaction,
                targetFuture,
                finishedAccumulatingResponseFuture,
                evidenceState
            );
            var lifecycleSettlement = new CompletableFuture<CompletionStage<Void>>();
            progressToken.whenComplete((token, admissionFailure) -> {
                if (admissionFailure != null) {
                    var cause = unwrap(admissionFailure);
                    lifecycleSettlement.complete(CompletableFuture.failedFuture(cause));
                    targetCompletion.completeExceptionally(cause);
                    transaction.fail(cause);
                    return;
                }
                var processingRegistration = runtime.processingRegistration(transaction);
                lifecycleSettlement.complete(processingRegistration.lifecycleHandled());
                settleProgressAfterProcessingLifecycleIsHandled(
                    processingRegistration,
                    token
                );
                try {
                    var scheduledTarget = sendRequestAfterGoingThroughWorkQueue(
                        ctx,
                        request,
                        requestKey,
                        partition.partitionGenerationId(),
                        processingRegistration,
                        finishedAccumulatingResponseFuture,
                        quiescentDurationForRequest
                    );
                    scheduledTarget.future.whenComplete((summary, targetFailure) -> {
                        if (targetFailure == null) {
                            targetCompletion.complete(summary);
                        } else {
                            targetCompletion.completeExceptionally(unwrap(targetFailure));
                        }
                    });
                } catch (Throwable t) {
                    targetCompletion.completeExceptionally(t);
                    transaction.fail(t);
                }
            });

            var allWorkFinishedForTransactionFuture = new TextTrackedFuture<>(
                transaction.completion()
                    .thenCompose(outcome -> handleTransactionOutcome(evidenceState, outcome))
                    .thenCombine(
                        lifecycleSettlement.thenCompose(stage -> stage),
                        (ignored, lifecycleIgnored) -> (Void) null
                    )
                    .toCompletableFuture(),
                () -> "waiting for replay transaction disposition and lifecycle handling for "
                    + runtime.requestId()
            );
            log.atTrace().setMessage("Adding {} to targetTransactionInProgressMap").addArgument(requestKey).log();
            requestWorkTracker.put(requestKey, allWorkFinishedForTransactionFuture);
            allWorkFinishedForTransactionFuture.future.whenComplete((ignored, failure) -> {
                requestWorkTracker.remove(requestKey);
                log.atTrace()
                    .setMessage("removed replay transaction from targetTransactionInProgressMap for {}")
                    .addArgument(requestKey)
                    .log();
                if (failure != null) {
                    failReplayForTransaction(ctx, unwrap(failure));
                }
            });

            return rrPair -> {
                evidenceState.source = rrPair;
                finishedAccumulatingResponseFuture.future.complete(rrPair);
                transaction.settleSource(sourceReconstructionPolicy.classify(rrPair))
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            transaction.fail(unwrap(failure));
                        }
                    });
            };
        }

        private void settleTransactionTarget(
            ReplayTransaction<TargetExchangeResult> transaction,
            TrackedFuture<String, TransformedTargetRequestAndResponseList> targetFuture,
            TextTrackedFuture<RequestResponsePacketPair> sourceFuture,
            TransactionEvidenceState evidenceState
        ) {
            CompletionStage<TargetExchangeResult> capturedTarget = targetFuture.future
                .handle((summary, failure) ->
                    captureTargetResult(transaction, evidenceState, summary, failure)
                )
                .thenCompose(stage -> stage);
            capturedTarget
                .thenCombine(
                    sourceFuture.future,
                    this::classifyTargetResult
                )
                .whenComplete((result, failure) -> settleTargetOrFail(transaction, result, failure));
        }

        private CompletionStage<TargetExchangeResult> captureTargetResult(
            ReplayTransaction<TargetExchangeResult> transaction,
            TransactionEvidenceState evidenceState,
            TransformedTargetRequestAndResponseList summary,
            Throwable failure
        ) {
            var cause = failure == null ? null : unwrap(failure);
            evidenceState.target = summary;
            evidenceState.targetFailure = cause;
            var cancellation = cause instanceof CancellationException c ? c : null;
            var result = new TargetExchangeResult(
                summary,
                cancellation == null ? cause : null,
                cancellation,
                null
            );
            return summary == null
                ? CompletableFuture.completedFuture(result)
                : transaction.ownResource(summary).thenApply(ignored -> result);
        }

        private void settleTargetOrFail(
            ReplayTransaction<TargetExchangeResult> transaction,
            TargetExchangeResult result,
            Throwable failure
        ) {
            if (failure != null) {
                transaction.fail(unwrap(failure));
                return;
            }
            CompletionStage<Void> settlement = result.cancellation != null
                ? transaction.settleTargetCancellation(result.cancellation)
                : transaction.settleTargetResult(result);
            settlement.whenComplete((ignored, settlementFailure) -> {
                if (settlementFailure != null) {
                    transaction.fail(unwrap(settlementFailure));
                }
            });
        }

        private CompletionStage<EvidenceOutcome> writeTransactionEvidence(TransactionEvidenceState state) {
            if (state.source == null) {
                return CompletableFuture.completedFuture(
                    new EvidenceOutcome.Failed(
                        new IllegalStateException("source outcome settled without a source request/response pair")
                    )
                );
            }
            try (var tupleHandlingContext = state.context.createTupleContext()) {
                if (tupleWriter == null) {
                    packageAndWriteResponse(
                        tupleHandlingContext,
                        resultTupleConsumer,
                        state.source,
                        state.target,
                        state.targetFailure
                    );
                    return CompletableFuture.completedFuture(
                        new EvidenceOutcome.Durable("synchronous tuple consumer")
                    );
                }
                CompletionStage<Void> tupleWrite;
                try {
                    tupleWrite = packageAndWriteTuple(
                        tupleHandlingContext,
                        tupleWriter,
                        state.source,
                        state.target,
                        state.targetFailure
                    );
                } catch (Throwable failure) {
                    return CompletableFuture.completedFuture(
                        new EvidenceOutcome.Failed(fatalTupleWriteFailure(state.context, failure))
                    );
                }
                return tupleWrite.handle((ignored, failure) ->
                    failure == null
                        ? new EvidenceOutcome.Durable("whole tuple durable")
                        : new EvidenceOutcome.Failed(fatalTupleWriteFailure(state.context, failure))
                );
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(new EvidenceOutcome.Failed(unwrap(t)));
            }
        }

        private Error fatalTupleWriteFailure(
            IReplayContexts.IReplayerHttpTransactionContext context,
            Throwable failure
        ) {
            return new Error(
                "Fatal tuple write failure for " + context + ". The replayer is stopping without "
                    + "marking contributing source records complete because tuple output was not "
                    + "durably written. Fix the tuple sink failure before restarting; otherwise "
                    + "replay will remain blocked at these records.",
                unwrap(failure)
            );
        }

        private CompletionStage<Void> handleTransactionOutcome(
            TransactionEvidenceState state,
            ReplayTransaction.TransactionOutcome<TargetExchangeResult> outcome
        ) {
            var targetResult = outcome.targetResult();
            countFinalOutcome(
                state.target,
                targetResult == null ? state.targetFailure : targetResult.failure,
                targetResult == null ? null : targetResult.classification
            );
            recordTargetResponseCodes(state.target);
            return CompletableFuture.completedFuture(null);
        }

        private TargetExchangeResult classifyTargetResult(
            TargetExchangeResult result,
            RequestResponsePacketPair source
        ) {
            if (result.failure != null || result.cancellation != null) {
                return result;
            }
            if (result.summary != null && result.summary.getTransformationStatus().isSkipped()) {
                return result;
            }
            if (result.summary != null && result.summary.getTransformationStatus().isError()) {
                return new TargetExchangeResult(
                    result.summary,
                    result.summary.getTransformationStatus().getException(),
                    null,
                    null
                );
            }
            if (result.summary == null) {
                return new TargetExchangeResult(
                    null,
                    new IllegalStateException("Target exchange completed without a result"),
                    null,
                    null
                );
            }
            return new TargetExchangeResult(
                result.summary,
                null,
                null,
                targetResponseClassifier.classify(result.summary, source)
            );
        }

        private void failReplayForTransaction(
            IReplayContexts.IReplayerHttpTransactionContext context,
            Throwable failure
        ) {
            if (failure instanceof CancellationException) {
                log.atInfo()
                    .setMessage("Replay transaction for {} was cancelled before settling")
                    .addArgument(context)
                    .log();
                return;
            }
            var fatalError = failure instanceof Error error
                ? error
                : new Error(
                    "Fatal replay transaction failure for " + context,
                    failure
                );
            log.atError()
                .setCause(failure)
                .setMessage("Replay transaction failed for {}; shutting down")
                .addArgument(context)
                .log();
            shutdown(fatalError);
        }

        private Throwable unwrap(Throwable throwable) {
            return TrackedFuture.unwindPossibleCompletionException(throwable);
        }

        /**
         * @see RequestTransformerAndSender#transformAndSendRequest
         */
        private TrackedFuture<String, TransformedTargetRequestAndResponseList> sendRequestAfterGoingThroughWorkQueue(
            IReplayContexts.IReplayerHttpTransactionContext ctx,
            HttpMessageAndTimestamp request,
            UniqueReplayerRequestKey requestKey,
            ReplayIdentity.PartitionGenerationId partitionGenerationId,
            org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestProcessingRegistration
                processingRegistration,
            TextTrackedFuture<RequestResponsePacketPair> finishedAccumulatingResponseFuture,
            Duration quiescentDurationForRequest) {
            log.atDebug().setMessage("[{}] Admitting request before asynchronous preparation")
                .addArgument(ctx::getConnectionId)
                .log();
            var httpSentRequestFuture = TrafficReplayerCore.this.transformAndSendRequest(
                inputRequestTransformerFactory,
                replayEngine,
                partitionGenerationId,
                finishedAccumulatingResponseFuture,
                ctx,
                request.getFirstPacketTimestamp(),
                request.getLastPacketTimestamp(),
                request.packetBytes::stream,
                quiescentDurationForRequest,
                processingRegistration
            );
            httpSentRequestFuture.future.whenComplete(
                (v, t) -> log.atTrace()
                    .setMessage("Summary response value for {} returned={}")
                    .addArgument(requestKey).addArgument(v)
                    .log());
            return httpSentRequestFuture;
        }

        private void countFinalOutcome(
            TransformedTargetRequestAndResponseList summary,
            Throwable t,
            TargetResponseClassifier.TargetResponseClassification classification
        ) {
            if (t != null) {
                exceptionRequestCount.incrementAndGet();
            } else if (classification instanceof TargetResponseClassifier.TargetResponseClassification.Unsuccessful
                || classification instanceof TargetResponseClassifier.TargetResponseClassification.Allowlisted) {
                exceptionRequestCount.incrementAndGet();
            } else if (summary == null || summary.getResponseList().isEmpty()) {
                // no response to count
            } else {
                var lastResponse = summary.getResponseList().get(summary.getResponseList().size() - 1);
                if (lastResponse.getError() != null || summary.getTransformationStatus().isError()) {
                    exceptionRequestCount.incrementAndGet();
                } else {
                    successfulRequestCount.incrementAndGet();
                }
            }
        }

        private void recordTargetResponseCodes(TransformedTargetRequestAndResponseList summary) {
            if (summary != null) {
                for (var resp : summary.responses()) {
                    if (resp.getRawResponse() != null) {
                        replayEngine.recordTargetResponseCode(resp.getRawResponse().status().code());
                    }
                }
            }
        }

        @Override
        public void onConnectionClose(
            int channelInteractionNum,
            @NonNull IReplayContexts.IChannelKeyContext ctx,
            int channelSessionNumber,
            RequestResponsePacketPair.ReconstructionStatus status,
            @NonNull Instant timestamp,
            @NonNull ITrafficStreamKey connectionKey,
            @NonNull Optional<TerminalSourceConnectionId> terminalAssociation
        ) {
            var sessionKey = sessionKey(ctx, channelSessionNumber);
            var progressToken = new AtomicReference<WorkToken>();
            CompletionStage<Void> actorTermination;
            trafficCaptureSource.onConnectionAccumulationComplete(connectionKey);
            if (status == RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED) {
                log.atInfo()
                    .setMessage("Cancelling replay session {} after source generation interruption")
                    .addArgument(sessionKey)
                    .log();
                actorTermination = replayEngine.cancelConnection(ctx, channelSessionNumber).future;
            } else {
                var partition = trafficCaptureSource.sourcePartitionFor(connectionKey);
                var progressAdmission = replayEngine.admitWork(
                    partition,
                    new ReplaySessionWorkId(
                        sessionKey,
                        channelInteractionNum,
                        "captured-close"
                    ),
                    timestamp
                );
                actorTermination = progressAdmission.thenCompose(token -> {
                    progressToken.set(token);
                    replayEngine.setFirstTimestamp(timestamp);
                    var scheduledClose = replayEngine.closeConnectionWithAcceptance(
                        ctx,
                        channelSessionNumber,
                        partition.partitionGenerationId(),
                        Math.addExact(channelSessionNumber, channelInteractionNum),
                        timestamp
                    );
                    return scheduledClose.admissionAccepted()
                        .thenRun(() -> terminalAssociation.ifPresent(
                            recordWorkTracker::submitAssociationFinished
                        ))
                        .thenCompose(ignored ->
                            scheduledClose.termination().future
                                .thenCompose(TrafficReplayerCore::acceptOrderedCloseOutcome)
                        );
                });
            }
            actorTermination.whenComplete((ignored, failure) ->
                log.atDebug()
                    .setMessage("Target actor termination settled for {}; failure={}")
                    .addArgument(sessionKey)
                    .addArgument(failure)
                    .log()
            );
            actorTermination.whenComplete((ignored, failure) -> {
                sessionPartitions.remove(sessionKey);
                var ownedProgressToken = progressToken.get();
                if (ownedProgressToken != null) {
                    ownedProgressToken.close();
                }
                if (failure != null) {
                    failReplayForSessionLifecycle(sessionKey, unwrap(failure));
                }
            });
        }

        @Override
        public void onTrafficStreamsExpired(
            RequestResponsePacketPair.ReconstructionStatus status,
            @NonNull IReplayContexts.IChannelKeyContext ctx,
            @NonNull ITrafficStreamKey connectionKey
        ) {
            trafficCaptureSource.onConnectionAccumulationComplete(connectionKey);
        }

        private ConnectionSessionKey sessionKey(
            IReplayContexts.IChannelKeyContext context,
            int sessionNumber
        ) {
            return new ConnectionSessionKey(
                new SourceConnectionKey(context.getNodeId(), context.getConnectionId()),
                sessionNumber,
                context.getChannelKey().getSourceGeneration()
            );
        }

        private void failReplayForSessionLifecycle(ConnectionSessionKey sessionKey, Throwable failure) {
            var fatalError = new Error(
                "Fatal replay session lifecycle failure for " + sessionKey,
                failure
            );
            log.atError()
                .setCause(failure)
                .setMessage("Replay session lifecycle failed for {}; shutting down")
                .addArgument(sessionKey)
                .log();
            shutdown(fatalError);
        }

        private CompletableFuture<Void> packageAndWriteTuple(
            IReplayContexts.ITupleHandlingContext tupleHandlingContext,
            ThreadLocalTupleWriter tupleWriter,
            RequestResponsePacketPair rrPair,
            TransformedTargetRequestAndResponseList summary,
            Throwable t
        ) {
            log.trace("done sending and finalizing data to the packet handler");

            if (t != null) {
                log.atError().setMessage("Got exception in CompletableFuture callback for {}")
                    .addArgument(tupleHandlingContext)
                    .setCause(t)
                    .log();
            }
            CompletableFuture<Void> writeFuture;
            try (var requestResponseTuple = new SourceTargetCaptureTuple(tupleHandlingContext, rrPair, summary, t)) {
                log.atDebug()
                    .setMessage("Source/Target Request/Response tuple: {}").addArgument(requestResponseTuple).log();
                if (tupleObserver != null) {
                    tupleObserver.accept(requestResponseTuple);
                }
                var parsedMsgs = new ParsedHttpMessagesAsDicts(requestResponseTuple);
                if (responsePostProcessor != null) {
                    applyResponsePostProcessor(parsedMsgs);
                }
                writeFuture = tupleWriter.writeTuple(requestResponseTuple, parsedMsgs);
            }

            return writeFuture;
        }

        @SuppressWarnings("unchecked")
        private void applyResponsePostProcessor(ParsedHttpMessagesAsDicts parsedMsgs) {
            TrafficReplayerCore.applyResponsePostProcessor(responsePostProcessor, parsedMsgs);
        }

        private void packageAndWriteResponse(
            IReplayContexts.ITupleHandlingContext tupleHandlingContext,
            Consumer<SourceTargetCaptureTuple> tupleConsumer,
            RequestResponsePacketPair rrPair,
            TransformedTargetRequestAndResponseList summary,
            Throwable t
        ) {
            log.trace("done sending and finalizing data to the packet handler");

            if (t != null) {
                log.atError().setMessage("Got exception in CompletableFuture callback for {}")
                    .addArgument(tupleHandlingContext)
                    .setCause(t)
                    .log();
            }
            try (var requestResponseTuple = new SourceTargetCaptureTuple(tupleHandlingContext, rrPair, summary, t)) {
                log.atDebug()
                    .setMessage("Source/Target Request/Response tuple: {}").addArgument(requestResponseTuple).log();
                assert tupleConsumer != null : "expected non-null tuple consumer";
                tupleConsumer.accept(requestResponseTuple);
            }
        }
    }

    @Override
    protected void perResponseConsumer(AggregatedRawResponse summary,
                                       HttpRequestTransformationStatus transformationStatus,
                                       IReplayContexts.IReplayerHttpTransactionContext context) {
        // Logging only — counting moved to handleCompletedTransaction to avoid double-counting on retries
        if (summary != null && summary.getError() != null) {
            log.atInfo().setCause(summary.getError())
                .setMessage("Exception for {}: ").addArgument(context).log();
        } else if (transformationStatus.isError()) {
            log.atInfo()
                .setCause(Optional.ofNullable(summary).map(AggregatedRawResponse::getError).orElse(null))
                .setMessage("Unknown error transforming {}: ")
                .addArgument(context)
                .log();
        } else if (summary == null) {
            log.atInfo().setMessage("No result at all for {}: ")
                .addArgument(context)
                .log();
        }
    }

    /**
     * Test-facing source drain that uses the same dedicated, typed replay-intake owner as bootstrap.
     */
    @SneakyThrows
    public void pullCaptureFromSourceToAccumulator(
        ITrafficCaptureSource trafficSource,
        CapturedTrafficToHttpTransactionAccumulator accumulator
    ) throws InterruptedException {
        var owner = new ReplayIntakeOwner(failure -> {
            throw failure;
        });
        var permits = new TargetAttemptPermitProvider(
            1,
            owner::submitRequired,
            TargetAttemptPermitProvider.Metrics.NOOP
        );
        var noFlowControl = new BufferedFlowController() {
            @Override
            public void stopReadsPast(Instant pointInTime) {}

            @Override
            public Duration getBufferTimeWindow() {
                return Duration.ZERO;
            }
        };
        var progress = new ReplayProgressController(
            owner::submitRequired,
            new ReplayReadGate(Duration.ZERO, noFlowControl)
        );
        owner.configureOwnedComponents(permits, progress);
        owner.start();
        try {
            owner.startReading(
                trafficSource,
                accumulator,
                topLevelContext::createReadChunkContext
            ).toCompletableFuture().get();
        } finally {
            owner.stopOwner().toCompletableFuture().get();
            owner.termination().toCompletableFuture().get();
        }
    }

    /**
     * Apply a response post-processor to all target responses in the parsed messages.
     * Package-private static for testability.
     */
    @SuppressWarnings("unchecked")
    static void applyResponsePostProcessor(IJsonTransformer postProcessor, ParsedHttpMessagesAsDicts parsedMsgs) {
        var responses = parsedMsgs.targetResponseList;
        if (responses == null) return;
        for (int i = 0; i < responses.size(); i++) {
            var original = responses.get(i);
            if (original == null) continue;
            try {
                var transformed = (Map<String, Object>) postProcessor.transformJson(original);
                responses.set(i, transformed);
            } catch (Exception e) {
                log.atWarn().setCause(e)
                    .setMessage("Response post-processor failed for response {}, leaving empty")
                    .addArgument(i).log();
                responses.set(i, null);
            }
        }
    }
}
