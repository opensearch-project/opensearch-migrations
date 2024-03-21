package org.opensearch.migrations.replay.datahandlers;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.PacketToTransformingHttpHandlerFactory;
import org.opensearch.migrations.replay.ReplayEngine;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.TrafficReplayer;
import org.opensearch.migrations.replay.TransformationLoader;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.testutils.HttpRequestFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@WrapWithNettyLeakDetection
public class NettyPacketToHttpConsumerTest extends InstrumentationTest {

    public static final String SERVER_RESPONSE_BODY = "I should be decrypted tester!\n";
    public static final int LARGE_RESPONSE_CONTENT_LENGTH = 2 * 1024 * 1024;
    public static final int LARGE_RESPONSE_LENGTH = LARGE_RESPONSE_CONTENT_LENGTH + 4246;

    final static String EXPECTED_REQUEST_STRING =
            "GET / HTTP/1.1\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "Host: localhost\r\n" +
                    "User-Agent: UnitTest\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "\r\n";
    public final static String EXPECTED_RESPONSE_STRING =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-transfer-encoding: chunked\r\n" +
                    "Date: Thu, 08 Jun 2023 23:06:23 GMT\r\n" + // This should be OK since it's always the same length
                    "Transfer-encoding: chunked\r\n" +
                    "Content-type: text/plain\r\n" +
                    "Funtime: checkIt!\r\n" +
                    "\r\n" +
                    "1e\r\n" +
                    "I should be decrypted tester!\n" +
                    "\r\n" +
                    "0\r\n" +
                    "\r\n";

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    private static SimpleHttpResponse makeResponseContext(HttpRequestFirstLine request) {
        var headers = Map.of(
            "Content-Type", "text/plain",
            "Funtime", "checkIt!",
            "Content-Transfer-Encoding", "chunked");
        var payloadBytes = SERVER_RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    private static SimpleHttpResponse makeResponseContextLarge(HttpRequestFirstLine request) {
        var headers = Map.of(
            "Content-Type", "text/plain",
            "Funtime", "checkIt!",
            "Content-Transfer-Encoding", "chunked");

        int size = 2 * 1024 * 1024;
        byte[] payloadBytes = new byte[size];
        Arrays.fill(payloadBytes, (byte) 'A');
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    @SneakyThrows
    private static SimpleHttpServer createTestServer(boolean useTls, boolean largeResponse) {
        return SimpleHttpServer.makeServer(useTls,
            largeResponse ? NettyPacketToHttpConsumerTest::makeResponseContextLarge
                : NettyPacketToHttpConsumerTest::makeResponseContext);
    }

    @Test
    public void testThatTestSetupIsCorrect()
            throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException
    {
        for (var useTls : new boolean[]{false, true}) {
            try (var client = new SimpleHttpClientForTesting(useTls)) {
                try (var testServer = createTestServer(useTls, false)) {
                    var endpoint = testServer.localhostEndpoint();
                    var response = makeTestRequestViaClient(client, endpoint);
                    Assertions.assertEquals(SERVER_RESPONSE_BODY,
                        new String(response.payloadBytes, StandardCharsets.UTF_8));
                }
            }
        }
    }

    private SimpleHttpResponse makeTestRequestViaClient(SimpleHttpClientForTesting client, URI endpoint)
            throws IOException {
        return client.makeGetRequest(endpoint, Map.of("Host", "localhost",
        "User-Agent", "UnitTest").entrySet().stream());
    }



    @ParameterizedTest
    @CsvSource({
        "false, false",
        "false, true",
        "true, false",
        "true, true"
    })
    public void testHttpResponseIsSuccessfullyCaptured(boolean useTls, boolean largeResponse) throws Exception {
        try (var testServer = createTestServer(useTls, largeResponse)) {
            for (int i = 0; i < 1; ++i) {
                var sslContext = !testServer.localhostEndpoint().getScheme().toLowerCase().equals("https") ? null :
                    SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                var httpContext = rootContext.getTestConnectionRequestContext(0);
                var channelContext = httpContext.getChannelKeyContext();
                var eventLoop = new NioEventLoopGroup(1, new DefaultThreadFactory("test")).next();
                var replaySession = new ConnectionReplaySession(eventLoop, channelContext,
                    () -> ClientConnectionPool.getCompletedChannelFutureAsCompletableFuture(
                        httpContext.getChannelKeyContext(),
                        NettyPacketToHttpConsumer.createClientConnection(eventLoop, sslContext,
                            testServer.localhostEndpoint(), channelContext)));
                var nphc = new NettyPacketToHttpConsumer(replaySession, httpContext);
                nphc.consumeBytes((EXPECTED_REQUEST_STRING).getBytes(StandardCharsets.UTF_8));
                var aggregatedResponse = nphc.finalizeRequest().get();
                var responseBytePackets = aggregatedResponse.getCopyOfPackets();
                var responseAsString = Arrays.stream(responseBytePackets)
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .collect(Collectors.joining());
                if (!largeResponse) {
                    Assertions.assertEquals(normalizeMessage(EXPECTED_RESPONSE_STRING),
                        normalizeMessage(responseAsString));
                } else {
                    Assertions.assertEquals(LARGE_RESPONSE_LENGTH,
                        normalizeMessage(responseAsString).getBytes(StandardCharsets.UTF_8).length);

                }            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "false, false",
        "false, true",
        "true, false",
        "true, true"
    })
    public void testThatConnectionsAreKeptAliveAndShared(boolean useTls, boolean largeResponse)
            throws Exception {
        try (var testServer = SimpleHttpServer.makeServer(useTls,
            largeResponse ? NettyPacketToHttpConsumerTest::makeResponseContextLarge : NettyPacketToHttpConsumerTest::makeResponseContext)) {
            var sslContext = !testServer.localhostEndpoint().getScheme().equalsIgnoreCase("https") ? null :
                SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            var transformingHttpHandlerFactory = new PacketToTransformingHttpHandlerFactory(
                new TransformationLoader().getTransformerFactoryLoader(null), null);
            var timeShifter = new TimeShifter();
            timeShifter.setFirstTimestamp(Instant.now());
            var sendingFactory = new ReplayEngine(
                new RequestSenderOrchestrator(
                        new ClientConnectionPool(testServer.localhostEndpoint(), sslContext,
                                "targetPool for testThatConnectionsAreKeptAliveAndShared", 1)),
                new TestFlowController(), timeShifter);
            for (int j = 0; j < 2; ++j) {
                for (int i = 0; i < 2; ++i) {
                    var ctx = rootContext.getTestConnectionRequestContext("TEST_" + i, j);
                    var requestFinishFuture = TrafficReplayer.transformAndSendRequest(transformingHttpHandlerFactory,
                        sendingFactory, ctx, Instant.now(), Instant.now(),
                        () -> Stream.of(EXPECTED_REQUEST_STRING.getBytes(StandardCharsets.UTF_8)));
                    log.info("requestFinishFuture=" + requestFinishFuture);
                    var aggregatedResponse = requestFinishFuture.get();
                    log.debug("Got aggregated response=" + aggregatedResponse);
                    Assertions.assertNull(aggregatedResponse.getError());
                    var responseBytePackets = aggregatedResponse.getCopyOfPackets();
                    var responseAsString = Arrays.stream(responseBytePackets)
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                        .collect(Collectors.joining());
                    if (!largeResponse) {
                        Assertions.assertEquals(normalizeMessage(EXPECTED_RESPONSE_STRING),
                            normalizeMessage(responseAsString));
                    } else {
                        Assertions.assertEquals(LARGE_RESPONSE_LENGTH,
                            normalizeMessage(responseAsString).getBytes(StandardCharsets.UTF_8).length);

                    }
                }
            }
            var stopFuture = sendingFactory.closeConnectionsAndShutdown();
            log.info("waiting for factory to shutdown: " + stopFuture);
            stopFuture.get();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @WrapWithNettyLeakDetection(repetitions = 1)
    public void testMetricCountsFor_testThatConnectionsAreKeptAliveAndShared(boolean useTls) throws Exception {
        testThatConnectionsAreKeptAliveAndShared(useTls, false);
        Thread.sleep(200); // let metrics settle down
        var allMetricData = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        long tcpOpenConnectionCount = allMetricData.stream().filter(md->md.getName().startsWith("tcpConnectionCount"))
                .reduce((a,b)->b).get().getLongSumData().getPoints().stream().reduce((a,b)->b).get().getValue();
        long connectionsOpenedCount = allMetricData.stream().filter(md->md.getName().startsWith("connectionsOpened"))
                .reduce((a,b)->b).get().getLongSumData().getPoints().stream().reduce((a,b)->b).get().getValue();
        long connectionsClosedCount = allMetricData.stream().filter(md->md.getName().startsWith("connectionsClosed"))
                .reduce((a,b)->b).get().getLongSumData().getPoints().stream().reduce((a,b)->b).get().getValue();
        Assertions.assertEquals(2, tcpOpenConnectionCount);
        Assertions.assertEquals(2, connectionsOpenedCount);
        Assertions.assertEquals(2, connectionsClosedCount);
    }

    private static String normalizeMessage(String s) {
        return s.replaceAll("Date: .*", "Date: SOMETHING");
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