package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestSenderOrchestratorLifecycleTest extends InstrumentationTest {
    private final List<Integer> targetExecutionOrder = new CopyOnWriteArrayList<>();
    private final AtomicInteger targetExchanges = new AtomicInteger();
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
            )
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
