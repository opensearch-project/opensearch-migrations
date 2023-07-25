package org.opensearch.migrations.replay.datahandlers;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.PortFinder;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.opensearch.migrations.testutils.SimpleHttpServer;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

class NettyPacketToHttpConsumerTest {
    public static final String SERVER_RESPONSE_BODY = "I should be decrypted tester!\n";


    private final static String EXPECTED_REQUEST_STRING =
            "GET / HTTP/1.1\r\n" +
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


    private static SimpleHttpServer testServer;

    @BeforeAll
    public static void setupTestServer() throws PortFinder.ExceededMaxPortAssigmentAttemptException {
        var testServerRef = new AtomicReference<SimpleHttpServer>();
        PortFinder.retryWithNewPortUntilNoThrow(port -> {
            try {
                testServerRef.set(new SimpleHttpServer(true, port.intValue(),
                        NettyPacketToHttpConsumerTest::makeContext));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        testServer = testServerRef.get();
    }

    @AfterAll
    public static void tearDownTestServer() throws Exception {
        testServer.close();
    }

    @Test
    public void testThatTestSetupIsCorrect()
            throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException
    {
        //Thread.sleep(120000);
        try (var client = new SimpleHttpClientForTesting(true)) {
            var endpoint = URI.create("https://localhost:" + testServer.port() + "/");
            var response = makeTestRequestViaClient(client, endpoint);
            Assertions.assertEquals(SERVER_RESPONSE_BODY, new String(response.payloadBytes, StandardCharsets.UTF_8));
        }
    }

    private SimpleHttpResponse makeTestRequestViaClient(SimpleHttpClientForTesting client, URI endpoint)
            throws IOException {
        return client.makeGetRequest(endpoint,
                Map.of("Host", "localhost",
        "User-Agent", "UnitTest").entrySet().stream());
    }

    private static SimpleHttpResponse makeContext(SimpleHttpServer.HttpFirstLine request) {
        var headers = Map.of(
                "Content-Type", "text/plain",
                "Funtime", "checkIt!",
                "Content-Transfer-Encoding", "chunked");
        var payloadBytes = SERVER_RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    @Test
    public void testHttpResponseIsASuccessfullyCaptured()
            throws SSLException, ExecutionException, InterruptedException
    {
        var sslContextBuilder = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE);
        var nphc = new NettyPacketToHttpConsumer(new NioEventLoopGroup(),
                testServer.localhostEndpoint(), sslContextBuilder.build(),"unitTest");
        nphc.consumeBytes(EXPECTED_REQUEST_STRING.getBytes(StandardCharsets.UTF_8));
        var aggregatedResponse = nphc.finalizeRequest().get();
        var responseBytePackets = aggregatedResponse.getCopyOfPackets();
        var responseAsString = Arrays.stream(responseBytePackets)
                .map(bytes->new String(bytes,StandardCharsets.UTF_8))
                .collect(Collectors.joining());
        Assertions.assertEquals(normalizeMessage(EXPECTED_RESPONSE_STRING),
                normalizeMessage(responseAsString));
    }

    private static String normalizeMessage(String s) {
        return s.replaceAll("Date: .*", "Date: SOMETHING");
    }

}