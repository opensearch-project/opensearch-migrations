package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.opensearch.migrations.testutils.CloseableLogSetup;
import org.opensearch.migrations.testutils.HttpRequest;
import org.opensearch.migrations.testutils.PortFinder;
import org.opensearch.migrations.testutils.SelfSignedSSLContextBuilder;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.InMemoryConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.netty.tracing.RootWireLoggingContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class NettyScanningHttpProxyTest {

    private static final Pattern EXPECTED_REQUEST_PATTERN = Pattern.compile(""
        + "GET / HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "User-Agent: UnitTest\r\n"
        + "DumbAndLongHeaderValue-0: 0\r\n"
        + "DumbAndLongHeaderValue-1: 1\r\n"
        + "DumbAndLongHeaderValue-2: 2\r\n"
        + "DumbAndLongHeaderValue-3: 3\r\n"
        + "DumbAndLongHeaderValue-4: 4\r\n"
        + "DumbAndLongHeaderValue-5: 5\r\n"
        + "DumbAndLongHeaderValue-6: 6\r\n"
        + "DumbAndLongHeaderValue-7: 7\r\n"
        + "DumbAndLongHeaderValue-8: 8\r\n"
        + "DumbAndLongHeaderValue-9: 9\r\n"
        + "DumbAndLongHeaderValue-10: 10\r\n"
        + "DumbAndLongHeaderValue-11: 11\r\n"
        + "DumbAndLongHeaderValue-12: 12\r\n"
        + "DumbAndLongHeaderValue-13: 13\r\n"
        + "DumbAndLongHeaderValue-14: 14\r\n"
        + "DumbAndLongHeaderValue-15: 15\r\n"
        + "DumbAndLongHeaderValue-16: 16\r\n"
        + "DumbAndLongHeaderValue-17: 17\r\n"
        + "DumbAndLongHeaderValue-18: 18\r\n"
        + "DumbAndLongHeaderValue-19: 19.*?\r\n\r\n",
        Pattern.DOTALL);
    private static final String EXPECTED_RESPONSE_STRING = "HTTP/1.1 200 OK\r\n"
        + "Content-transfer-encoding: chunked\r\n"
        + "Date: Thu, 08 Jun 2023 23:06:23 GMT\r\n"
        + // This should be OK since it's always the same length
        "Transfer-encoding: chunked\r\n"
        + "Content-type: text/plain\r\n"
        + "Funtime: checkIt!\r\n"
        + "\r\n"
        + "e\r\n"
        + "Hello tester!\n"
        + "\r\n"
        + "0\r\n"
        + "\r\n";

    public static final String UPSTREAM_SERVER_RESPONSE_BODY = "Hello tester!\n";
    public static final String TEST_NODE_ID_STRING = "test_node_id";

    @Test
    public void testRoundTrip() throws IOException, InterruptedException,
        PortFinder.ExceededMaxPortAssigmentAttemptException {
        final int NUM_EXPECTED_TRAFFIC_STREAMS = 1;
        final int NUM_INTERACTIONS = 3;
        CountDownLatch interactionsCapturedCountdown = new CountDownLatch(NUM_EXPECTED_TRAFFIC_STREAMS);
        var captureFactory = new InMemoryConnectionCaptureFactory(
            TEST_NODE_ID_STRING,
            1024 * 1024,
            () -> interactionsCapturedCountdown.countDown()
        );
        var inMemoryInstrumentationBundle = new InMemoryInstrumentationBundle(true, true);
        var rootCtx = new RootWireLoggingContext(
            inMemoryInstrumentationBundle.openTelemetrySdk,
            IContextTracker.DO_NOTHING_TRACKER
        );
        try (var servers = startServers(rootCtx, captureFactory);
             var client = new SimpleHttpClientForTesting()) {
            var nettyEndpoint = servers.proxyEndpoint();
            for (int i = 0; i < NUM_INTERACTIONS; ++i) {
                var responseBody = makeTestRequestViaClient(client, nettyEndpoint);
                Assertions.assertEquals(UPSTREAM_SERVER_RESPONSE_BODY, responseBody);
            }

            interactionsCapturedCountdown.await();
            var recordedStreams = captureFactory.getRecordedStreams();
            Assertions.assertEquals(1, recordedStreams.size());
            var recordedTrafficStreams = captureFactory.getRecordedTrafficStreamsStream().toArray(TrafficStream[]::new);
            Assertions.assertEquals(NUM_EXPECTED_TRAFFIC_STREAMS, recordedTrafficStreams.length);
            log.info("Recorded traffic stream:\n" + recordedTrafficStreams[0]);
            var coalescedTrafficList = coalesceObservations(recordedTrafficStreams[0]);
            Assertions.assertEquals(NUM_INTERACTIONS * 2, coalescedTrafficList.size());
            int counter = 0;
            final var expectedResponseMessage = normalizeMessage(EXPECTED_RESPONSE_STRING);
            for (var httpMessage : coalescedTrafficList) {
                var normalizedMessage = normalizeMessage(new String(httpMessage, StandardCharsets.UTF_8));
                if (counter % 2 == 0) {
                    Assertions.assertTrue(EXPECTED_REQUEST_PATTERN.matcher(normalizedMessage).matches());
                } else {
                    Assertions.assertEquals(expectedResponseMessage, normalizedMessage);
                }
                counter++;
            }

            var observations = recordedTrafficStreams[0].getSubStreamList();
            var eomIndices = IntStream.range(0, observations.size())
                .filter(i -> observations.get(i).hasEndOfMessageIndicator())
                .toArray();
            Assertions.assertEquals(NUM_INTERACTIONS, eomIndices.length);
            for (int eomIndex : eomIndices) {
                Assertions.assertTrue(observations.get(eomIndex - 1).hasRead());
                Assertions.assertTrue(observations.get(eomIndex + 1).hasWrite());
                var eom = observations.get(eomIndex).getEndOfMessageIndicator();
                Assertions.assertEquals(14, eom.getFirstLineByteLength());
                Assertions.assertEquals(711, eom.getHeadersByteLength());
            }
        }
    }

    @Test
    public void testTlsHandshakeFailureIsLoggedAndProxyStaysAlive() throws Exception {
        var captureFactory = new InMemoryConnectionCaptureFactory(TEST_NODE_ID_STRING, 1024 * 1024, () -> {});
        var inMemoryInstrumentationBundle = new InMemoryInstrumentationBundle(true, true);
        var rootCtx = new RootWireLoggingContext(
            inMemoryInstrumentationBundle.openTelemetrySdk,
            IContextTracker.DO_NOTHING_TRACKER
        );
        var sslEngineSupplier = makeServerSslEngineSupplier();

        try (var servers = startServers(rootCtx, captureFactory, sslEngineSupplier);
             var closeableLogSetup = new CloseableLogSetup(ProxyChannelInitializer.class.getName())) {
            sendPlaintextToTlsEndpoint(servers.proxy.getProxyPort());

            assertEventuallyLogged(closeableLogSetup, ProxyChannelInitializer.TLS_HANDSHAKE_FAILURE_LOG_MESSAGE);

            var responseBody = makeTestRequestViaInsecureHttpsClient(servers.proxyEndpoint());
            Assertions.assertEquals(UPSTREAM_SERVER_RESPONSE_BODY, responseBody);
        }
    }

    @Test
    public void testTlsHandshakeFailureLogsAreDedupedWithinWindow() throws Exception {
        var captureFactory = new InMemoryConnectionCaptureFactory(TEST_NODE_ID_STRING, 1024 * 1024, () -> {});
        var inMemoryInstrumentationBundle = new InMemoryInstrumentationBundle(true, true);
        var rootCtx = new RootWireLoggingContext(
            inMemoryInstrumentationBundle.openTelemetrySdk,
            IContextTracker.DO_NOTHING_TRACKER
        );
        var sslEngineSupplier = makeServerSslEngineSupplier();

        try (var servers = startServers(rootCtx, captureFactory, sslEngineSupplier);
             var closeableLogSetup = new CloseableLogSetup(ProxyChannelInitializer.class.getName())) {
            sendPlaintextToTlsEndpoint(servers.proxy.getProxyPort());
            assertEventuallyLogged(closeableLogSetup, ProxyChannelInitializer.TLS_HANDSHAKE_FAILURE_LOG_MESSAGE);

            sendPlaintextToTlsEndpoint(servers.proxy.getProxyPort());
            Thread.sleep(250);

            Assertions.assertEquals(1, countLogEventsContaining(
                closeableLogSetup,
                ProxyChannelInitializer.TLS_HANDSHAKE_FAILURE_LOG_MESSAGE
            ));

            var responseBody = makeTestRequestViaInsecureHttpsClient(servers.proxyEndpoint());
            Assertions.assertEquals(UPSTREAM_SERVER_RESPONSE_BODY, responseBody);
        }
    }

    private static String normalizeMessage(String s) {
        return s.replaceAll("Date: .*", "Date: SOMETHING");
    }

    private List<byte[]> coalesceObservations(TrafficStream recordedTrafficStream) throws IOException {
        var rval = new ArrayList<byte[]>();
        boolean lastWasRead = true;
        try (var accumOutputStream = new ByteArrayOutputStream()) {
            for (var obs : recordedTrafficStream.getSubStreamList()) {
                if (!obs.hasRead() && !obs.hasTs()) {
                    continue;
                }
                if (lastWasRead != obs.hasRead()) {
                    lastWasRead = obs.hasRead();
                    rval.add(accumOutputStream.toByteArray());
                    accumOutputStream.reset();
                }
                accumOutputStream.write(
                    (obs.hasRead() ? obs.getRead().getData() : obs.getWrite().getData()).toByteArray()
                );
            }
            var remaining = accumOutputStream.toByteArray();
            if (remaining.length > 0) {
                rval.add(remaining);
            }
        }
        return rval;
    }

    private static String makeTestRequestViaClient(SimpleHttpClientForTesting client, URI endpoint) throws IOException {
        var allHeaders = new LinkedHashMap<String, String>();
        allHeaders.put("Host", "localhost");
        allHeaders.put("User-Agent", "UnitTest");
        for (int i = 0; i < 20; i++) {
            allHeaders.put("DumbAndLongHeaderValue-" + i, "" + i);
        }
        var response = client.makeGetRequest(endpoint, allHeaders.entrySet().stream());
        var responseBody = new String(response.payloadBytes);
        return responseBody;
    }

    private static String makeTestRequestViaInsecureHttpsClient(URI endpoint) throws Exception {
        var connection = (HttpsURLConnection) endpoint.toURL().openConnection();
        connection.setSSLSocketFactory(makeTrustAllSslContext().getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> true);
        connection.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static RunningProxyServers startServers(
        RootWireLoggingContext rootCtx,
        IConnectionCaptureFactory connectionCaptureFactory
    ) throws PortFinder.ExceededMaxPortAssigmentAttemptException {
        return startServers(rootCtx, connectionCaptureFactory, null);
    }

    private static RunningProxyServers startServers(
        RootWireLoggingContext rootCtx,
        IConnectionCaptureFactory connectionCaptureFactory,
        java.util.function.Supplier<SSLEngine> sslEngineSupplier
    ) throws PortFinder.ExceededMaxPortAssigmentAttemptException {
        var nshp = new AtomicReference<NettyScanningHttpProxy>();
        var upstreamTestServer = new AtomicReference<SimpleHttpServer>();
        PortFinder.retryWithNewPortUntilNoThrow(port -> {
            try {
                upstreamTestServer.set(new SimpleHttpServer(false, port, NettyScanningHttpProxyTest::makeContext));
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        });
        var underlyingPort = upstreamTestServer.get().port();

        URI testServerUri;
        try {
            testServerUri = new URI("http", null, SimpleHttpServer.LOCALHOST, underlyingPort, null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }

        PortFinder.retryWithNewPortUntilNoThrow(port -> {
            nshp.set(new NettyScanningHttpProxy(port));
            try {
                var connectionPool = new BacksideConnectionPool(testServerUri, null, 10, Duration.ofSeconds(10));

                nshp.get()
                    .start(new ProxyChannelInitializer(rootCtx, connectionPool, sslEngineSupplier,
                        connectionCaptureFactory, new RequestCapturePredicate()), 1);
                System.out.println("proxy port = " + port);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw Lombok.sneakyThrow(e);
            }
        });
        return new RunningProxyServers(nshp.get(), upstreamTestServer.get(), sslEngineSupplier != null);
    }

    private static SimpleHttpResponse makeContext(HttpRequest request) {
        var headers = Map.of(
            "Content-Type",
            "text/plain",
            "Funtime",
            "checkIt!",
            "Content-Transfer-Encoding",
            "chunked"
        );
        var payloadBytes = UPSTREAM_SERVER_RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    private static java.util.function.Supplier<SSLEngine> makeServerSslEngineSupplier() throws Exception {
        SSLContext sslContext = SelfSignedSSLContextBuilder.getSSLContext();
        return () -> {
            var engine = sslContext.createSSLEngine();
            engine.setUseClientMode(false);
            return engine;
        };
    }

    private static SSLContext makeTrustAllSslContext() throws Exception {
        TrustManager[] trustAllManagers = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllManagers, null);
        return sslContext;
    }

    private static void sendPlaintextToTlsEndpoint(int port) throws IOException {
        try (var socket = new Socket(SimpleHttpServer.LOCALHOST, port)) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        }
    }

    private static void assertEventuallyLogged(CloseableLogSetup logSetup, String expectedMessage)
        throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (countLogEventsContaining(logSetup, expectedMessage) > 0) {
                return;
            }
            Thread.sleep(25);
        }
        Assertions.fail("Expected log message containing: " + expectedMessage
            + ". Actual log messages: " + logSetup.getLogEvents());
    }

    private static long countLogEventsContaining(CloseableLogSetup logSetup, String expectedMessage) {
        return new ArrayList<>(logSetup.getLogEvents()).stream()
            .filter(e -> e.contains(expectedMessage))
            .count();
    }

    private static class RunningProxyServers implements AutoCloseable {
        private final NettyScanningHttpProxy proxy;
        private final SimpleHttpServer upstreamServer;
        private final boolean useTls;

        private RunningProxyServers(NettyScanningHttpProxy proxy, SimpleHttpServer upstreamServer, boolean useTls) {
            this.proxy = proxy;
            this.upstreamServer = upstreamServer;
            this.useTls = useTls;
        }

        private URI proxyEndpoint() {
            return URI.create((useTls ? "https" : "http") + "://localhost:" + proxy.getProxyPort() + "/");
        }

        @Override
        public void close() throws InterruptedException {
            try {
                proxy.stop();
            } finally {
                upstreamServer.close();
            }
        }
    }
}
