package org.opensearch.migrations.replay.http.retries;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.TestContext;

import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import static org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumerTest.REGULAR_RESPONSE_TIMEOUT;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
public class HttpRetryTest {
    private static final DockerImageName NGINX_IMAGE = DockerImageName.parse("nginx:1.27.0-alpine3.19-slim");

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
    scheduleSingleRequest(URI testServerUri) {
        var clientConnectionPool = TrafficReplayerTopLevel.makeNettyPacketConsumerConnectionPool(
            testServerUri,
            false,
            1,
            "targetConnectionPool for testTransientRequestFailuresAreRetried"
        );
        try {
            return scheduleSingleRequest(clientConnectionPool);
        } finally {
            clientConnectionPool.shutdownNow();
        }
    }

    private TrackedFuture<String, TransformedTargetRequestAndResponseList>
    scheduleSingleRequest(ClientConnectionPool clientConnectionPool) {
        try (var rootContext = TestContext.noOtelTracking()) {
            return scheduleSingleRequest(clientConnectionPool, rootContext);
        }
    }

    private TrackedFuture<String, TransformedTargetRequestAndResponseList>
    scheduleSingleRequest(ClientConnectionPool clientConnectionPool, TestContext rootContext) {
        var retryFactory = new RetryCollectingVisitorFactory(new DefaultRetry());
        var senderOrchestrator = new RequestSenderOrchestrator(
            clientConnectionPool,
            (replaySession, ctx) -> new NettyPacketToHttpConsumer(replaySession, ctx, REGULAR_RESPONSE_TIMEOUT)
        );
        var baseTime = Instant.now();
        var requestContext = rootContext.getTestConnectionRequestContext(0);
        var startTimeForThisRequest = baseTime.plus(Duration.ofMillis(10));
        var sourceRequestPackets = makeRequest();
        var sourceResponseBytes = RetryTestUtils.makeSlashResponse(200).getBytes(StandardCharsets.UTF_8);
        var retryVisitor = retryFactory.getRetryCheckVisitor(
            new TransformedOutputAndResult<>(sourceRequestPackets, HttpRequestTransformationStatus.skipped()),
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceResponseBytes),
                () -> "static rrp"));
        log.info("Scheduling item to run at " + startTimeForThisRequest);
        return senderOrchestrator.scheduleRequest(
            requestContext.getReplayerRequestKey(),
            requestContext,
            startTimeForThisRequest,
            Duration.ofMillis(1),
            sourceRequestPackets,
            retryVisitor
        );
    }

    private TransformedTargetRequestAndResponseList
    runServerAndGetResponse(int numFailuresBeforeSuccess) throws Exception
    {
        var requestsReceivedCounter = new AtomicInteger();
        try (
            var httpServer = SimpleHttpServer.makeServer(
                false,
                r -> requestsReceivedCounter.incrementAndGet() > numFailuresBeforeSuccess
                    ? TestHttpServerContext.makeResponse(r, Duration.ofMillis(100))
                    : makeTransientErrorResponse(Duration.ofMillis(100))
            );
        ) {
            var responseFuture = scheduleSingleRequest(httpServer.localhostEndpoint());
            return Assertions.assertDoesNotThrow(() -> responseFuture.get());
        }
    }

    @Test
    public void testTransientRequestFailuresAreRetriedAndCanSucceed() throws Exception {
        var response = runServerAndGetResponse(DefaultRetry.MAX_RETRIES-1);
        Assertions.assertNotNull(response.responses());
        Assertions.assertFalse(response.responses().isEmpty());
        Assertions.assertEquals(DefaultRetry.MAX_RETRIES, response.responses().size());
        Assertions.assertEquals(200,
            response.responses().get(DefaultRetry.MAX_RETRIES-1).getRawResponse().status().code());
        Assertions.assertTrue(response.responses().stream()
            .limit(DefaultRetry.MAX_RETRIES-1)
            .map(r -> r.getRawResponse().status().code())
            .allMatch(c -> 404 == c));
    }

    @Test
    public void testPersistentRequestFailuresAreRetriedThenFailed() throws Exception {
        var response = runServerAndGetResponse(DefaultRetry.MAX_RETRIES+1);
        Assertions.assertNotNull(response.responses());
        Assertions.assertFalse(response.responses().isEmpty());
        Assertions.assertEquals(DefaultRetry.MAX_RETRIES+1, response.responses().size());
        Assertions.assertTrue(response.responses().stream()
            .map(r -> r.getRawResponse().status().code())
            .allMatch(c -> 404 == c));
    }

    @Test
    @WrapWithNettyLeakDetection(disableLeakChecks = true) // code is forcibly terminated so leaks are expected
    public void testConnectionFailuresNeverGiveUp_original() throws Exception {
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
            var f = executor.submit(() -> Assertions.assertThrows(Exception.class,
                () -> scheduleSingleRequest(clientConnectionPool).get()));
            Thread.sleep(1 * 1000);
            clientConnectionPool.shutdownNow().get();

            var e = f.get();
            executor.shutdown();
            log.atInfo().setCause(e).setMessage(() -> "exception: ").log();

//            var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
//
        }
    }

    @Test
    @WrapWithNettyLeakDetection(disableLeakChecks = true) // code is forcibly terminated so leaks are expected
    public void testConnectionFailuresNeverGiveUp() throws Exception {

        final var SERVERNAME_ALIAS = "nginx";
        var executor = Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("HttpRetryTest"));
        try (var network = Network.newNetwork();
             var server = new GenericContainer<>("httpd:alpine")
                 //.withCopyFileToContainer(MountableFile.forHostPath(tmpDirectory), "/usr/share/nginx/html")
                 .withNetwork(network)
                 .withExposedPorts(80)
                 .withNetworkAliases(SERVERNAME_ALIAS)
                 .waitingFor(Wait.forHttp("/").forStatusCode(200));
             var toxiproxy = new ToxiProxyWrapper(network))
        {
            server.start();
            executor.schedule(() -> toxiproxy.start(SERVERNAME_ALIAS, 80), 10, TimeUnit.SECONDS);
            var responseFuture = scheduleSingleRequest(toxiproxy.getProxyURI());
            var responses = responseFuture.get();
            log.atInfo().setMessage(()->"responses: " + responses).log();
//        Assertions.assertFalse(response.responses().isEmpty());
//        Assertions.assertEquals(DefaultRetry.MAX_RETRIES+1, response.responses().size());
//        Assertions.assertTrue(response.responses().stream()
//            .map(r -> r.getRawResponse().status().code())
//            .allMatch(c -> 404 == c));
        } finally {
            executor.shutdown();
        }
    }
}
