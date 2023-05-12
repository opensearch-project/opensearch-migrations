package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.SneakyThrows;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.opensearch.common.collect.Tuple;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.InMemoryConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.lang.Math.abs;

class NettyScanningHttpProxyTest {
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
                System.err.println("Exception: "+e);
                e.printStackTrace();
                Assumptions.assumeTrue(++numTries <= MAX_PORT_TRIES);
            }
        }
    }

    @Test
    public void testRoundTrip() throws IOException, InterruptedException {
        var captureFactory = new InMemoryConnectionCaptureFactory(1024*1024);
        var servers = startServers(captureFactory);

        String responseBody;
        try (var client = HttpClientBuilder.create().build()) {
            var nettyEndpoint = URI.create("http://localhost:" + servers.v1().getProxyPort() + "/");
            var request = new HttpGet(nettyEndpoint);
            request.setProtocolVersion(new ProtocolVersion("HTTP", 1, 1));
            for (int i = 0; i < 20; i++) {
                request.setHeader("DumbAndLongHeaderValue-" + i, "" + i);
            }
            var response = client.execute(request);
            responseBody = new String(response.getEntity().getContent().readAllBytes());
        }

        Assertions.assertEquals(UPSTREAM_SERVER_RESPONSE_BODY, responseBody);
        Thread.sleep(1000*10);
        var recordedStreams = captureFactory.getRecordedStreams();
        Assertions.assertEquals(1, recordedStreams.size());
        var recordedTrafficStreams =
                recordedStreams.stream()
                        .map(rts-> {
                            try {
                                return TrafficStream.parseFrom(rts.data);
                            } catch (InvalidProtocolBufferException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .toArray();
        Assertions.assertEquals(1, recordedTrafficStreams.length);
        System.out.println(recordedTrafficStreams[0]);
    }

    private static Tuple<NettyScanningHttpProxy, HttpServer>
    startServers(IConnectionCaptureFactory connectionCaptureFactory) throws InterruptedException {
        AtomicReference<NettyScanningHttpProxy> nshp = new AtomicReference<>();
        AtomicReference<HttpServer> upstreamTestServer = new AtomicReference<>();
        retryWithNewPortUntilNoThrow(port -> {
            upstreamTestServer.set(createAndStartTestServer(port.intValue()));
        });
        var underlyingPort = upstreamTestServer.get().getAddress().getPort();
        System.out.println("underlying port = "+underlyingPort);

        retryWithNewPortUntilNoThrow(port -> {
            nshp.set(new NettyScanningHttpProxy(port.intValue()));
            try {
                nshp.get().start(LOCALHOST, upstreamTestServer.get().getAddress().getPort(), null,
                        connectionCaptureFactory);
                System.out.println("proxy port = "+port.intValue());
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
                for (int i=0; i<1; ++i) {
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