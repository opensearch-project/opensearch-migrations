package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.opensearch.common.collect.Tuple;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.InMemoryConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static java.lang.Math.abs;

@Slf4j
class NettyScanningHttpProxyTest {

    private final static String EXPECTED_REQUEST_STRING =
            "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "DumbAndLongHeaderValue-0: 0\r\n" +
                    "DumbAndLongHeaderValue-1: 1\r\n" +
                    "DumbAndLongHeaderValue-2: 2\r\n" +
                    "DumbAndLongHeaderValue-3: 3\r\n" +
                    "DumbAndLongHeaderValue-4: 4\r\n" +
                    "DumbAndLongHeaderValue-5: 5\r\n" +
                    "DumbAndLongHeaderValue-6: 6\r\n" +
                    "DumbAndLongHeaderValue-7: 7\r\n" +
                    "DumbAndLongHeaderValue-8: 8\r\n" +
                    "DumbAndLongHeaderValue-9: 9\r\n" +
                    "DumbAndLongHeaderValue-10: 10\r\n" +
                    "DumbAndLongHeaderValue-11: 11\r\n" +
                    "DumbAndLongHeaderValue-12: 12\r\n" +
                    "DumbAndLongHeaderValue-13: 13\r\n" +
                    "DumbAndLongHeaderValue-14: 14\r\n" +
                    "DumbAndLongHeaderValue-15: 15\r\n" +
                    "DumbAndLongHeaderValue-16: 16\r\n" +
                    "DumbAndLongHeaderValue-17: 17\r\n" +
                    "DumbAndLongHeaderValue-18: 18\r\n" +
                    "DumbAndLongHeaderValue-19: 19\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "User-Agent: Apache-HttpClient/4.5.13 (Java/17.0.7)\r\n" +
                    "Accept-Encoding: gzip,deflate\r\n" +
                    "\r\n";
    private final static String EXPECTED_RESPONSE_STRING =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-transfer-encoding: chunked\r\n" +
                    "Date: Thu, 08 Jun 2023 23:06:23 GMT\r\n" +
                    "Transfer-encoding: chunked\r\n" +
                    "Content-type: text/plain\r\n" +
                    "Funtime: checkIt!\r\n" +
                    "\r\n" +
                    "e\r\n" +
                    "Hello tester!\n" +
                    "\r\n" +
                    "0\r\n" +
                    "\r\n";

    private static final int MAX_PORT_TRIES = 100;
    private static final Random random = new Random();
    public static final String LOCALHOST = "localhost";
    public static final String UPSTREAM_SERVER_RESPONSE_BODY = "Hello tester!\n";

    private static int retryWithNewPortUntilNoThrow(Consumer<Integer> r) {
        int numTries = 0;
        while (true) {
            try {
                int port = (abs(random.nextInt()) % (2 ^ 16 - 1025)) + 1025;
                r.accept(Integer.valueOf(port));
                return port;
            } catch (Exception e) {
                System.err.println("Exception: " + e);
                e.printStackTrace();
                Assumptions.assumeTrue(++numTries <= MAX_PORT_TRIES);
            }
        }
    }

