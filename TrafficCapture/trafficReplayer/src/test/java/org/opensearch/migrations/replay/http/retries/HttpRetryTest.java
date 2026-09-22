package org.opensearch.migrations.replay.http.retries;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.ActorRequestTestUtils.RequestProcessingFixture;
import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.opensearch.migrations.replay.ActorRequestTestUtils.schedulePreparedRequest;
import static org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumerTest.REGULAR_RESPONSE_TIMEOUT;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
public class HttpRetryTest {
    private static final PartitionGenerationId TEST_GENERATION =
        new PartitionGenerationId(new TopicPartition("http-retry-test", 2), 7);

    private record ScheduledRequest(
        TrackedFuture<String, TransformedTargetRequestAndResponseList> completion,
        RequestSenderOrchestrator orchestrator,
        IReplayContexts.IReplayerHttpTransactionContext context,
        RequestProcessingFixture processing,
        List<Error> fatalFailures
    ) {}

    private ByteBufList makeRequest() {
        return new ByteBufList(Unpooled.wrappedBuffer(TestHttpServerContext.getRequestStringForSimpleGet("/")
            .getBytes(StandardCharsets.UTF_8)));
    }

    public static SimpleHttpResponse makeTransientErrorResponse(Duration responseWaitTime) {
        try {
            Thread.sleep(responseWaitTime.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Lombok.sneakyThrow(e);
        }
        return new SimpleHttpResponse(Map.of(), null, "Not Found", 404);
    }

    private TrackedFuture<String, TransformedTargetRequestAndResponseList>
    scheduleSingleRequest(URI testServerUri, TestContext rootContext) {
        var clientConnectionPool = TrafficReplayerTopLevel.makeNettyPacketConsumerConnectionPool(
            testServerUri,
            false,
            1,
            "targetConnectionPool for testTransientRequestFailuresAreRetried"
        );
        var scheduled = scheduleSingleRequest(clientConnectionPool, rootContext);
        return scheduled.completion().thenCompose(
            result -> {
                if (!scheduled.processing().completeTupleDurable()) {
                    return TextTrackedFuture.failedFuture(
                        new IllegalStateException("request processing was already settled"),
                        () -> "settle retry test request processing"
                    );
                }
                return new TextTrackedFuture<>(
                    scheduled.processing().lifecycleHandled().toCompletableFuture(),
                    () -> "wait for retry test request processing lifecycle"
                ).thenApply(
                    ignored -> result,
                    () -> "preserve the retry result after request processing finishes"
                );
            },
            () -> "settle retry test request processing after target completion"
        ).thenCompose(
            result -> scheduled.orchestrator().scheduleActorClose(
                scheduled.context().getChannelKeyContext(),
                0,
                TEST_GENERATION,
                Instant.now()
            ).thenApply(ignored -> result, () -> "preserve the retry result after closing its connection actor"),
            () -> "close the connection actor after retry processing finishes"
        ).thenCompose(
            result -> new TextTrackedFuture<>(
                scheduled.orchestrator().shutdownActors(
                    new CancellationException("retry test cleanup")
                ).toCompletableFuture(),
                () -> "wait for retry test replay actors to stop"
            ).thenApply(ignored -> result, () -> "preserve the retry result after stopping replay actors"),
            () -> "stop replay actors after the connection actor reaches its final state"
        ).thenCompose(
            result -> new TextTrackedFuture<>(
                clientConnectionPool.shutdownNow(),
                () -> "wait for the retry test connection pool to stop"
            ).thenApply(ignored -> result, () -> "preserve the retry result after stopping Netty"),
            () -> "stop Netty only after the connection actor reaches its final state"
        ).whenComplete(
            (ignored, failure) -> {
                scheduled.context().close();
                if (failure == null) {
                    assertNoFatalFailures(scheduled.fatalFailures());
                } else {
                    scheduled.fatalFailures().forEach(failure::addSuppressed);
                }
            },
            () -> "close the retry test request context"
        );
    }

    private ScheduledRequest
    scheduleSingleRequest(ClientConnectionPool clientConnectionPool, TestContext rootContext) {
        var retryFactory = new RetryCollectingVisitorFactory(new DefaultRetry());
        var fatalFailures = new CopyOnWriteArrayList<Error>();
        var senderOrchestrator = new RequestSenderOrchestrator(
            clientConnectionPool,
            (replaySession, ctx, firstTargetWriteSubmitted) -> new NettyPacketToHttpConsumer(
                replaySession,
                ctx,
                REGULAR_RESPONSE_TIMEOUT,
                firstTargetWriteSubmitted
            ),
            RequestSenderOrchestrator.noSourceTerminationObligations(),
            acceptingLifecycleSink(),
            fatalFailures::add
        );
        var baseTime = Instant.now();
        var requestContext = rootContext.getTestConnectionRequestContext(0);
        var startTimeForThisRequest = baseTime.plus(Duration.ofMillis(10));
        var sourceRequestPackets = makeRequest();
        var sourceResponseBytes = RetryTestUtils.makeSlashResponse(200).getBytes(StandardCharsets.UTF_8);
        var sourceRequestProducer = ByteBufListProducer.of(sourceRequestPackets);
        var retryVisitor = retryFactory.getRetryCheckVisitor(
            new TransformedOutputAndResult<>(sourceRequestProducer, HttpRequestTransformationStatus.skipped()),
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceResponseBytes),
                () -> "static rrp"));
        var processing = new RequestProcessingFixture();
        log.info("Scheduling item to run at " + startTimeForThisRequest);
        return new ScheduledRequest(
            schedulePreparedRequest(
                senderOrchestrator,
                TEST_GENERATION,
                requestContext,
                startTimeForThisRequest,
                Duration.ofMillis(1),
                sourceRequestProducer,
                retryVisitor,
                processing.registration()
            ),
            senderOrchestrator,
            requestContext,
            processing,
            fatalFailures
        );
    }

    private TransformedTargetRequestAndResponseList
    runServerAndGetResponse(TestContext rootContext, int numFailuresBeforeSuccess) throws Exception
    {
        var requestsReceivedCounter = new AtomicInteger();
        try (var httpServer = SimpleHttpServer.makeServer(false,
            r -> requestsReceivedCounter.incrementAndGet() > numFailuresBeforeSuccess
                    ? TestHttpServerContext.makeResponse(r, Duration.ofMillis(100))
                    : makeTransientErrorResponse(Duration.ofMillis(100))))
        {
            var responseFuture = scheduleSingleRequest(httpServer.localhostEndpoint(), rootContext);
            return Assertions.assertDoesNotThrow(() -> responseFuture.get());
        }
    }

    @Test
    public void testTransientRequestFailuresAreRetriedAndCanSucceed() throws Exception {
        try (var rootContext = TestContext.withAllTracking()) {
            var response = runServerAndGetResponse(rootContext, DefaultRetry.MAX_RETRIES - 1);
            Assertions.assertNotNull(response.responses());
            Assertions.assertFalse(response.responses().isEmpty());
            Assertions.assertEquals(DefaultRetry.MAX_RETRIES, response.responses().size());
            Assertions.assertEquals(200,
                response.responses().get(DefaultRetry.MAX_RETRIES - 1).getRawResponse().status().code());
            Assertions.assertTrue(response.responses().stream()
                .limit(DefaultRetry.MAX_RETRIES - 1)
                .map(r -> r.getRawResponse().status().code())
                .allMatch(c -> 404 == c));
        }
    }

    @Test
    public void testPersistentRequestFailuresAreRetriedThenFailed() throws Exception {
        try (var rootContext = TestContext.withAllTracking()) {
            var response = runServerAndGetResponse(rootContext, DefaultRetry.MAX_RETRIES + 1);
            Assertions.assertNotNull(response.responses());
            Assertions.assertFalse(response.responses().isEmpty());
            Assertions.assertEquals(DefaultRetry.MAX_RETRIES, response.responses().size());
            Assertions.assertTrue(response.responses().stream()
                .map(r -> r.getRawResponse().status().code())
                .allMatch(c -> 404 == c));

            var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
            Assertions.assertEquals(1, InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "requestConnectingCount"));
            Assertions.assertEquals(0, InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "requestConnectingExceptionCount"));
        }
    }

    @Test
    @Tag("longTest")
    @WrapWithNettyLeakDetection(disableLeakChecks = true) // code is forcibly terminated so leaks are expected
    public void testConnectionFailuresNeverGiveUp() throws Exception {
        URI serverUri;
        try (var server = SimpleHttpServer.makeServer(false, r -> makeTransientErrorResponse(Duration.ZERO))) {
            // do nothing but close it back down
            serverUri = server.localhostEndpoint();
        }

        // setup a deferred kill
        var executor = Executors.newSingleThreadExecutor(new DefaultThreadFactory("HttpRetryTest"));
        var clientConnectionPool = TrafficReplayerTopLevel.makeNettyPacketConsumerConnectionPool(
            serverUri,
            false,
            1,
            "targetConnectionPool for testTransientRequestFailuresAreRetried"
        );
        try (var rootContext = TestContext.withAllTracking()) {
            var scheduled = scheduleSingleRequest(clientConnectionPool, rootContext);
            var f = executor.submit(() -> scheduled.completion().get());

            // Wait until multiple connection attempts have been made instead of sleeping a fixed duration
            var deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
                if (InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "requestConnectingExceptionCount") > 1) {
                    break;
                }
                Thread.sleep(10);
            }
            scheduled.orchestrator().abortActor(
                scheduled.context().getChannelKeyContext(),
                0,
                AbortReason.SHUTDOWN,
                new CancellationException("test requested retry shutdown")
            ).get(Duration.ofSeconds(5));
            scheduled.orchestrator().shutdownActors(
                new CancellationException("retry test cleanup")
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var ccpShutdownFuture = clientConnectionPool.shutdownNow();

            var e = Assertions.assertThrows(Exception.class, f::get);
            var shutdownResult = ccpShutdownFuture.get();
            scheduled.context().close();
            log.atInfo().setCause(e).setMessage("exception: ").log();
            // doubly-nested ExecutionException.  Once for the get() call here and once for the work done in submit,
            // which wraps the scheduled request's future.  Which exception surfaces depends on where the
            // shutdown lands: IllegalStateException when a connection attempt was in flight,
            // CancellationException when a retry delay was pending and the event loop cancelled it.
            var rootFailure = e.getCause().getCause();
            Assertions.assertTrue(
                rootFailure instanceof IllegalStateException || rootFailure instanceof CancellationException,
                "expected the shutdown to fail the request, but got: " + rootFailure);
            executor.shutdown();

            // connection issues won't count as retries since they aren't related to resending the data.
            // The server won't have any idea which request wasn't able to send, so the reason for the retry is
            // because of a connection retry, not the request
            Assertions.assertEquals(0, checkHttpRetryConsistency(rootContext));
            var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
            Assertions.assertTrue(InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "requestConnectingCount") > 1);
            Assertions.assertTrue(InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "requestConnectingExceptionCount") > 1);
            Assertions.assertEquals(0, InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "nonRetryableConnectionFailures"));
            assertNoFatalFailures(scheduled.fatalFailures());
        }
    }

    private static void assertNoFatalFailures(List<Error> fatalFailures) {
        Assertions.assertTrue(
            fatalFailures.isEmpty(),
            () -> "unexpected process-fatal replay failures: " + fatalFailures
        );
    }

    private static TargetConnectionOwner.RequestLifecycleSink acceptingLifecycleSink() {
        return new TargetConnectionOwner.RequestLifecycleSink() {
            @Override
            public java.util.concurrent.CompletionStage<Void> connectionRequestFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> requestProcessingFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    static long checkHttpRetryConsistency(TestContext rootContext) {
        var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        final var retryMetricCount =
            InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "numRetriedRequests");
        final var targetTransactions =
            InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "targetTransactionCount");
        final var httpTransactions =
            InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "httpTransactionCount");
        Assertions.assertEquals(retryMetricCount, targetTransactions - httpTransactions,
            "every http transaction should have one closed target transaction span per attempt, so the "
                + "surplus of target spans is exactly the retry count; got numRetriedRequests="
                + retryMetricCount + " targetTransactionCount=" + targetTransactions
                + " httpTransactionCount=" + httpTransactions);
        return retryMetricCount;
    }

    @Disabled(value = "This policy opens the replayer up to easy DOS patterns that would halt the replayer.  " +
        "Specifically, if a request is ill-defined and that causes a prematurely closed connection from the server," +
        "that single request would cause the replayer to spin on it indefinitely.  Clearly, malformed requests " +
        "shouldn't have such a great impact.  Other options need to be considered to compensate for a other " +
        "premature connection closed errors.")
    @Tag("longTest")
    @Test
    @WrapWithNettyLeakDetection(disableLeakChecks = true) // code is forcibly terminated so leaks are expected
    public void testMalformedResponseFailuresNeverGiveUp() throws Exception {

        final var SERVERNAME_ALIAS = "webserver";
        var executor = Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("HttpRetryTest"));
        try (var rootContext = TestContext.withAllTracking();
            var network = Network.newNetwork();
             var server = new GenericContainer<>(SharedDockerImageNames.HTTPD)
                 .withNetwork(network)
                 .withNetworkAliases(SERVERNAME_ALIAS)
                 .waitingFor(Wait.forHttp("/").forStatusCode(200)).withStartupTimeout(Duration.ofMinutes(5));
             var toxiproxy = new ToxiProxyWrapper(network))
        {
            server.start();
            toxiproxy.start(SERVERNAME_ALIAS, 80).disable();
            var responseFuture = scheduleSingleRequest(toxiproxy.getProxyURI(), rootContext);
            executor.schedule(toxiproxy::enable, 4, TimeUnit.SECONDS);
            var responses = responseFuture.get();
            var responseList = responses.getResponseList();
            var lastResponse = responseList.get(responseList.size()-1).getRawResponse();
            Assertions.assertNotNull(lastResponse);
            Assertions.assertEquals(200, lastResponse.status().code());
            log.atInfo().setMessage("responses: {}").addArgument(responses).log();
            var retries = checkHttpRetryConsistency(rootContext);
            Assertions.assertTrue(retries > 0);
            var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
            Assertions.assertTrue(InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "requestConnectingCount") > 1);
            Assertions.assertEquals(0, InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "requestConnectingExceptionCount"));
        } finally {
            executor.shutdown();
        }
    }
}
