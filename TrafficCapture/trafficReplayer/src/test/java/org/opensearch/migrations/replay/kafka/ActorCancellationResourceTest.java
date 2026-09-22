package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.ActorRequestTestUtils;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.http.retries.NoRetryEvaluatorFactory;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.opensearch.migrations.replay.ActorRequestTestUtils.schedulePreparedRequest;

class ActorCancellationResourceTest extends InstrumentationTest {
    private static final int PERMIT_COUNT = 5;

    private final AtomicBoolean targetStarted = new AtomicBoolean();
    private final AtomicReference<Error> fatalFailure = new AtomicReference<>();
    private ClientConnectionPool pool;
    private ExecutorService permitOwnerExecutor;
    private RequestSenderOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        permitOwnerExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "permit-owner-test")
        );
        pool = new ClientConnectionPool(
            (eventLoop, context) -> {
                throw new AssertionError("Far-future requests must not open a channel");
            },
            "actor-cancellation-test",
            1
        );
        orchestrator = new RequestSenderOrchestrator(
            pool,
            (session, context, firstTargetWriteSubmitted) -> {
                targetStarted.set(true);
                return null;
            },
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            new TargetConnectionOwner.RequestLifecycleSink() {
                @Override
                public java.util.concurrent.CompletionStage<Void> connectionRequestFinished(
                    PartitionGenerationId partitionGenerationId,
                    ReplayRequestId requestId
                ) {
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }

                @Override
                public java.util.concurrent.CompletionStage<Void> requestProcessingFinished(
                    PartitionGenerationId partitionGenerationId,
                    ReplayRequestId requestId
                ) {
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
            },
            fatalFailure::set
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            pool.shutdownNow().get(5, TimeUnit.SECONDS);
        } finally {
            try {
                permitOwnerExecutor.shutdownNow();
            } finally {
                Assertions.assertTrue(permitOwnerExecutor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        Assertions.assertNull(fatalFailure.get(), "unexpected process-fatal replay failure");
    }

    @Test
    @Timeout(10)
    void abortReleasesEveryPermitHeldByFarFuturePreparation() throws Exception {
        var permits = new AsyncPermitPool(PERMIT_COUNT, permitOwnerExecutor);
        List<TrackedFuture<String, AggregatedRawResponse>> requests = new ArrayList<>();
        List<ActorRequestTestUtils.RequestProcessingFixture> processingFixtures = new ArrayList<>();
        var sendTime = Instant.now().plusSeconds(60);
        var generation = new PartitionGenerationId(
            new TopicPartition("actor-cancellation-resource-test", 0),
            0
        );

        for (int i = 0; i < PERMIT_COUNT; i++) {
            var context = rootContext.getTestConnectionRequestContext("cancelled", i);
            var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[] { 1 }));
            var processing = new ActorRequestTestUtils.RequestProcessingFixture();
            requests.add(
                schedulePreparedRequest(
                    orchestrator,
                    generation,
                    context,
                    sendTime,
                    Duration.ZERO,
                    ByteBufListProducer.of(packets),
                    new NoRetryEvaluatorFactory.NoRetryVisitor(),
                    permits,
                    processing.registration()
                )
            );
            processingFixtures.add(processing);
        }

        var channelContext = rootContext.getTestConnectionRequestContext("cancelled", 0)
            .getChannelKeyContext();
        pool.getCachedSession(channelContext, 0).eventLoop.submit(() -> {}).sync();

        orchestrator.abortActor(
            channelContext,
            0,
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("source reassigned")
        ).get(Duration.ofSeconds(5));

        Assertions.assertFalse(targetStarted.get());
        requests.forEach(request -> Assertions.assertTrue(request.future.isCompletedExceptionally()));
        processingFixtures.forEach(processing ->
            Assertions.assertDoesNotThrow(
                () -> processing.lifecycleHandled().toCompletableFuture().get(2, TimeUnit.SECONDS)
            )
        );

        var probes = new ArrayList<AsyncPermitPool.Permit>();
        for (int i = 0; i < PERMIT_COUNT; i++) {
            probes.add(
                permits.acquire(requestId("probe", i), 1)
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS)
            );
        }
        probes.forEach(AsyncPermitPool.Permit::close);
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
}