    @Test
    public void testRoundTrip() throws IOException, InterruptedException {
        final int NUM_EXPECTED_TRAFFIC_STREAMS = 1;
        final int NUM_INTERACTIONS = 3;
        CountDownLatch interactionsCapturedCountdown = new CountDownLatch(NUM_EXPECTED_TRAFFIC_STREAMS);
        var captureFactory = new InMemoryConnectionCaptureFactory(1024 * 1024,
                () -> interactionsCapturedCountdown.countDown());
        var servers = startServers(captureFactory);

        try (var client = HttpClientBuilder.create().build()) {
            var nettyEndpoint = URI.create("http://localhost:" + servers.v1().getProxyPort() + "/");
            for (int i = 0; i < NUM_INTERACTIONS; ++i) {
                var responseBody = makeTestRequestViaClient(client, nettyEndpoint);
                Assertions.assertEquals(UPSTREAM_SERVER_RESPONSE_BODY, responseBody);
            }
        }
        interactionsCapturedCountdown.await();
        var recordedStreams = captureFactory.getRecordedStreams();
        Assertions.assertEquals(1, recordedStreams.size());
        var recordedTrafficStreams =
                recordedStreams.stream()
                        .map(rts -> {
                            try {
                                return TrafficStream.parseFrom(rts.data);
                            } catch (InvalidProtocolBufferException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .toArray(TrafficStream[]::new);
        Assertions.assertEquals(NUM_EXPECTED_TRAFFIC_STREAMS, recordedTrafficStreams.length);
        log.info("Recorded traffic stream:\n" + recordedTrafficStreams[0]);
        var coalescedTrafficList = coalesceObservations(recordedTrafficStreams[0]);
        Assertions.assertEquals(NUM_INTERACTIONS * 2, coalescedTrafficList.size());
        int counter = 0;
        final var expectedMessages = new String[]{
                normalizeMessage(EXPECTED_REQUEST_STRING),
                normalizeMessage(EXPECTED_RESPONSE_STRING)
        };
        for (var httpMessage : coalescedTrafficList) {
            Assertions.assertEquals(expectedMessages[(counter++) % 2],
                    normalizeMessage(new String(httpMessage, StandardCharsets.UTF_8)));
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
            // Fudge factor - allow for variance of +/- 2 byte
            int diff = Math.abs(676 - eom.getHeadersByteLength()) / 2;
            Assertions.assertEquals(0, diff);
        }
    }

    private static String normalizeMessage(String s) {
        return s.replaceAll("Date: .*", "Date: SOMETHING")
                .replaceAll("User-Agent: .*", "User-Agent: Something");
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
                accumOutputStream.write((obs.hasRead() ? obs.getRead().getData() : obs.getWrite().getData())
                        .toByteArray());
            }
            var remaining = accumOutputStream.toByteArray();
            if (remaining.length > 0) {
                rval.add(remaining);
            }
        }
        return rval;
    }

    private static String makeTestRequestViaClient(CloseableHttpClient client, URI nettyEndpoint) throws IOException {
        String responseBody;
        var request = new HttpGet(nettyEndpoint);
        request.setProtocolVersion(new ProtocolVersion("HTTP", 1, 1));
        request.setHeader("Host", "localhost");
        for (int i = 0; i < 20; i++) {
            request.setHeader("DumbAndLongHeaderValue-" + i, "" + i);
        }
        var response = client.execute(request);
        responseBody = new String(response.getEntity().getContent().readAllBytes());
        return responseBody;
    }

    private static Tuple<NettyScanningHttpProxy, HttpServer>
    startServers(IConnectionCaptureFactory connectionCaptureFactory) throws InterruptedException {
        AtomicReference<NettyScanningHttpProxy> nshp = new AtomicReference<>();
        AtomicReference<HttpServer> upstreamTestServer = new AtomicReference<>();
        retryWithNewPortUntilNoThrow(port -> {
            upstreamTestServer.set(createAndStartTestServer(port.intValue()));
        });
        var underlyingPort = upstreamTestServer.get().getAddress().getPort();
        System.out.println("underlying port = " + underlyingPort);

        retryWithNewPortUntilNoThrow(port -> {
            nshp.set(new NettyScanningHttpProxy(port.intValue()));
            try {
                nshp.get().start(LOCALHOST, upstreamTestServer.get().getAddress().getPort(), null,
                        connectionCaptureFactory);
                System.out.println("proxy port = " + port.intValue());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        return new Tuple<>(nshp.get(), upstreamTestServer.get());
    }

    @SneakyThrows
    private static HttpServer createAndStartTestServer(int port) {
        HttpServer server = HttpServer.create(new InetSocketAddress(LOCALHOST, port), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange httpExchange) throws IOException {
                var headers = httpExchange.getResponseHeaders();
                headers.set("Content-Type", "text/plain");
                headers.set("Funtime", "checkIt!");
                headers.set("Content-Transfer-Encoding", "chunked");
                httpExchange.sendResponseHeaders(200, 0);
                var response = UPSTREAM_SERVER_RESPONSE_BODY;
                for (int i = 0; i < 1; ++i) {
                    httpExchange.getResponseBody().write(response.getBytes());
                    httpExchange.getResponseBody().flush();
                }
                httpExchange.getResponseBody().close();
                httpExchange.close();
            }
        });
        server.start();
        return server;
    }
}