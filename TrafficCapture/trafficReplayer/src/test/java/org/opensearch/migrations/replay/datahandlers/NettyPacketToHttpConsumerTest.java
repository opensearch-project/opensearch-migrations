package org.opensearch.migrations.replay.datahandlers;

import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.migrations.replay.BufferedTimeController;
import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.PacketToTransformingHttpHandlerFactory;
import org.opensearch.migrations.replay.ReplayEngine;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.TrafficReplayer;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.testutils.HttpFirstLine;
import org.opensearch.migrations.testutils.PortFinder;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.transform.JsonJoltTransformBuilder;

import javax.net.ssl.SSLException;
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
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
class NettyPacketToHttpConsumerTest {

    public static final String SERVER_RESPONSE_BODY = "I should be decrypted tester!\n";

    final static String EXPECTED_REQUEST_STRING =
            "GET / HTTP/1.1\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "Host: localhost\r\n" +
                    "User-Agent: UnitTest\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "\r\n";
    private final static String EXPECTED_RESPONSE_STRING =
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

    private static Map<Boolean, SimpleHttpServer> testServers;

    @BeforeAll
    public static void setupTestServer() throws PortFinder.ExceededMaxPortAssigmentAttemptException {
        testServers = Map.of(
                false, SimpleHttpServer.makeServer(false, NettyPacketToHttpConsumerTest::makeContext),
                true, SimpleHttpServer.makeServer(true, NettyPacketToHttpConsumerTest::makeContext));
    }

    @AfterAll
    public static void tearDownTestServer() throws Exception {
        testServers.values().stream().forEach(s-> {
            try {
                s.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void testThatTestSetupIsCorrect()
            throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException
    {
        for (var useTls : new boolean[]{false, true}) {
            try (var client = new SimpleHttpClientForTesting(useTls)) {
                var endpoint = testServers.get(useTls).localhostEndpoint();
                var response = makeTestRequestViaClient(client, endpoint);
                Assertions.assertEquals(SERVER_RESPONSE_BODY,
                        new String(response.payloadBytes, StandardCharsets.UTF_8));
            }
        }
    }

    private SimpleHttpResponse makeTestRequestViaClient(SimpleHttpClientForTesting client, URI endpoint)
            throws IOException {
        return client.makeGetRequest(endpoint, Map.of("Host", "localhost",
        "User-Agent", "UnitTest").entrySet().stream());
    }

    private static SimpleHttpResponse makeContext(HttpFirstLine request) {
        var headers = Map.of(
                "Content-Type", "text/plain",
                "Funtime", "checkIt!",
                "Content-Transfer-Encoding", "chunked");
        var payloadBytes = SERVER_RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHttpResponseIsSuccessfullyCaptured(boolean useTls) throws Exception {
        for (int i = 0; i < 3; ++i) {
            var testServer = testServers.get(useTls);
            var sslContext = !testServer.localhostEndpoint().getScheme().toLowerCase().equals("https") ? null :
                    SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            var nphc = new NettyPacketToHttpConsumer(new NioEventLoopGroup(4),
                    testServer.localhostEndpoint(), sslContext, "unitTest"+i);
            nphc.consumeBytes((EXPECTED_REQUEST_STRING).getBytes(StandardCharsets.UTF_8));
            var aggregatedResponse = nphc.finalizeRequest().get();
            var responseBytePackets = aggregatedResponse.getCopyOfPackets();
            var responseAsString = Arrays.stream(responseBytePackets)
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .collect(Collectors.joining());
            Assertions.assertEquals(normalizeMessage(EXPECTED_RESPONSE_STRING),
                    normalizeMessage(responseAsString));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testThatConnectionsAreKeptAliveAndShared(boolean useTls)
            throws SSLException, ExecutionException, InterruptedException
    {
        var testServer = testServers.get(useTls);
        var sslContext = !testServer.localhostEndpoint().getScheme().toLowerCase().equals("https") ? null :
                SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        var transformingHttpHandlerFactory = new PacketToTransformingHttpHandlerFactory(
                new JsonJoltTransformBuilder().build(), null);
        var sendingFactory = new ReplayEngine(
                new RequestSenderOrchestrator(
                        new ClientConnectionPool(testServer.localhostEndpoint(), sslContext, 1)),
                new TestTimeController(), new TimeShifter(), 2.0);
        for (int j=0; j<2; ++j) {
            for (int i = 0; i < 2; ++i) {
                String connId = "TEST_" + j;
                var requestFinishFuture = TrafficReplayer.transformAndSendRequest(transformingHttpHandlerFactory,
                        sendingFactory, Instant.now(), Instant.now(),
                        new UniqueRequestKey(connId, i),
                        Stream.of(Unpooled.wrappedBuffer(EXPECTED_REQUEST_STRING.getBytes(StandardCharsets.UTF_8))));
                log.info("requestFinishFuture="+requestFinishFuture);
                var aggregatedResponse = requestFinishFuture.get();
                log.debug("Got aggregated response=" + aggregatedResponse);
                Assertions.assertNull(aggregatedResponse.getError());
                var responseBytePackets = aggregatedResponse.getCopyOfPackets();
                var responseAsString = Arrays.stream(responseBytePackets)
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                        .collect(Collectors.joining());
                Assertions.assertEquals(normalizeMessage(EXPECTED_RESPONSE_STRING),
                        normalizeMessage(responseAsString));
            }
        }
        var stopFuture = sendingFactory.close();
        log.info("waiting for factory to shutdown: " + stopFuture);
        stopFuture.get();
        Assertions.assertEquals(2, sendingFactory.getNumConnectionsCreated());
        Assertions.assertEquals(2, sendingFactory.getNumConnectionsClosed());
    }

    private static String normalizeMessage(String s) {
        return s.replaceAll("Date: .*", "Date: SOMETHING");
    }

    private class TestTimeController implements BufferedTimeController {
        @Override
        public void stopReadsPast(Instant pointInTime) {}

        @Override
        public Duration getBufferTimeWindow() {
            return Duration.ofSeconds(1);
        }
    }
}