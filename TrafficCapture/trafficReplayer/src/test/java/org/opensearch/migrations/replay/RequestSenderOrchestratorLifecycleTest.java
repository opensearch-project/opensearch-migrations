package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.RecordDisposition;
import org.opensearch.migrations.replay.lifecycle.RecordDispositionLedger;
import org.opensearch.migrations.replay.lifecycle.ReplayDispositionPolicy;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestSenderOrchestratorLifecycleTest extends InstrumentationTest {
    private final List<Integer> targetExecutionOrder = new CopyOnWriteArrayList<>();
    private final AtomicInteger targetExchanges = new AtomicInteger();
    private final AtomicReference<Function<ConnectionSessionKey, java.util.concurrent.CompletionStage<Void>>>
        sessionAcknowledger = new AtomicReference<>(ignored -> CompletableFuture.completedFuture(null));
    private ClientConnectionPool connectionPool;
    private RequestSenderOrchestrator orchestrator;

    @BeforeEach
    void createOrchestrator() {
        connectionPool = new ClientConnectionPool(
            (eventLoop, context) -> {
                throw new AssertionError("The test packet consumer does not need a Netty channel");
            },
            "actor-lifecycle-test",
            1
        );
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> new ImmediatePacketConsumer(
                context.getReplayerRequestKey().getReplayerRequestIndex()
            ),
            sessionKey -> sessionAcknowledger.get().apply(sessionKey)
        );
    }

    @AfterEach
    void closeOrchestrator() throws Exception {
        connectionPool.shutdownNow().get(5, TimeUnit.SECONDS);
    }

    @Test
    void preparationMayFinishOutOfOrderButTargetExecutionStaysInAdmissionOrder() throws Exception {
        var permits = new AsyncPermitPool(2, Runnable::run);
        var firstContext = rootContext.getTestConnectionRequestContext("ordered", 0);
        var secondContext = rootContext.getTestConnectionRequestContext("ordered", 1);
        var firstPreparation =
            new CompletableFuture<TransformedOutputAndResult<ByteBufListProducer>>();
        var secondPreparation =
            new CompletableFuture<TransformedOutputAndResult<ByteBufListProducer>>();

        var first = schedule(firstContext, permits, firstPreparation);
        var second = schedule(secondContext, permits, secondPreparation);
        secondPreparation.complete(transformedRequest());
        Thread.sleep(25);
        Assertions.assertTrue(targetExecutionOrder.isEmpty());

        firstPreparation.complete(transformedRequest());
        first.get(Duration.ofSeconds(5));
        second.get(Duration.ofSeconds(5));

        Assertions.assertEquals(List.of(0, 1), targetExecutionOrder);
        Assertions.assertEquals(2, targetExchanges.get());
        closeActor(firstContext);
    }

    @Test
    void abortCancelsPreparationAndQueuedPermitWithoutStartingTargetWork() throws Exception {
        var permits = new AsyncPermitPool(1, Runnable::run);
        var firstContext = rootContext.getTestConnectionRequestContext("abort", 0);
        var secondContext = rootContext.getTestConnectionRequestContext("abort", 1);
        var firstPreparation =
            new CompletableFuture<TransformedOutputAndResult<ByteBufListProducer>>();
        var secondPreparation =
            new CompletableFuture<TransformedOutputAndResult<ByteBufListProducer>>();
        var first = schedule(firstContext, permits, firstPreparation);
        var second = schedule(secondContext, permits, secondPreparation);

        orchestrator.abortActor(
            firstContext.getChannelKeyContext(),
            0,
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("rebalance")
        ).get(Duration.ofSeconds(5));

        Assertions.assertTrue(first.future.isCompletedExceptionally());
        Assertions.assertTrue(second.future.isCompletedExceptionally());
        Assertions.assertTrue(firstPreparation.isCancelled());
        Assertions.assertFalse(secondPreparation.isDone());
        Assertions.assertEquals(0, targetExchanges.get());

        var probe = permits.acquire(requestId("probe", 0), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        probe.close();
    }

    @Test
    void abortCancelsAnAlreadyScheduledRetryWithoutWaitingForItsDelay() throws Exception {
        var retryStarted = new CompletableFuture<Void>();
        var visitorClosed = new CompletableFuture<Void>();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            (session, context) -> new ImmediatePacketConsumer(
                context.getReplayerRequestKey().getReplayerRequestIndex()
            ),
            RequestSenderOrchestrator.noSourceTerminationObligations()
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("cancel-retry", 0);
        var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[] { 1 }));
        var request = orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            context,
            Instant.now().minusSeconds(1),
            Instant.now().minusMillis(1),
            Instant.now(),
            permits,
            () -> TextTrackedFuture.completedFuture(
                new TransformedOutputAndResult<>(
                    ByteBufListProducer.of(packets),
                    HttpRequestTransformationStatus.completed()
                ),
                () -> "prepared request"
            ),
            transformed -> new RequestSenderOrchestrator.RetryVisitor<>() {
                @Override
                public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<String>> visit(
                    ByteBuf requestBytes,
                    AggregatedRawResponse response,
                    Throwable failure
                ) {
                    retryStarted.complete(null);
                    return TextTrackedFuture.completedFuture(
                        new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                            RequestSenderOrchestrator.RetryDirective.RETRY,
                            "retry"
                        ),
                        () -> "force a delayed retry"
                    );
                }

                @Override
                public void close() {
                    visitorClosed.complete(null);
                }
            },
            status -> status.getClass().getSimpleName()
        );

        retryStarted.get(5, TimeUnit.SECONDS);
        orchestrator.abortActor(
            context.getChannelKeyContext(),
            0,
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("source reassigned")
        ).get(Duration.ofSeconds(2));

        Assertions.assertTrue(request.future.isCompletedExceptionally());
        Assertions.assertEquals(1, targetExchanges.get());
        visitorClosed.get(2, TimeUnit.SECONDS);
        var probe = permits.acquire(requestId("probe", 0), 1).toCompletableFuture().get(2, TimeUnit.SECONDS);
        probe.close();
    }

    @Test
    void abortDoesNotWaitForAResponseFinalizerThatNeverCompletes() throws Exception {
        var finalizationStarted = new CompletableFuture<Void>();
        var consumerAborted = new CompletableFuture<CancellationException>();
        var ownershipMetrics = new RecordingOwnershipMetrics();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> new IPacketFinalizingConsumer<>() {
                @Override
                public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
                    nextRequestPacket.release();
                    return TextTrackedFuture.completedFuture(null, () -> "packet consumed");
                }

                @Override
                public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
                    finalizationStarted.complete(null);
                    return new TextTrackedFuture<>(
                        new CompletableFuture<>(),
                        "response that never completes"
                    );
                }

                @Override
                public void abort(CancellationException cause) {
                    consumerAborted.complete(cause);
                }
            },
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            org.opensearch.migrations.replay.lifecycle.ConnectionActor.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ownershipMetrics
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("cancel-finalizer", 0);
        var request = schedule(
            context,
            permits,
            CompletableFuture.completedFuture(transformedRequest())
        );

        finalizationStarted.get(5, TimeUnit.SECONDS);
        var cancellation = new CancellationException("source reassigned");
        orchestrator.abortActor(
            context.getChannelKeyContext(),
            0,
            AbortReason.SOURCE_REASSIGNMENT,
            cancellation
        ).get(Duration.ofSeconds(2));

        Assertions.assertSame(cancellation, consumerAborted.get(2, TimeUnit.SECONDS));
        Assertions.assertTrue(request.future.isCompletedExceptionally());
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.PREPARED_REQUEST) == 0);
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.ATTEMPT_PAYLOAD) == 0);
        Assertions.assertEquals(1, ownershipMetrics.maxHandles(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
        var probe = permits.acquire(requestId("probe", 0), 1).toCompletableFuture().get(2, TimeUnit.SECONDS);
        probe.close();
    }

    @Test
    void terminalRetryResultIsReleasedWhenAbortWins() throws Exception {
        var retryStarted = new CompletableFuture<Void>();
        var retryDecision =
            new CompletableFuture<RequestSenderOrchestrator.DeterminedTransformedResponse<String>>();
        var releasedResults = new AtomicInteger();
        var ownershipMetrics = new RecordingOwnershipMetrics();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> new IPacketFinalizingConsumer<>() {
                @Override
                public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
                    nextRequestPacket.release();
                    return TextTrackedFuture.completedFuture(null, () -> "packet consumed");
                }

                @Override
                public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
                    return TextTrackedFuture.completedFuture(
                        new AggregatedRawResponse(null, 0, Duration.ZERO, null, null),
                        () -> "response completed"
                    );
                }
            },
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            org.opensearch.migrations.replay.lifecycle.ConnectionActor.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ownershipMetrics
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("abort-terminal-result", 0);
        var now = Instant.now();
        var request = orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            context,
            now.minusSeconds(1),
            now.minusMillis(1),
            now,
            permits,
            () -> TextTrackedFuture.completedFuture(transformedRequest(), () -> "prepared request"),
            transformed -> (requestBytes, response, failure) -> {
                retryStarted.complete(null);
                return new TextTrackedFuture<>(retryDecision, "controlled terminal retry decision");
            },
            status -> status.getClass().getSimpleName()
        );

        retryStarted.get(5, TimeUnit.SECONDS);
        orchestrator.abortActor(
            context.getChannelKeyContext(),
            0,
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("source reassigned")
        ).get(Duration.ofSeconds(2));
        retryDecision.complete(
            new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                RequestSenderOrchestrator.RetryDirective.DONE,
                "discarded",
                ignored -> releasedResults.incrementAndGet()
            )
        );

        await(() -> releasedResults.get() == 1);
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.PREPARED_REQUEST) == 0);
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.ATTEMPT_PAYLOAD) == 0);
        Assertions.assertTrue(request.future.isCompletedExceptionally());
    }

    @Test
    void exchangePhasesAreSerializedOnTheSessionEventLoopAndClearedAfterAbort() throws Exception {
        var metrics = new RecordingTargetExchangeMetrics();
        var consume = new CompletableFuture<Void>();
        var response = new CompletableFuture<AggregatedRawResponse>();
        var retryDecision =
            new CompletableFuture<RequestSenderOrchestrator.DeterminedTransformedResponse<String>>();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            (session, context) -> new IPacketFinalizingConsumer<>() {
                @Override
                public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
                    nextRequestPacket.release();
                    return new TextTrackedFuture<>(consume, "controlled packet write");
                }

                @Override
                public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
                    return new TextTrackedFuture<>(response, "controlled target response");
                }
            },
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            org.opensearch.migrations.replay.lifecycle.ConnectionActor.Metrics.NOOP,
            metrics
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("phase-state", 0);
        var now = Instant.now();
        var request = orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            context,
            now.minusSeconds(1),
            now.minusMillis(1),
            now,
            permits,
            () -> TextTrackedFuture.completedFuture(transformedRequest(), () -> "prepared request"),
            transformed -> (requestBytes, targetResponse, failure) ->
                new TextTrackedFuture<>(retryDecision, "controlled retry decision"),
            status -> status.getClass().getSimpleName()
        );

        metrics.awaitPhase(TargetExchangeState.Phase.SENDING_REQUEST);
        consume.complete(null);
        metrics.awaitPhase(TargetExchangeState.Phase.WAITING_FOR_RESPONSE);
        response.complete(new AggregatedRawResponse(null, 0, Duration.ZERO, null, null));
        metrics.awaitPhase(TargetExchangeState.Phase.EVALUATING_RETRY);
        retryDecision.complete(
            new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                RequestSenderOrchestrator.RetryDirective.RETRY,
                "retry"
            )
        );
        metrics.awaitPhase(TargetExchangeState.Phase.RETRY_DELAY);

        orchestrator.abortActor(
            context.getChannelKeyContext(),
            0,
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("source reassigned")
        ).get(Duration.ofSeconds(5));
        metrics.awaitNoActivePhase();

        Assertions.assertTrue(request.future.isCompletedExceptionally());
        Assertions.assertTrue(metrics.onlyOwnerThreadCallbacks());
        Assertions.assertEquals(
            List.of(
                TargetExchangeState.Phase.STARTING_ATTEMPT,
                TargetExchangeState.Phase.SENDING_REQUEST,
                TargetExchangeState.Phase.WAITING_FOR_RESPONSE,
                TargetExchangeState.Phase.EVALUATING_RETRY,
                TargetExchangeState.Phase.RETRY_DELAY,
                TargetExchangeState.Phase.ABORTING
            ),
            metrics.enteredPhases()
        );
    }

    @Test
    void synchronouslyCompletedExchangeClearsItsPhase() throws Exception {
        var metrics = new RecordingTargetExchangeMetrics();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> new ImmediatePacketConsumer(
                context.getReplayerRequestKey().getReplayerRequestIndex()
            ),
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            org.opensearch.migrations.replay.lifecycle.ConnectionActor.Metrics.NOOP,
            metrics
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("synchronous-phase-state", 0);

        var result = schedule(
            context,
            permits,
            CompletableFuture.completedFuture(transformedRequest())
        ).get(Duration.ofSeconds(5));

        metrics.awaitEnteredPhaseCount(4);
        metrics.awaitNoActivePhase();
        Assertions.assertEquals("sent", result);
        Assertions.assertTrue(metrics.onlyOwnerThreadCallbacks());
        Assertions.assertEquals(
            List.of(
                TargetExchangeState.Phase.STARTING_ATTEMPT,
                TargetExchangeState.Phase.SENDING_REQUEST,
                TargetExchangeState.Phase.WAITING_FOR_RESPONSE,
                TargetExchangeState.Phase.EVALUATING_RETRY
            ),
            metrics.enteredPhases()
        );
        closeActor(context);
    }

    @Test
    void synchronousPacketConsumerCreationFailureReleasesOwnedRequestResources() throws Exception {
        var ownershipMetrics = new RecordingOwnershipMetrics();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> {
                throw new IllegalStateException("packet consumer creation failed");
            },
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            org.opensearch.migrations.replay.lifecycle.ConnectionActor.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ownershipMetrics
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("sender-factory-failure", 0);

        var request = schedule(
            context,
            permits,
            CompletableFuture.completedFuture(transformedRequest())
        );

        var failure = Assertions.assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> request.get(Duration.ofSeconds(5))
        );
        Assertions.assertEquals("packet consumer creation failed", failure.getCause().getMessage());
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.PREPARED_REQUEST) == 0);
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.ATTEMPT_PAYLOAD) == 0);
        Assertions.assertEquals(1, ownershipMetrics.maxHandles(ResourceOwnership.Type.PREPARED_REQUEST));
        Assertions.assertEquals(1, ownershipMetrics.maxHandles(ResourceOwnership.Type.ATTEMPT_PAYLOAD));

        var probe = permits.acquire(requestId("probe", 0), 1).toCompletableFuture().get(2, TimeUnit.SECONDS);
        probe.close();
        closeActor(context);
    }

    @Test
    void visitorCreationFailureSettlesPreparationWhenPreparedCleanupAlsoFails() throws Exception {
        var ownershipMetrics = new RecordingOwnershipMetrics();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> new ImmediatePacketConsumer(
                context.getReplayerRequestKey().getReplayerRequestIndex()
            ),
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            org.opensearch.migrations.replay.lifecycle.ConnectionActor.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ownershipMetrics
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("visitor-factory-failure", 0);
        var transformed = transformedRequest();
        var producer = transformed.transformedOutput;
        producer.retain();
        var visitorFailure = new IllegalStateException("visitor factory failed");
        var now = Instant.now();

        try {
            var request = orchestrator.scheduleRequestLifecycle(
                context.getReplayerRequestKey(),
                context,
                now.minusSeconds(1),
                now.minusMillis(1),
                now,
                permits,
                () -> TextTrackedFuture.completedFuture(transformed, () -> "prepared request"),
                ignored -> {
                    throw visitorFailure;
                },
                status -> status.getClass().getSimpleName()
            );

            var failure = Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> request.get(Duration.ofSeconds(5))
            );
            Assertions.assertSame(visitorFailure, failure.getCause());
            Assertions.assertEquals(1, visitorFailure.getSuppressed().length);
            Assertions.assertEquals(
                "prepared request has shared ownership; refCnt=2",
                visitorFailure.getSuppressed()[0].getMessage()
            );

            var probe = permits.acquire(requestId("probe", 0), 1)
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
            probe.close();
        } finally {
            if (producer.refCnt() == 2) {
                producer.release();
            }
            if (producer.refCnt() == 1) {
                producer.close();
            }
        }

        Assertions.assertEquals(0, ownershipMetrics.handles(ResourceOwnership.Type.PREPARED_REQUEST));
        closeActor(context);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void synchronousPacketConsumerFailureReleasesOwnedRequestResources(boolean returnsNull) throws Exception {
        var ownershipMetrics = new RecordingOwnershipMetrics();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> new IPacketFinalizingConsumer<>() {
                @Override
                public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
                    if (returnsNull) {
                        return null;
                    }
                    throw new IllegalStateException("packet write rejected");
                }

                @Override
                public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
                    throw new AssertionError("synchronous packet failure must not finalize");
                }
            },
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            org.opensearch.migrations.replay.lifecycle.ConnectionActor.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ownershipMetrics
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext(
            returnsNull ? "sender-returned-null" : "sender-threw",
            0
        );
        var transformed = transformedRequest();
        var now = Instant.now();
        var request = orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            context,
            now.minusSeconds(1),
            now.minusMillis(1),
            now,
            permits,
            () -> TextTrackedFuture.completedFuture(transformed, () -> "prepared request"),
            transformedResult -> (requestBytes, response, sendFailure) -> {
                var failure = sendFailure == null
                    ? new AssertionError("synchronous packet failure was not propagated")
                    : sendFailure;
                return TextTrackedFuture.failedFuture(
                    failure,
                    () -> "propagating the synchronous packet failure"
                );
            },
            status -> status.getClass().getSimpleName()
        );

        var failure = Assertions.assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> request.get(Duration.ofSeconds(5))
        );
        Assertions.assertEquals(
            returnsNull ? "packet consumer returned null" : "packet write rejected",
            failure.getCause().getMessage()
        );
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.PREPARED_REQUEST) == 0);
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.ATTEMPT_PAYLOAD) == 0);
        Assertions.assertEquals(0, transformed.transformedOutput.refCnt());
        Assertions.assertEquals(1, ownershipMetrics.maxHandles(ResourceOwnership.Type.ATTEMPT_PAYLOAD));

        var probe = permits.acquire(requestId("probe", 0), 1).toCompletableFuture().get(2, TimeUnit.SECONDS);
        probe.close();
        closeActor(context);
    }

    @Test
    void lateTransformationAfterCancellationIsTrackedAndReleased() throws Exception {
        var ownershipMetrics = new RecordingOwnershipMetrics();
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> new ImmediatePacketConsumer(
                context.getReplayerRequestKey().getReplayerRequestIndex()
            ),
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            org.opensearch.migrations.replay.lifecycle.ConnectionActor.Metrics.NOOP,
            TargetExchangeState.Metrics.NOOP,
            ownershipMetrics
        );
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("late-transformation", 0);
        var preparationStarted = new CompletableFuture<Void>();
        var transformation = new CompletableFuture<TransformedOutputAndResult<ByteBufListProducer>>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
        var now = Instant.now();
        var request = orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            context,
            now.minusSeconds(1),
            now.minusMillis(1),
            now,
            permits,
            () -> {
                preparationStarted.complete(null);
                return new TextTrackedFuture<>(transformation, "non-cancellable transformation");
            },
            transformed -> (requestBytes, response, failure) ->
                TextTrackedFuture.completedFuture(
                    new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                        RequestSenderOrchestrator.RetryDirective.DONE,
                        "unused"
                    ),
                    () -> "unused retry visitor"
                ),
            status -> status.getClass().getSimpleName()
        );

        preparationStarted.get(2, TimeUnit.SECONDS);
        orchestrator.abortActor(
            context.getChannelKeyContext(),
            0,
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("source reassigned")
        ).get(Duration.ofSeconds(2));

        var transformed = transformedRequest();
        transformation.complete(transformed);

        await(() -> ownershipMetrics.maxHandles(ResourceOwnership.Type.PREPARED_REQUEST) == 1);
        await(() -> ownershipMetrics.handles(ResourceOwnership.Type.PREPARED_REQUEST) == 0);
        Assertions.assertEquals(0, transformed.transformedOutput.refCnt());
        Assertions.assertTrue(request.future.isCompletedExceptionally());
    }

    @Test
    void filteredPreparationReleasesPermitWithoutOpeningTargetExchange() throws Exception {
        var permits = new AsyncPermitPool(1, Runnable::run);
        var context = rootContext.getTestConnectionRequestContext("filtered", 0);
        var result = schedule(
            context,
            permits,
            CompletableFuture.completedFuture(
                new TransformedOutputAndResult<>(null, HttpRequestTransformationStatus.skipped())
            )
        ).get(Duration.ofSeconds(5));

        Assertions.assertEquals("Skipped", result);
        Assertions.assertEquals(0, targetExchanges.get());
        var probe = permits.acquire(requestId("probe", 0), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        probe.close();
        closeActor(context);
    }

    @Test
    void sessionTerminationWaitsForTransactionsAndSourceAcknowledgement() throws Exception {
        var sourceAcknowledgement = new CompletableFuture<Void>();
        var acknowledgementStarted = new CompletableFuture<ConnectionSessionKey>();
        sessionAcknowledger.set(sessionKey -> {
            acknowledgementStarted.complete(sessionKey);
            return sourceAcknowledgement;
        });
        var context = rootContext.getTestConnectionRequestContext("termination", 0);
        var runtime = orchestrator.transactionRuntime(
            context.getReplayerRequestKey(),
            context.getChannelKeyContext()
        );
        var transaction = new CompletableFuture<Void>();
        runtime.register(transaction).toCompletableFuture().get(5, TimeUnit.SECONDS);

        var close = orchestrator.scheduleActorClose(
            context.getChannelKeyContext(),
            0,
            Instant.now()
        );

        Assertions.assertFalse(close.future.isDone());
        Assertions.assertFalse(acknowledgementStarted.isDone());

        transaction.complete(null);
        var acknowledgedSession = acknowledgementStarted.get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(runtime.requestId().session(), acknowledgedSession);
        Assertions.assertFalse(close.future.isDone());

        sourceAcknowledgement.complete(null);
        close.get(Duration.ofSeconds(5));
    }

    @Test
    void failedTransactionPreventsSourceAcknowledgement() throws Exception {
        var acknowledgementStarted = new CompletableFuture<ConnectionSessionKey>();
        sessionAcknowledger.set(sessionKey -> {
            acknowledgementStarted.complete(sessionKey);
            return CompletableFuture.completedFuture(null);
        });
        var context = rootContext.getTestConnectionRequestContext("failed-transaction", 0);
        var runtime = orchestrator.transactionRuntime(
            context.getReplayerRequestKey(),
            context.getChannelKeyContext()
        );
        var transaction = new CompletableFuture<Void>();
        runtime.register(transaction).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var close = orchestrator.scheduleActorClose(
            context.getChannelKeyContext(),
            0,
            Instant.now()
        );

        transaction.completeExceptionally(new IllegalStateException("disposition failed"));

        var error = Assertions.assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> close.get(Duration.ofSeconds(5))
        );
        Assertions.assertEquals("disposition failed", error.getCause().getMessage());
        Assertions.assertFalse(acknowledgementStarted.isDone());
    }

    @Test
    void globalRunwayLossReachesAnActorCreatedDuringShutdown() throws Exception {
        orchestrator.observeAllRunwaysLost(ReplayTransaction.RunwayLossReason.SHUTDOWN)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        var context = rootContext.getTestConnectionRequestContext("late-shutdown-actor", 0);
        var runtime = orchestrator.transactionRuntime(
            context.getReplayerRequestKey(),
            context.getChannelKeyContext()
        );
        var runwayObserved = new CompletableFuture<ReplayTransaction.RunwayLossReason>();
        var transaction = new ReplayTransaction<String>(
            runtime.requestId(),
            runtime.mailbox(),
            (id, source, target) -> CompletableFuture.completedFuture(
                new org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome.Durable("unused")
            ),
            new ReplayDispositionPolicy(),
            new RecordDispositionLedger(Runnable::run),
            List.of(),
            List.of(),
            new ReplayTransaction.Metrics() {
                @Override
                public void phaseChanged(ReplayTransaction.Phase phase, int delta) {}

                @Override
                public void runwayStateChanged(ReplayTransaction.RunwayState state, int delta) {}

                @Override
                public void runwayLost(ReplayTransaction.RunwayLossReason reason) {
                    runwayObserved.complete(reason);
                }

                @Override
                public void terminalOutcome(ReplayTransaction.TerminalOutcome outcome) {}

                @Override
                public void disposition(RecordDisposition disposition) {}
            }
        );

        runtime.register(transaction).toCompletableFuture().get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(
            ReplayTransaction.RunwayLossReason.SHUTDOWN,
            runwayObserved.get(5, TimeUnit.SECONDS)
        );

        transaction.settleSource(new SourceOutcome.Interrupted("shutdown"))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        transaction.settleTarget(new TargetOutcome.Cancelled<>(new CancellationException("shutdown")))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        closeActor(context);
    }

    @Test
    void failedSourceAcknowledgementFailsTheSessionGate() {
        sessionAcknowledger.set(sessionKey ->
            CompletableFuture.failedFuture(new IllegalStateException("source acknowledgement failed"))
        );
        var context = rootContext.getTestConnectionRequestContext("failed-acknowledgement", 0);

        var close = orchestrator.scheduleActorClose(
            context.getChannelKeyContext(),
            0,
            Instant.now()
        );

        var error = Assertions.assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> close.get(Duration.ofSeconds(5))
        );
        Assertions.assertEquals("source acknowledgement failed", error.getCause().getMessage());
    }

    @Test
    void absentActorStillProducesAnExplicitSourceAcknowledgement() throws Exception {
        var sourceAcknowledgement = new CompletableFuture<Void>();
        var acknowledgementStarted = new CompletableFuture<ConnectionSessionKey>();
        sessionAcknowledger.set(sessionKey -> {
            acknowledgementStarted.complete(sessionKey);
            return sourceAcknowledgement;
        });
        var context = rootContext.getTestConnectionRequestContext("no-actor", 0);

        var abort = orchestrator.abortActor(
            context.getChannelKeyContext(),
            0,
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("rebalance")
        );

        var acknowledgedSession = acknowledgementStarted.get(5, TimeUnit.SECONDS);
        Assertions.assertEquals("no-actor", acknowledgedSession.connection().connectionId());
        Assertions.assertFalse(abort.future.isDone());

        sourceAcknowledgement.complete(null);
        abort.get(Duration.ofSeconds(5));
    }

    private TrackedFuture<String, String> schedule(
        org.opensearch.migrations.replay.tracing.IReplayContexts.IReplayerHttpTransactionContext context,
        AsyncPermitPool permits,
        CompletableFuture<TransformedOutputAndResult<ByteBufListProducer>> preparation
    ) {
        var now = Instant.now();
        return orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            context,
            now.minusSeconds(1),
            now.minusMillis(1),
            now,
            permits,
            () -> new TextTrackedFuture<>(preparation, "test preparation"),
            transformed -> (request, response, failure) ->
                TextTrackedFuture.completedFuture(
                    new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                        RequestSenderOrchestrator.RetryDirective.DONE,
                        "sent"
                    ),
                    () -> "do not retry"
                ),
            status -> status.getClass().getSimpleName()
        );
    }

    private void closeActor(
        org.opensearch.migrations.replay.tracing.IReplayContexts.IReplayerHttpTransactionContext context
    ) throws Exception {
        orchestrator.scheduleActorClose(
            context.getChannelKeyContext(),
            0,
            Instant.now()
        ).get(Duration.ofSeconds(5));
    }

    private static TransformedOutputAndResult<ByteBufListProducer> transformedRequest() {
        var source = Unpooled.wrappedBuffer(new byte[] { 1 });
        var packets = new ByteBufList(source);
        source.release();
        return new TransformedOutputAndResult<>(
            ByteBufListProducer.of(packets),
            HttpRequestTransformationStatus.completed()
        );
    }

    private static ReplayRequestId requestId(String connectionId, int index) {
        return new ReplayRequestId(
            new ConnectionSessionKey(
                new SourceConnectionKey("test", connectionId),
                0,
                0
            ),
            index
        );
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        Assertions.assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    private static final class RecordingTargetExchangeMetrics implements TargetExchangeState.Metrics {
        private final List<TargetExchangeState.Phase> enteredPhases = new CopyOnWriteArrayList<>();
        private final AtomicReference<TargetExchangeState.Phase> activePhase = new AtomicReference<>();
        private final List<Boolean> ownerThreadCallbacks = new CopyOnWriteArrayList<>();

        @Override
        public void phaseChanged(TargetExchangeState.Phase phase, int delta) {
            ownerThreadCallbacks.add(Thread.currentThread().getName().startsWith("actor-lifecycle-test"));
            if (delta > 0) {
                Assertions.assertTrue(activePhase.compareAndSet(null, phase));
                enteredPhases.add(phase);
            } else {
                Assertions.assertTrue(activePhase.compareAndSet(phase, null));
            }
        }

        @Override
        public void channelStateChanged(TargetExchangeState.ChannelState state, int delta) {}

        void awaitPhase(TargetExchangeState.Phase phase) throws InterruptedException {
            await(() -> activePhase.get() == phase);
        }

        void awaitNoActivePhase() throws InterruptedException {
            await(() -> activePhase.get() == null);
        }

        void awaitEnteredPhaseCount(int count) throws InterruptedException {
            await(() -> enteredPhases.size() == count);
        }

        boolean onlyOwnerThreadCallbacks() {
            return !ownerThreadCallbacks.isEmpty() && ownerThreadCallbacks.stream().allMatch(Boolean::booleanValue);
        }

        List<TargetExchangeState.Phase> enteredPhases() {
            return List.copyOf(enteredPhases);
        }
    }

    private static final class RecordingOwnershipMetrics implements ResourceOwnership.Metrics {
        private final EnumMap<ResourceOwnership.Type, Integer> handles =
            new EnumMap<>(ResourceOwnership.Type.class);
        private final EnumMap<ResourceOwnership.Type, Integer> maxHandles =
            new EnumMap<>(ResourceOwnership.Type.class);

        @Override
        public synchronized void ownershipChanged(
            ResourceOwnership.Type type,
            int handleDelta,
            int bufferDelta,
            long byteDelta
        ) {
            var current = handles.merge(type, handleDelta, Integer::sum);
            maxHandles.merge(type, current, Math::max);
        }

        private synchronized int handles(ResourceOwnership.Type type) {
            return handles.getOrDefault(type, 0);
        }

        private synchronized int maxHandles(ResourceOwnership.Type type) {
            return maxHandles.getOrDefault(type, 0);
        }
    }

    private final class ImmediatePacketConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {
        private final int requestIndex;

        private ImmediatePacketConsumer(int requestIndex) {
            this.requestIndex = requestIndex;
        }

        @Override
        public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
            nextRequestPacket.release();
            targetExecutionOrder.add(requestIndex);
            targetExchanges.incrementAndGet();
            return TextTrackedFuture.completedFuture(null, () -> "packet consumed");
        }

        @Override
        public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
            return TextTrackedFuture.completedFuture(
                new AggregatedRawResponse(null, 0, java.time.Duration.ZERO, null, null),
                () -> "response completed"
            );
        }
    }
}
