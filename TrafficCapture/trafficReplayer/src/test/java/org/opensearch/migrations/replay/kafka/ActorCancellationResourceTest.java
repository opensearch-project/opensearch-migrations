package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.http.retries.NoRetryEvaluatorFactory;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.opensearch.migrations.replay.ActorRequestTestUtils.schedulePreparedRequest;

class ActorCancellationResourceTest extends InstrumentationTest {
    private static final int PERMIT_COUNT = 5;

    private final AtomicBoolean targetStarted = new AtomicBoolean();
    private ClientConnectionPool pool;
    private RequestSenderOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        pool = new ClientConnectionPool(
            (eventLoop, context) -> {
                throw new AssertionError("Far-future requests must not open a channel");
            },
            "actor-cancellation-test",
            1
        );
        orchestrator = new RequestSenderOrchestrator(
            pool,
            (session, context) -> {
                targetStarted.set(true);
                return null;
            },
            RequestSenderOrchestrator.noSourceTerminationObligations()
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdownNow().get(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(10)
    void abortReleasesEveryPermitHeldByFarFuturePreparation() throws Exception {
        var permits = new AsyncPermitPool(PERMIT_COUNT, Runnable::run);
        List<TrackedFuture<String, AggregatedRawResponse>> requests = new ArrayList<>();
        var sendTime = Instant.now().plusSeconds(60);

        for (int i = 0; i < PERMIT_COUNT; i++) {
            var context = rootContext.getTestConnectionRequestContext("cancelled", i);
            var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[] { 1 }));
            requests.add(
                schedulePreparedRequest(
                    orchestrator,
                    context,
                    sendTime,
                    Duration.ZERO,
                    ByteBufListProducer.of(packets),
                    new NoRetryEvaluatorFactory.NoRetryVisitor(),
                    permits
                )
            );
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
