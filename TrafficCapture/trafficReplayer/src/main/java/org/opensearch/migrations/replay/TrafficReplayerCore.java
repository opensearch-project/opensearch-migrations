package org.opensearch.migrations.replay;

import java.io.EOFException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.RecordDisposition;
import org.opensearch.migrations.replay.lifecycle.RecordDispositionLedger;
import org.opensearch.migrations.replay.lifecycle.ReplayDispositionPolicy;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplaySessionWorkId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeMailbox;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController.WorkToken;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.replay.sink.ThreadLocalTupleWriter;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.SourceInput;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
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

        private TargetExchangeResult(TransformedTargetRequestAndResponseList summary, Throwable failure) {
            this.summary = summary;
            this.failure = failure;
        }
    }

    static CompletableFuture<Void> combineConnectionCloseOperations(
        CompletionStage<Void> sourceDisposition,
        CompletionStage<Void> actorTermination
    ) {
        return CompletableFuture.allOf(
            sourceDisposition.toCompletableFuture(),
            actorTermination.toCompletableFuture()
        );
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
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(aborted.cause());
            }

            @Override
            public CompletionStage<Void> onFailed(SessionOutcome.Failed failed) {
                return CompletableFuture.failedFuture(failed.cause());
            }
        });
    }

    private final PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory;
    protected final int maxConcurrentRequests;
    protected final AtomicInteger successfulRequestCount;
    protected final AtomicInteger exceptionRequestCount;
    public final IRootReplayerContext topLevelContext;
    protected final IWorkTracker<Void> requestWorkTracker;
    protected IJsonTransformer responsePostProcessor;
    private final TargetResponseClassifier targetResponseClassifier;


    protected final AtomicBoolean stopReadingRef;
    protected final AtomicReference<CompletableFuture<List<SourceInput>>> nextChunkFutureRef;
    protected final AtomicReference<AsyncPermitPool> permitPoolRef;
    protected final AtomicReference<ReplayIntakeMailbox> intakeMailboxRef;

    protected TrafficReplayerCore(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformer,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        int maxConcurrentRequests,
        IWorkTracker<Void> requestWorkTracker,
        IRetryVisitorFactory retryVisitorFactory
    ) {
        this(
            context,
            serverUri,
            authTransformer,
            jsonTransformerSupplier,
            maxConcurrentRequests,
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
        int maxConcurrentRequests,
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
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.requestWorkTracker = requestWorkTracker;
        inputRequestTransformerFactory = new PacketToTransformingHttpHandlerFactory(jsonTransformerSupplier, authTransformer);
        successfulRequestCount = new AtomicInteger();
        exceptionRequestCount = new AtomicInteger();
        nextChunkFutureRef = new AtomicReference<>();
        stopReadingRef = new AtomicBoolean();
        permitPoolRef = new AtomicReference<>();
        intakeMailboxRef = new AtomicReference<>();
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
        private final AsyncPermitPool permitPool;
        private final ReplayDispositionPolicy dispositionPolicy;
        private final RecordDispositionLedger dispositionLedger;
        private final Map<ConnectionSessionKey, SourcePartitionKey> sessionPartitions = new LinkedHashMap<>();

        TrafficReplayerAccumulationCallbacks(
            ReplayEngine replayEngine,
            ThreadLocalTupleWriter tupleWriter,
            Consumer<SourceTargetCaptureTuple> resultTupleConsumer,
            Consumer<SourceTargetCaptureTuple> tupleObserver,
            ITrafficCaptureSource trafficCaptureSource,
            Duration quiescentDuration,
            AsyncPermitPool permitPool
        ) {
            this.replayEngine = replayEngine;
            this.tupleWriter = tupleWriter;
            this.resultTupleConsumer = resultTupleConsumer;
            this.tupleObserver = tupleObserver;
            this.trafficCaptureSource = trafficCaptureSource;
            this.quiescentDuration = quiescentDuration;
            this.permitPool = permitPool;
            this.dispositionPolicy = new ReplayDispositionPolicy();
            this.dispositionLedger = new RecordDispositionLedger(
                java.util.Objects.requireNonNull(intakeMailboxRef.get(), "replay intake mailbox")
            );
        }

        private final class TransactionEvidenceState {
            private final IReplayContexts.IReplayerHttpTransactionContext context;
            private RequestResponsePacketPair source;
            private TransformedTargetRequestAndResponseList target;
            private Throwable targetFailure;

            private TransactionEvidenceState(IReplayContexts.IReplayerHttpTransactionContext context) {
                this.context = context;
            }
        }

        private final class TrafficStreamRecordHandle implements RecordDispositionLedger.RecordHandle {
            private final ITrafficStreamKey key;
            private final RecordId id;

            private TrafficStreamRecordHandle(ITrafficStreamKey key) {
                this.key = key;
                this.id = trafficCaptureSource.recordIdFor(key);
            }

            @Override
            public RecordId id() {
                return id;
            }

            @Override
            public void closeContext() {
                key.getTrafficStreamsContext().close();
            }

            @Override
            public CompletionStage<Void> commit() {
                return trafficCaptureSource.commitTrafficStreamAsync(key);
            }
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

            var runtime = replayEngine.transactionRuntime(ctx);
            var partition = trafficCaptureSource.sourcePartitionFor(requestKey.trafficStreamKey);
            sessionPartitions.put(runtime.requestId().session(), partition);
            var progressToken = replayEngine.admitWork(
                partition,
                runtime.requestId(),
                request.getFirstPacketTimestamp()
            );
            var evidenceState = new TransactionEvidenceState(ctx);
            var transaction = new ReplayTransaction<TransformedTargetRequestAndResponseList>(
                runtime.requestId(),
                runtime.mailbox(),
                (requestId, sourceOutcome, targetOutcome) ->
                    writeTransactionEvidence(evidenceState, targetOutcome),
                dispositionPolicy,
                dispositionLedger,
                List.of(),
                List.of(progressToken, ctx)
            );
            runtime.register(transaction.completion()).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    transaction.fail(unwrap(failure));
                }
            });
            var targetFuture = sendRequestAfterGoingThroughWorkQueue(
                ctx,
                request,
                requestKey,
                finishedAccumulatingResponseFuture,
                quiescentDurationForRequest
            );
            settleTransactionTarget(
                transaction,
                targetFuture,
                finishedAccumulatingResponseFuture,
                evidenceState
            );

            var allWorkFinishedForTransactionFuture = new TextTrackedFuture<>(
                transaction.completion()
                    .thenCompose(outcome -> handleTransactionOutcome(evidenceState, outcome))
                    .toCompletableFuture(),
                () -> "waiting for replay transaction disposition for " + runtime.requestId()
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
                registerTransactionRecords(transaction, rrPair.getTrafficStreamsHeld())
                    .thenCompose(recordIds -> transaction.settleSource(
                        toSourceOutcome(rrPair),
                        recordIds
                    ))
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            transaction.fail(unwrap(failure));
                        }
                    });
            };
        }

        private void settleTransactionTarget(
            ReplayTransaction<TransformedTargetRequestAndResponseList> transaction,
            TrackedFuture<String, TransformedTargetRequestAndResponseList> targetFuture,
            TextTrackedFuture<RequestResponsePacketPair> sourceFuture,
            TransactionEvidenceState evidenceState
        ) {
            targetFuture.future
                .handle((summary, failure) -> captureTargetResult(evidenceState, summary, failure))
                .thenCombine(
                    sourceFuture.future,
                    (targetResult, source) -> toTargetOutcome(
                        targetResult.summary,
                        targetResult.failure,
                        source
                    )
                )
                .whenComplete((outcome, failure) -> settleTargetOrFail(transaction, outcome, failure));
        }

        private TargetExchangeResult captureTargetResult(
            TransactionEvidenceState evidenceState,
            TransformedTargetRequestAndResponseList summary,
            Throwable failure
        ) {
            var cause = failure == null ? null : unwrap(failure);
            evidenceState.target = summary;
            evidenceState.targetFailure = cause;
            return new TargetExchangeResult(summary, cause);
        }

        private void settleTargetOrFail(
            ReplayTransaction<TransformedTargetRequestAndResponseList> transaction,
            TargetOutcome<TransformedTargetRequestAndResponseList> outcome,
            Throwable failure
        ) {
            if (failure != null) {
                transaction.fail(unwrap(failure));
                return;
            }
            transaction.settleTarget(outcome).whenComplete((ignored, settlementFailure) -> {
                if (settlementFailure != null) {
                    transaction.fail(unwrap(settlementFailure));
                }
            });
        }

        private CompletionStage<List<RecordId>> registerTransactionRecords(
            ReplayTransaction<?> transaction,
            List<ITrafficStreamKey> keys
        ) {
            var handlesById = new LinkedHashMap<RecordId, TrafficStreamRecordHandle>();
            for (var key : keys) {
                var handle = new TrafficStreamRecordHandle(key);
                handlesById.putIfAbsent(handle.id(), handle);
            }
            var registrations = handlesById.values()
                .stream()
                .map(handle -> dispositionLedger.register(handle, transaction.ledgerOwner()).toCompletableFuture())
                .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(registrations)
                .thenApply(ignored -> List.copyOf(handlesById.keySet()));
        }

        private CompletionStage<EvidenceOutcome> writeTransactionEvidence(
            TransactionEvidenceState state,
            TargetOutcome<TransformedTargetRequestAndResponseList> targetOutcome
        ) {
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
                return packageAndWriteTuple(
                    tupleHandlingContext,
                    tupleWriter,
                    state.source,
                    state.target,
                    state.targetFailure
                ).handle((ignored, failure) ->
                    failure == null
                        ? new EvidenceOutcome.Durable("whole tuple durable")
                        : new EvidenceOutcome.Failed(unwrap(failure))
                );
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(new EvidenceOutcome.Failed(unwrap(t)));
            }
        }

        private CompletionStage<Void> handleTransactionOutcome(
            TransactionEvidenceState state,
            ReplayTransaction.TransactionOutcome outcome
        ) {
            countFinalOutcome(state.target, state.targetFailure, outcome.targetOutcome());
            recordTargetResponseCodes(state.target);
            if (!outcome.haltReplay()) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(transactionHaltError(outcome));
        }

        private Error transactionHaltError(ReplayTransaction.TransactionOutcome outcome) {
            return outcome.evidenceOutcome().visit(new EvidenceOutcome.Visitor<>() {
                @Override
                public Error onDurable(EvidenceOutcome.Durable durable) {
                    return new Error(
                        "Replay transaction " + outcome.requestId() + " retained its source records after "
                            + outcome.disposition().reasonCode(),
                        targetFailureCause(outcome.targetOutcome())
                    );
                }

                @Override
                public Error onFailed(EvidenceOutcome.Failed failed) {
                    return new Error(
                        "Fatal tuple write failure for " + outcome.requestId()
                            + ". Source records were retained because tuple output was not durably written.",
                        failed.cause()
                    );
                }

                @Override
                public Error onNotRequired(EvidenceOutcome.NotRequired notRequired) {
                    return new Error(
                        "Replay transaction " + outcome.requestId() + " retained its source records after "
                            + outcome.disposition().reasonCode(),
                        targetFailureCause(outcome.targetOutcome())
                    );
                }
            });
        }

        private <T> Throwable targetFailureCause(TargetOutcome<T> outcome) {
            return outcome.visit(new TargetOutcome.Visitor<T, Throwable>() {
                @Override
                public Throwable onSucceeded(TargetOutcome.Succeeded<T> succeeded) {
                    return null;
                }

                @Override
                public Throwable onFailed(TargetOutcome.Failed<T> failed) {
                    return failed.cause();
                }

                @Override
                public Throwable onCancelled(TargetOutcome.Cancelled<T> cancelled) {
                    return cancelled.cause();
                }

                @Override
                public Throwable onFiltered(TargetOutcome.Filtered<T> filtered) {
                    return null;
                }

                @Override
                public Throwable onClassifiedSkip(TargetOutcome.ClassifiedSkip<T> classifiedSkip) {
                    return null;
                }
            });
        }

        private TargetOutcome<TransformedTargetRequestAndResponseList> toTargetOutcome(
            TransformedTargetRequestAndResponseList summary,
            Throwable failure,
            RequestResponsePacketPair source
        ) {
            if (failure != null) {
                if (failure instanceof CancellationException cancellation) {
                    return new TargetOutcome.Cancelled<>(cancellation);
                }
                return new TargetOutcome.Failed<>(failure);
            }
            if (summary != null && summary.getTransformationStatus().isSkipped()) {
                return new TargetOutcome.Filtered<>("request transformation filter");
            }
            if (summary != null && summary.getTransformationStatus().isError()) {
                return new TargetOutcome.Failed<>(summary.getTransformationStatus().getException());
            }
            if (summary == null) {
                return new TargetOutcome.Failed<>(
                    new IllegalStateException("Target exchange completed without a result")
                );
            }
            return targetResponseClassifier.classify(summary, source);
        }

        private SourceOutcome toSourceOutcome(RequestResponsePacketPair rrPair) {
            return switch (rrPair.completionStatus) {
                case COMPLETE -> new SourceOutcome.Complete();
                case CONFIRMED_DEAD -> new SourceOutcome.ConfirmedDead(
                    Objects.requireNonNull(
                        rrPair.structuralProofId,
                        "Confirmed-dead source outcomes require a structural proof identity"
                    )
                );
                case EXPIRED_PREMATURELY ->
                    new SourceOutcome.Inconclusive("timestamp-only source expiry has no structural proof");
                case CLOSED_PREMATURELY ->
                    new SourceOutcome.Shutdown("source closed before request reconstruction completed");
                case TRAFFIC_SOURCE_READER_INTERRUPTED ->
                    new SourceOutcome.Interrupted("Kafka source generation was reassigned");
            };
        }

        private void failReplayForTransaction(
            IReplayContexts.IReplayerHttpTransactionContext context,
            Throwable failure
        ) {
            var fatalError = failure instanceof Error error
                ? error
                : new Error(
                    "Fatal replay transaction failure for " + context
                        + ". Source records were retained because the transaction did not reach a safe commit.",
                    failure
                );
            log.atError()
                .setCause(failure)
                .setMessage("Replay transaction failed for {}; shutting down without committing its source records")
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
            TextTrackedFuture<RequestResponsePacketPair> finishedAccumulatingResponseFuture,
            Duration quiescentDurationForRequest) {
            log.atDebug().setMessage("[{}] Admitting request before asynchronous preparation")
                .addArgument(ctx::getConnectionId)
                .log();
            var httpSentRequestFuture = TrafficReplayerCore.this.transformAndSendRequest(
                inputRequestTransformerFactory,
                replayEngine,
                finishedAccumulatingResponseFuture,
                ctx,
                request.getFirstPacketTimestamp(),
                request.getLastPacketTimestamp(),
                request.packetBytes::stream,
                quiescentDurationForRequest,
                permitPool
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
            TargetOutcome<?> targetOutcome
        ) {
            if (t != null) {
                exceptionRequestCount.incrementAndGet();
            } else if (targetOutcome instanceof TargetOutcome.Failed<?>
                || targetOutcome instanceof TargetOutcome.ClassifiedSkip<?>) {
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
            @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
        ) {
            var sessionKey = sessionKey(ctx, channelSessionNumber);
            WorkToken progressToken = null;
            CompletionStage<Void> sourceDisposition;
            CompletionStage<Void> actorTermination;
            if (status == RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED) {
                sourceDisposition = disposeSourceRecords(
                    trafficStreamKeysBeingHeld,
                    new RecordDisposition.Retain("source-reassigned"),
                    "interrupted connection close"
                );
                notifyConnectionDone(trafficStreamKeysBeingHeld);
                actorTermination = replayEngine.cancelConnection(ctx, channelSessionNumber).future;
            } else {
                progressToken = admitSessionWork(
                    sessionKey,
                    channelInteractionNum,
                    "captured-close",
                    timestamp,
                    trafficStreamKeysBeingHeld
                );
                notifyConnectionDone(trafficStreamKeysBeingHeld);
                sourceDisposition = disposeSourceRecords(
                    trafficStreamKeysBeingHeld,
                    sourceOnlyDisposition(status, "captured connection close"),
                    "captured connection close"
                );
                replayEngine.setFirstTimestamp(timestamp);
                actorTermination = replayEngine.closeConnection(ctx, channelSessionNumber, timestamp)
                    .future
                    .thenCompose(TrafficReplayerCore::acceptOrderedCloseOutcome);
            }
            var ownedProgressToken = progressToken;
            combineConnectionCloseOperations(sourceDisposition, actorTermination)
                .whenComplete((ignored, failure) -> {
                    sessionPartitions.remove(sessionKey);
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
            @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
        ) {
            notifyConnectionDone(trafficStreamKeysBeingHeld);
            disposeSourceRecords(
                trafficStreamKeysBeingHeld,
                sourceOnlyDisposition(status, "source accumulation expired"),
                "source accumulation expired"
            );
        }

        private void notifyConnectionDone(List<ITrafficStreamKey> keys) {
            if (keys != null && !keys.isEmpty()) {
                trafficCaptureSource.onConnectionAccumulationComplete(keys.get(0));
            }
        }

        @Override
        public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {
            disposeSourceRecords(
                List.of(ctx.getTrafficStreamKey()),
                new RecordDisposition.Commit("source-record-ignored"),
                "ignored source record"
            );
        }

        private WorkToken admitSessionWork(
            ConnectionSessionKey sessionKey,
            int interactionIndex,
            String operation,
            Instant sourceTime,
            List<ITrafficStreamKey> keys
        ) {
            var partition = keys == null || keys.isEmpty()
                ? sessionPartitions.get(sessionKey)
                : trafficCaptureSource.sourcePartitionFor(keys.get(0));
            if (partition == null) {
                partition = new SourcePartitionKey(
                    "unpartitioned-session",
                    0,
                    sessionKey.sourceGeneration()
                );
            }
            return replayEngine.admitWork(
                partition,
                new ReplaySessionWorkId(sessionKey, interactionIndex, operation),
                sourceTime
            );
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

        private RecordDisposition sourceOnlyDisposition(
            RequestResponsePacketPair.ReconstructionStatus status,
            String operation
        ) {
            return switch (status) {
                case COMPLETE -> new RecordDisposition.Commit(operation);
                case CONFIRMED_DEAD -> new RecordDisposition.Commit("source-confirmed-dead");
                case EXPIRED_PREMATURELY ->
                    new RecordDisposition.Retain("source-expired-without-structural-proof");
                case CLOSED_PREMATURELY ->
                    new RecordDisposition.Retain("source-closed-prematurely");
                case TRAFFIC_SOURCE_READER_INTERRUPTED ->
                    new RecordDisposition.Retain("source-reassigned");
            };
        }

        private CompletionStage<Void> disposeSourceRecords(
            List<ITrafficStreamKey> keys,
            RecordDisposition disposition,
            String operation
        ) {
            if (keys == null || keys.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            var handlesById = new LinkedHashMap<RecordId, TrafficStreamRecordHandle>();
            for (var key : keys) {
                var handle = new TrafficStreamRecordHandle(key);
                handlesById.putIfAbsent(handle.id(), handle);
            }
            var owner = operation + ":" + handlesById.keySet();
            var registrations = handlesById.values()
                .stream()
                .map(handle -> dispositionLedger.register(handle, owner).toCompletableFuture())
                .toArray(CompletableFuture[]::new);
            var completion = CompletableFuture.allOf(registrations)
                .thenCompose(ignored -> CompletableFuture.allOf(
                    handlesById.keySet()
                        .stream()
                        .map(recordId -> dispositionLedger.dispose(recordId, owner, disposition).toCompletableFuture())
                        .toArray(CompletableFuture[]::new)
                ));
            completion.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    failReplayForSourceDisposition(operation, handlesById.keySet(), unwrap(failure));
                }
            });
            return completion;
        }

        private void failReplayForSourceDisposition(
            String operation,
            java.util.Set<RecordId> recordIds,
            Throwable failure
        ) {
            var fatalError = new Error(
                "Fatal source-record disposition failure during " + operation + " for " + recordIds,
                failure
            );
            log.atError()
                .setCause(failure)
                .setMessage("Source-record disposition failed during {} for {}; shutting down")
                .addArgument(operation)
                .addArgument(recordIds)
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

    @SneakyThrows
    public void pullCaptureFromSourceToAccumulator(
        ITrafficCaptureSource trafficChunkStream,
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator
    ) throws InterruptedException {
        pullCaptureFromSourceToAccumulator(
            trafficChunkStream,
            trafficToHttpTransactionAccumulator,
            new ReplayIntakeMailbox()
        );
    }

    @SneakyThrows
    public void pullCaptureFromSourceToAccumulator(
        ITrafficCaptureSource trafficChunkStream,
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator,
        ReplayIntakeMailbox intakeMailbox
    ) throws InterruptedException {
        try {
            while (true) {
                log.trace("Reading next chunk from TrafficStream supplier");
                if (stopReadingRef.get()) {
                    break;
                }
                this.nextChunkFutureRef.set(
                    trafficChunkStream.readNextTrafficStreamChunk(topLevelContext::createReadChunkContext)
                );
                List<SourceInput> trafficStreams;
                try {
                    trafficStreams = intakeMailbox.await(this.nextChunkFutureRef.get());
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof EOFException) {
                        log.atWarn().setCause(ex.getCause())
                            .setMessage("Got an EOF on the stream.  " + "Done reading traffic streams.").log();
                        break;
                    } else {
                        log.atWarn().setCause(ex).setMessage("Done reading traffic streams due to exception.").log();
                        throw ex.getCause();
                    }
                }
                if (log.isDebugEnabled()) {
                    Optional.of(
                        trafficStreams.stream()
                            .filter(ITrafficStreamWithKey.class::isInstance)
                            .map(ITrafficStreamWithKey.class::cast)
                            .map(ts -> TrafficStreamUtils.summarizeTrafficStream(ts.getStream()))
                            .collect(Collectors.joining(";"))
                    )
                        .filter(s -> !s.isEmpty())
                        .ifPresent(s -> log.atDebug().setMessage("TrafficStream Summary: {{}}").addArgument(s).log());
                }
                log.atDebug().setMessage("Read {} traffic stream(s) from source")
                    .addArgument(trafficStreams::size)
                    .log();
                var batchStart = System.nanoTime();
                trafficStreams.forEach(trafficToHttpTransactionAccumulator::accept);
                intakeMailbox.runUntilIdle();
                var batchDurationMs = (System.nanoTime() - batchStart) / 1_000_000;
                if (batchDurationMs > 5_000) {
                    log.atWarn().setMessage("Batch processing took {}ms ({} records). " +
                            "This delays the next Kafka poll. max.poll.interval.ms may be at risk.")
                        .addArgument(batchDurationMs)
                        .addArgument(trafficStreams::size)
                        .log();
                }
            }
        } finally {
            intakeMailbox.runUntilIdle();
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
