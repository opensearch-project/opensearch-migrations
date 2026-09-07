package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ConnectionActor;
import org.opensearch.migrations.replay.lifecycle.RecordDisposition;
import org.opensearch.migrations.replay.lifecycle.RecordDispositionLedger;
import org.opensearch.migrations.replay.lifecycle.ReplayDispositionPolicy;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepeatedGenerationLifecycleTest extends InstrumentationTest {
    private static final int PERMIT_CAPACITY = 2;

    private final LifecycleMetrics metrics = new LifecycleMetrics();
    private ClientConnectionPool connectionPool;
    private RequestSenderOrchestrator orchestrator;
    private AsyncPermitPool permitPool;
    private RecordDispositionLedger ledger;

    @BeforeEach
    void setUpLifecycle() {
        connectionPool = new ClientConnectionPool(
            (eventLoop, context) -> {
                throw new AssertionError("The test packet consumer does not need a Netty channel");
            },
            "generation-turnover-test",
            1
        );
        orchestrator = new RequestSenderOrchestrator(
            connectionPool,
            (session, context) -> new ImmediatePacketConsumer(),
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            metrics,
            metrics,
            metrics
        );
        permitPool = new AsyncPermitPool(PERMIT_CAPACITY, Runnable::run, metrics);
        ledger = new RecordDispositionLedger(Runnable::run);
    }

    @AfterEach
    void closePool() throws Exception {
        connectionPool.shutdownNow().get(5, TimeUnit.SECONDS);
    }

    @Test
    void consecutiveGenerationsReturnEveryLifecycleRegistryAndGaugeToBaseline() throws Exception {
        runCompletedGeneration(1);
        assertGenerationBaseline(1);

        runCompletedGeneration(2);
        assertGenerationBaseline(2);

        Assertions.assertEquals(2, metrics.committedTransactions);
        Assertions.assertEquals(2, metrics.commitDispositions);
        Assertions.assertTrue(metrics.maximumOwnedHandles > 0);
        Assertions.assertTrue(metrics.maximumQueuedCommands > 0);
    }

    private void runCompletedGeneration(int generation) throws Exception {
        var generated = newGeneratedContext("reused-connection", generation);
        var context = generated.context();
        var runtime = orchestrator.transactionRuntime(
            context.getReplayerRequestKey(),
            context.getChannelKeyContext()
        );
        var partition = new SourcePartitionKey("topic", 0, generation);
        var record = new GenerationRecord(new KafkaRecordId("topic", 0, generation, generation));
        var resourceCloses = new AtomicInteger();
        var transaction = new ReplayTransaction<String>(
            runtime.requestId(),
            runtime.mailbox(),
            (requestId, source, target) -> CompletableFuture.completedFuture(
                new EvidenceOutcome.Durable("generation-" + generation)
            ),
            new ReplayDispositionPolicy(),
            ledger,
            List.of(record.id()),
            List.of(resourceCloses::incrementAndGet),
            metrics
        );

        ledger.onAssigned(List.of(partition));
        ledger.register(record, transaction.ledgerOwner()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        runtime.register(transaction).toCompletableFuture().get(5, TimeUnit.SECONDS);

        schedule(context).get(Duration.ofSeconds(5));
        transaction.settleSource(new SourceOutcome.Complete()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        transaction.settleTarget(new TargetOutcome.Succeeded<>("sent"))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        var outcome = transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Assertions.assertInstanceOf(RecordDisposition.Commit.class, outcome.disposition());

        orchestrator.scheduleActorClose(
            context.getChannelKeyContext(),
            0,
            Instant.now()
        ).get(Duration.ofSeconds(5));
        ledger.onRevoked(List.of(partition));
        ledger.onRetired(List.of(partition));

        Assertions.assertEquals(1, record.contextCloses.get());
        Assertions.assertEquals(1, record.commits.get());
        Assertions.assertEquals(0, record.releasesWithoutCommit.get());
        Assertions.assertEquals(1, resourceCloses.get());

        context.close();
        generated.key().getTrafficStreamsContext().close();
        rootContext.channelContextManager.releaseContextFor(context.getChannelKeyContext());
    }

    private void assertGenerationBaseline(int generation) throws Exception {
        await(metrics::isAtBaseline);
        await(() -> orchestrator.describeUnterminatedSessions().isEmpty());

        var snapshot = ledger.stateSnapshot().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(0, snapshot.unresolved());
        Assertions.assertEquals(0, snapshot.pending());
        Assertions.assertEquals(0, snapshot.resolved());
        Assertions.assertEquals(0, snapshot.resolvedIndexEntries());
        Assertions.assertEquals(0, snapshot.failed());
        Assertions.assertEquals(0, snapshot.runwayGenerations());
        Assertions.assertEquals(0, snapshot.retiringGenerations());
        Assertions.assertEquals(
            1,
            snapshot.retiredPartitionWatermarks(),
            "retired history must remain bounded by source partition after generation " + generation
        );

        var probe = permitPool.acquire(
            new org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId(
                new org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey(
                    new org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey(
                        "testNodeId",
                        "baseline-probe"
                    ),
                    0,
                    generation
                ),
                0
            ),
            PERMIT_CAPACITY
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        probe.close();
        await(metrics::isAtBaseline);
    }

    private TrackedFuture<String, String> schedule(
        IReplayContexts.IReplayerHttpTransactionContext context
    ) {
        var now = Instant.now();
        return orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            context,
            now.minusSeconds(1),
            now.minusMillis(1),
            now,
            permitPool,
            () -> TextTrackedFuture.completedFuture(transformedRequest(), () -> "prepared request"),
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

    private GeneratedContext newGeneratedContext(String connectionId, int generation) {
        var key = new GenerationTrafficStreamKey(connectionId, generation);
        var requestKey = new UniqueReplayerRequestKey(key, 0, 0);
        return new GeneratedContext(
            key,
            key.getTrafficStreamsContext().createHttpTransactionContext(requestKey, Instant.EPOCH)
        );
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

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        Assertions.assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    private record GeneratedContext(
        GenerationTrafficStreamKey key,
        IReplayContexts.IReplayerHttpTransactionContext context
    ) {}

    private final class GenerationTrafficStreamKey implements ITrafficStreamKey {
        private final String connectionId;
        private final int generation;
        private final IReplayContexts.ITrafficStreamsLifecycleContext trafficStreamsContext;

        private GenerationTrafficStreamKey(String connectionId, int generation) {
            this.connectionId = connectionId;
            this.generation = generation;
            trafficStreamsContext = rootContext.createTrafficStreamContextForTest(this);
        }

        @Override
        public String getNodeId() {
            return "testNodeId";
        }

        @Override
        public String getConnectionId() {
            return connectionId;
        }

        @Override
        public int getSourceGeneration() {
            return generation;
        }

        @Override
        public int getTrafficStreamIndex() {
            return 0;
        }

        @Override
        public IReplayContexts.ITrafficStreamsLifecycleContext getTrafficStreamsContext() {
            return trafficStreamsContext;
        }
    }

    private static final class GenerationRecord implements RecordDispositionLedger.RecordHandle {
        private final KafkaRecordId id;
        private final AtomicInteger contextCloses = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger releasesWithoutCommit = new AtomicInteger();

        private GenerationRecord(KafkaRecordId id) {
            this.id = id;
        }

        @Override
        public KafkaRecordId id() {
            return id;
        }

        @Override
        public SourcePartitionKey sourcePartition() {
            return new SourcePartitionKey(id.topic(), id.partition(), id.sourceGeneration());
        }

        @Override
        public void closeContext() {
            contextCloses.incrementAndGet();
        }

        @Override
        public void releaseWithoutCommit() {
            releasesWithoutCommit.incrementAndGet();
        }

        @Override
        public CompletableFuture<Void> commit() {
            commits.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class LifecycleMetrics implements
        AsyncPermitPool.Metrics,
        ConnectionActor.Metrics,
        TargetExchangeState.Metrics,
        ReplayTransaction.Metrics,
        ResourceOwnership.Metrics {

        private final EnumMap<ConnectionActor.HeadWaitReason, Integer> headWaits =
            new EnumMap<>(ConnectionActor.HeadWaitReason.class);
        private final EnumMap<ConnectionActor.AbortChild, Integer> abortChildren =
            new EnumMap<>(ConnectionActor.AbortChild.class);
        private final EnumMap<TargetExchangeState.Phase, Integer> targetPhases =
            new EnumMap<>(TargetExchangeState.Phase.class);
        private final EnumMap<TargetExchangeState.ChannelState, Integer> channelStates =
            new EnumMap<>(TargetExchangeState.ChannelState.class);
        private final EnumMap<ReplayTransaction.Phase, Integer> transactionPhases =
            new EnumMap<>(ReplayTransaction.Phase.class);
        private final EnumMap<ReplayTransaction.RunwayState, Integer> runwayStates =
            new EnumMap<>(ReplayTransaction.RunwayState.class);
        private final EnumMap<ResourceOwnership.Type, Integer> ownershipHandles =
            new EnumMap<>(ResourceOwnership.Type.class);
        private int availablePermits;
        private int queuedPermits;
        private int queuedCommands;
        private int ownedBuffers;
        private long ownedBytes;
        private int committedTransactions;
        private int commitDispositions;
        private int maximumOwnedHandles;
        private int maximumQueuedCommands;

        @Override
        public synchronized void availableChanged(int delta) {
            availablePermits += delta;
        }

        @Override
        public synchronized void queuedChanged(int delta) {
            queuedPermits += delta;
        }

        @Override
        public void permitHeld(Duration duration) {}

        @Override
        public void cancelled(int count) {}

        @Override
        public synchronized void queuedCommandsChanged(int delta) {
            queuedCommands += delta;
            maximumQueuedCommands = Math.max(maximumQueuedCommands, queuedCommands);
        }

        @Override
        public synchronized void headWaitChanged(ConnectionActor.HeadWaitReason reason, int delta) {
            headWaits.merge(reason, delta, Integer::sum);
        }

        @Override
        public void activeDuration(Duration duration) {}

        @Override
        public void abortDuration(Duration duration) {}

        @Override
        public synchronized void pendingAbortChildChanged(ConnectionActor.AbortChild child, int delta) {
            abortChildren.merge(child, delta, Integer::sum);
        }

        @Override
        public synchronized void phaseChanged(TargetExchangeState.Phase phase, int delta) {
            targetPhases.merge(phase, delta, Integer::sum);
        }

        @Override
        public synchronized void channelStateChanged(TargetExchangeState.ChannelState state, int delta) {
            channelStates.merge(state, delta, Integer::sum);
        }

        @Override
        public synchronized void phaseChanged(ReplayTransaction.Phase phase, int delta) {
            transactionPhases.merge(phase, delta, Integer::sum);
        }

        @Override
        public synchronized void runwayStateChanged(ReplayTransaction.RunwayState state, int delta) {
            runwayStates.merge(state, delta, Integer::sum);
        }

        @Override
        public void runwayLost(ReplayTransaction.RunwayLossReason reason) {}

        @Override
        public synchronized void terminalOutcome(ReplayTransaction.TerminalOutcome outcome) {
            if (outcome == ReplayTransaction.TerminalOutcome.COMMITTED) {
                committedTransactions++;
            }
        }

        @Override
        public synchronized void disposition(RecordDisposition disposition) {
            if (disposition instanceof RecordDisposition.Commit) {
                commitDispositions++;
            }
        }

        @Override
        public synchronized void ownershipChanged(
            ResourceOwnership.Type type,
            int handleDelta,
            int bufferDelta,
            long byteDelta
        ) {
            var handles = ownershipHandles.merge(type, handleDelta, Integer::sum);
            maximumOwnedHandles = Math.max(maximumOwnedHandles, handles);
            ownedBuffers += bufferDelta;
            ownedBytes += byteDelta;
        }

        private synchronized boolean isAtBaseline() {
            return availablePermits == PERMIT_CAPACITY
                && queuedPermits == 0
                && queuedCommands == 0
                && sum(headWaits) == 0
                && sum(abortChildren) == 0
                && sum(targetPhases) == 0
                && sum(channelStates) == 0
                && sum(transactionPhases) == 0
                && sum(runwayStates) == 0
                && sum(ownershipHandles) == 0
                && ownedBuffers == 0
                && ownedBytes == 0;
        }

        private static <E extends Enum<E>> int sum(EnumMap<E, Integer> values) {
            return values.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static final class ImmediatePacketConsumer
        implements IPacketFinalizingConsumer<AggregatedRawResponse> {

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
    }
}
