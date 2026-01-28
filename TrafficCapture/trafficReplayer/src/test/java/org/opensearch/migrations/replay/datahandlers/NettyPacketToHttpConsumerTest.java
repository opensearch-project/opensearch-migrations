package org.opensearch.migrations.replay.datahandlers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.PacketToTransformingHttpHandlerFactory;
import org.opensearch.migrations.replay.ReplayEngineFactory;
import org.opensearch.migrations.replay.ReplayUtils;
import org.opensearch.migrations.replay.RequestTransformerAndSender;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.http.retries.NoRetryEvaluatorFactory;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.testutils.HttpRequest;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Slf4j
@WrapWithNettyLeakDetection
public class NettyPacketToHttpConsumerTest extends InstrumentationTest {

    public static final Duration REGULAR_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    public static final String SERVER_RESPONSE_BODY = "I should be decrypted tester!\n";
    public static final int LARGE_RESPONSE_CONTENT_LENGTH = 2 * 1024 * 1024;
    public static final int LARGE_RESPONSE_LENGTH = LARGE_RESPONSE_CONTENT_LENGTH + 107;

    static final String EXPECTED_REQUEST_STRING = "GET / HTTP/1.1\r\n"
        + "Connection: Keep-Alive\r\n"
        + "Host: localhost\r\n"
        + "User-Agent: UnitTest\r\n"
        + "Connection: Keep-Alive\r\n"
        + "\r\n";
    public static final String EXPECTED_RESPONSE_STRING = "HTTP/1.1 200 OK\r\n"
        + "Content-Type: text/plain\r\n"
        + "Funtime: checkIt!\r\n"
        + "transfer-encoding: chunked\r\n"
        + "\r\n"
        + "1e\r\n"
        + "I should be decrypted tester!\n"
        + "\r\n"
        + "0\r\n"
        + "\r\n";

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    private static SimpleHttpResponse makeResponseContext(HttpRequest request) {
        var headers = new TreeMap(
            Map.of(
                "Content-Type",
                "text/plain",
                "Funtime",
                "checkIt!",
                HttpHeaderNames.TRANSFER_ENCODING.toString(),
                HttpHeaderValues.CHUNKED.toString()
            )
        );
        var payloadBytes = SERVER_RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    private static SimpleHttpResponse makeResponseContextLarge(HttpRequest request) {
        var headers = new TreeMap(
            Map.of(
                "Content-Type",
                "text/plain",
                "Funtime",
                "checkIt!",
                HttpHeaderNames.TRANSFER_ENCODING.toString(),
                HttpHeaderValues.CHUNKED.toString()
            )
        );

        int size = 2 * 1024 * 1024;
        byte[] payloadBytes = new byte[size];
        Arrays.fill(payloadBytes, (byte) 'A');
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    @SneakyThrows
    private static SimpleNettyHttpServer createTestServer(boolean useTls, boolean largeResponse) {
        return SimpleNettyHttpServer.makeServer(
            useTls,
            largeResponse
                ? NettyPacketToHttpConsumerTest::makeResponseContextLarge
                : NettyPacketToHttpConsumerTest::makeResponseContext
        );
    }

    @Test
    public void testThatTestSetupIsCorrect() throws Exception {
        for (var useTls : new boolean[] { false, true }) {
            try (
                var client = new SimpleHttpClientForTesting(useTls);
                var testServer = createTestServer(useTls, false)
            ) {
                var endpoint = testServer.localhostEndpoint();
                var response = makeTestRequestViaClient(client, endpoint);
                Assertions.assertEquals(
                    SERVER_RESPONSE_BODY,
                    new String(response.payloadBytes, StandardCharsets.UTF_8)
                );
            } catch (Throwable t) {
                log.atError().setCause(t).setMessage("Error=").log();
            }
        }
    }

    private SimpleHttpResponse makeTestRequestViaClient(SimpleHttpClientForTesting client, URI endpoint)
        throws IOException {
        return client.makeGetRequest(
            endpoint,
            Map.of("Host", "localhost", "User-Agent", "UnitTest").entrySet().stream()
        );
    }

    @ParameterizedTest
    @CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    @Tag("longTest")
    public void testHttpResponseIsSuccessfullyCaptured(boolean useTls, boolean largeResponse) throws Exception {
        try (var testServer = createTestServer(useTls, largeResponse)) {
            for (int i = 0; i < 1; ++i) {
                var sslContext = !testServer.localhostEndpoint().getScheme().toLowerCase().equals("https")
                    ? null
                    : SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                var httpContext = rootContext.getTestConnectionRequestContext(0);
                var channelContext = httpContext.getChannelKeyContext();
                var eventLoop = new NioEventLoopGroup(1, new DefaultThreadFactory("test")).next();
                var replaySession = new ConnectionReplaySession(
                    eventLoop,
                    channelContext,
                    NettyPacketToHttpConsumer.createClientConnectionFactory(
                        sslContext,
                        testServer.localhostEndpoint()));
                var nphc = new NettyPacketToHttpConsumer(replaySession, httpContext, REGULAR_RESPONSE_TIMEOUT);
                nphc.consumeBytes((EXPECTED_REQUEST_STRING).getBytes(StandardCharsets.UTF_8));
                var aggregatedResponse = nphc.finalizeRequest().get();
                var responseBytePackets = aggregatedResponse.getCopyOfPackets();
                var responseAsString = getResponsePacketsAsString(aggregatedResponse);
                if (!largeResponse) {
                    Assertions.assertEquals(EXPECTED_RESPONSE_STRING, responseAsString);
                } else {
                    Assertions.assertEquals(
                        LARGE_RESPONSE_LENGTH,
                        responseAsString.getBytes(StandardCharsets.UTF_8).length
                    );

                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    @Tag("longTest")
    @WrapWithNettyLeakDetection(repetitions = 1)
    public void testThatPeerResetTriggersFinalizeFuture(boolean useTls, boolean withServerReadTimeout)
        throws Exception {
        final var RESPONSE_TIMEOUT_FOR_HUNG_TEST = Duration.ofMillis(500);
        testPeerResets(
            useTls,
            withServerReadTimeout,
            RESPONSE_TIMEOUT_FOR_HUNG_TEST,
            RESPONSE_TIMEOUT_FOR_HUNG_TEST.plus(Duration.ofMillis(1000))
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    @Tag("longTest")
    @WrapWithNettyLeakDetection(repetitions = 1)
    public void testThatWithBigResponseReadTimeoutResponseWouldHang(boolean useTls) throws Exception {
        testPeerResets(useTls, false, REGULAR_RESPONSE_TIMEOUT, Duration.ofSeconds(5));
    }

    private void testPeerResets(
        boolean useTls,
        boolean withServerReadTimeout,
        Duration readTimeout,
        Duration resultWaitTimeout
    ) throws Exception {
        ClientConnectionPool clientConnectionPool = null;
        try (
            var testServer = SimpleNettyHttpServer.makeServer(
                useTls,
                withServerReadTimeout ? readTimeout : null,
                NettyPacketToHttpConsumerTest::makeResponseContext
            )
        ) {
            log.atError().setMessage("Got port {}").addArgument(testServer.port).log();
            var sslContext = !useTls
                ? null
                : SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            var timeShifter = new TimeShifter();
            timeShifter.setFirstTimestamp(Instant.now());
            clientConnectionPool = new ClientConnectionPool(
                NettyPacketToHttpConsumer.createClientConnectionFactory(sslContext, testServer.localhostEndpoint()),
                "targetPool for testThatConnectionsAreKeptAliveAndShared",
                1
            );

            var reqCtx = rootContext.getTestConnectionRequestContext(1);
            var nphc = new NettyPacketToHttpConsumer(
                clientConnectionPool.buildConnectionReplaySession(reqCtx.getChannelKeyContext()),
                reqCtx,
                readTimeout
            );
            // purposefully send ONLY the beginning of a request
            nphc.consumeBytes("GET ".getBytes(StandardCharsets.UTF_8));
            if (resultWaitTimeout.minus(readTimeout).isNegative()) {
                Assertions.assertThrows(TimeoutException.class, () -> nphc.finalizeRequest().get(resultWaitTimeout));
                return;
            }

            var result = nphc.finalizeRequest().get(resultWaitTimeout);
            try (
                var is = ReplayUtils.byteArraysToInputStream(Arrays.stream(result.getCopyOfPackets()));
                var isr = new InputStreamReader(is);
                var br = new BufferedReader(isr)
            ) {
                Assertions.assertEquals("", Optional.ofNullable(br.readLine()).orElse(""));
                Assertions.assertEquals(0, result.getSizeInBytes());
            }
            if (withServerReadTimeout) {
                log.trace(
                    "An empty response is all that we'll get.  "
                        + "There won't be any packets coming back, so nothing will be accumulated and eventually "
                        + "the connection closes - so the above checks are sufficient"
                );
            } else {
                Assertions.assertInstanceOf(ReadTimeoutException.class, result.getError());
            }
        } finally {
            if (clientConnectionPool != null) {
                var stopFuture = clientConnectionPool.shutdownNow();
                log.info("waiting for factory to shutdown: " + stopFuture);
                stopFuture.get();
                log.info("done shutting down");
            }

        }
    }

    @ParameterizedTest
    @CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    @WrapWithNettyLeakDetection(repetitions = 1)
    @Tag("longTest")
    public void testThatConnectionsAreKeptAliveAndShared(boolean useTls, boolean largeResponse) throws Exception {
        try (
            var testServer = SimpleNettyHttpServer.makeServer(
                useTls,
                largeResponse
                    ? NettyPacketToHttpConsumerTest::makeResponseContextLarge
                    : NettyPacketToHttpConsumerTest::makeResponseContext
            )
        ) {
            var sslContext = !testServer.localhostEndpoint().getScheme().equalsIgnoreCase("https")
                ? null
                : SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            var transformingHttpHandlerFactory = new PacketToTransformingHttpHandlerFactory(
                () -> new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(null),
                null
            );
            var timeShifter = new TimeShifter();
            timeShifter.setFirstTimestamp(Instant.now());
            var clientConnectionPool = new ClientConnectionPool(
                NettyPacketToHttpConsumer.createClientConnectionFactory(sslContext, testServer.localhostEndpoint()),
                "targetPool for testThatConnectionsAreKeptAliveAndShared",
                1
            );
            var replayEngineFactory = new ReplayEngineFactory(REGULAR_RESPONSE_TIMEOUT,
                new TestFlowController(), timeShifter);
            for (int j = 0; j < 2; ++j) {
                for (int i = 0; i < 2; ++i) {
                    var ctx = rootContext.getTestConnectionRequestContext("TEST_" + i, j);

                    var tr = new RequestTransformerAndSender<>(new NoRetryEvaluatorFactory());

                    var requestFinishFuture = tr.transformAndSendRequest(
                        transformingHttpHandlerFactory,
                        replayEngineFactory.apply(clientConnectionPool),
                        TextTrackedFuture.completedFuture(null, () -> "do nothing"),
                        ctx,
                        Instant.now(),
                        Instant.now(),
                        () -> Stream.of(EXPECTED_REQUEST_STRING.getBytes(StandardCharsets.UTF_8)));
                    log.info("requestFinishFuture=" + requestFinishFuture);
                    var aggregatedResponse = requestFinishFuture.get();
                    log.debug("Got aggregated response=" + aggregatedResponse);
                    Assertions.assertNull(aggregatedResponse.getError());
                    var responseAsString = getResponsePacketsAsString(aggregatedResponse);
                    if (!largeResponse) {
                        Assertions.assertEquals(EXPECTED_RESPONSE_STRING, responseAsString);
                    } else {
                        Assertions.assertEquals(
                            LARGE_RESPONSE_LENGTH,
                            responseAsString.getBytes(StandardCharsets.UTF_8).length
                        );
                    }
                }
            }
            var stopFuture = clientConnectionPool.shutdownNow();
            log.info("waiting for factory to shutdown: " + stopFuture);
            stopFuture.get();
        }
    }

    private static String getResponsePacketsAsString(AggregatedRawResponse response) {
        return Arrays.stream(response.getCopyOfPackets())
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .collect(Collectors.joining());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    @WrapWithNettyLeakDetection(repetitions = 1)
    @Tag("longTest")
    public void testMetricCountsFor_testThatConnectionsAreKeptAliveAndShared(boolean useTls) throws Exception {
        testThatConnectionsAreKeptAliveAndShared(useTls, false);
        Thread.sleep(200); // let metrics settle down
        var allMetricData = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        long tcpOpenConnectionCount = allMetricData.stream()
            .filter(md -> md.getName().startsWith("tcpConnectionCount"))
            .reduce((a, b) -> b)
            .get()
            .getLongSumData()
            .getPoints()
            .stream()
            .reduce((a, b) -> b)
            .get()
            .getValue();
        long connectionsOpenedCount = allMetricData.stream()
            .filter(md -> md.getName().startsWith("connectionsOpened"))
            .reduce((a, b) -> b)
            .get()
            .getLongSumData()
            .getPoints()
            .stream()
            .reduce((a, b) -> b)
            .get()
            .getValue();
        long connectionsClosedCount = allMetricData.stream()
            .filter(md -> md.getName().startsWith("connectionsClosed"))
            .reduce((a, b) -> b)
            .get()
            .getLongSumData()
            .getPoints()
            .stream()
            .reduce((a, b) -> b)
            .get()
            .getValue();
        Assertions.assertEquals(2, tcpOpenConnectionCount);
        Assertions.assertEquals(2, connectionsOpenedCount);
        Assertions.assertEquals(2, connectionsClosedCount);
    }

    @ParameterizedTest
    @Tag("longTest")
    @CsvSource({ "false", "true" })
    public void testResponseTakesLongerThanTimeout(boolean useTls) throws Exception {
        var responseTimeout = Duration.ofMillis(50);
        // Response shouldn't come back before everything else finishes
        var responseDuration = Duration.ofHours(1);
        try (var testServer = SimpleNettyHttpServer.makeServer(useTls, (requestFirstLine) -> {
            parkForAtLeast(responseDuration);
            return NettyPacketToHttpConsumerTest.makeResponseContext(requestFirstLine);
        })) {
            var sslContext = !testServer.localhostEndpoint().getScheme().equalsIgnoreCase("https")
                ? null
                : SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            var transformingHttpHandlerFactory = new PacketToTransformingHttpHandlerFactory(
                () -> new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(null),
                null
            );

            var clientConnectionPool = new ClientConnectionPool(
                NettyPacketToHttpConsumer.createClientConnectionFactory(sslContext, testServer.localhostEndpoint()),
                "targetPool for testReadTimeoutHandler_responseTakesLongerThanTimeout",
                1
            );

            var timeShifter = new TimeShifter();
            var firstRequestTime = Instant.now();
            timeShifter.setFirstTimestamp(firstRequestTime);
            log.atInfo().setMessage("Initial Timestamp: {}").addArgument(firstRequestTime).log();

            var replayEngineFactory = new ReplayEngineFactory(responseTimeout,
                new TestFlowController(),
                timeShifter
            );

            var ctx = rootContext.getTestConnectionRequestContext("TEST", 0);
            var tr = new RequestTransformerAndSender<>(new NoRetryEvaluatorFactory());
            var requestFinishFuture = tr.transformAndSendRequest(
                transformingHttpHandlerFactory,
                replayEngineFactory.apply(clientConnectionPool),
                TextTrackedFuture.completedFuture(null, () -> "do nothing"),
                ctx,
                Instant.now(),
                Instant.now(),
                () -> Stream.of(EXPECTED_REQUEST_STRING.getBytes(StandardCharsets.UTF_8)));
            var maxTimeToWaitForTimeoutOrResponse = REGULAR_RESPONSE_TIMEOUT;
            var aggregatedResponse = requestFinishFuture.get(maxTimeToWaitForTimeoutOrResponse);
            log.atInfo().setMessage("RequestFinishFuture finished").log();
            Assertions.assertInstanceOf(ReadTimeoutException.class, aggregatedResponse.getError());
        }
    }

    @ParameterizedTest
    @Tag("longTest")
    @CsvSource({ "false", "true" })
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testTimeBetweenRequestsLongerThanResponseTimeout(boolean useTls) throws Exception {
        var responseTimeout = Duration.ofSeconds(1);
        var timeBetweenRequests = responseTimeout.plus(Duration.ofMillis(10));
        log.atInfo()
            .setMessage("Running testTimeBetweenRequestsLongerThanResponseTimeout with responseTimeout {} and timeBetweenRequests {}")
            .addArgument(responseTimeout)
            .addArgument(timeBetweenRequests)
            .log();
        try (
            var testServer = SimpleNettyHttpServer.makeServer(
                useTls,
                NettyPacketToHttpConsumerTest::makeResponseContext
            )
        ) {
            var sslContext = !testServer.localhostEndpoint().getScheme().equalsIgnoreCase("https")
                ? null
                : SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            var transformingHttpHandlerFactory = new PacketToTransformingHttpHandlerFactory(
                () -> new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(null),
                null
            );

            var clientConnectionPool = new ClientConnectionPool(
                NettyPacketToHttpConsumer.createClientConnectionFactory(sslContext, testServer.localhostEndpoint()),
                "targetPool for testTimeBetweenRequestsLongerThanResponseTimeout",
                1
            );

            var timeShifter = new TimeShifter();
            var firstRequestTime = Instant.now();
            timeShifter.setFirstTimestamp(firstRequestTime);
            log.atInfo().setMessage("Initial Timestamp: {}").addArgument(firstRequestTime).log();
            var replayEngineFactory = new ReplayEngineFactory(responseTimeout,
                new TestFlowController(),
                timeShifter
            );
            int i = 0;
            while (true) {
                var ctx = rootContext.getTestConnectionRequestContext("TEST", i);
                log.atInfo().setMessage("Starting transformAndSendRequest for request {}").addArgument(i).log();

                var tr = new RequestTransformerAndSender<>(new NoRetryEvaluatorFactory());
                var requestFinishFuture = tr.transformAndSendRequest(
                    transformingHttpHandlerFactory,
                    replayEngineFactory.apply(clientConnectionPool),
                    TextTrackedFuture.completedFuture(null, () -> "do nothing"),
                    ctx,
                    Instant.now(),
                    Instant.now(),
                    () -> Stream.of(EXPECTED_REQUEST_STRING.getBytes(StandardCharsets.UTF_8)));
                var maxTimeToWaitForTimeoutOrResponse = REGULAR_RESPONSE_TIMEOUT;
                var aggregatedResponse = requestFinishFuture.get(maxTimeToWaitForTimeoutOrResponse);
                log.atInfo().setMessage("RequestFinishFuture finished for request {}").addArgument(i).log();
                Assertions.assertNull(aggregatedResponse.getError());
                var responseAsString = getResponsePacketsAsString(aggregatedResponse);
                Assertions.assertEquals(EXPECTED_RESPONSE_STRING, responseAsString);
                if (i > 1) {
                    break;
                }
                parkForAtLeast(timeBetweenRequests);
                i++;
            }
        }
    }

    private static void parkForAtLeast(Duration waitDuration) {
        var responseTime = Instant.now().toEpochMilli() + waitDuration.toMillis();
        while (Instant.now().toEpochMilli() < responseTime) {
            LockSupport.parkUntil(responseTime);
        }
    }

    private class TestFlowController implements BufferedFlowController {
        @Override
        public void stopReadsPast(Instant pointInTime) {}

        @Override
        public Duration getBufferTimeWindow() {
            return Duration.ofSeconds(1);
        }
    }
}
