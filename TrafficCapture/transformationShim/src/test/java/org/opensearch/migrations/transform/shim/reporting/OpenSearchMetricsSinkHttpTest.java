package org.opensearch.migrations.transform.shim.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OpenSearchMetricsSink} that use a local HTTP server
 * to exercise sendBulkWithRetry, createIndexTemplate, and auth via ConnectionContext.
 */
class OpenSearchMetricsSinkHttpTest {

    private HttpServer server;
    private String baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        baseUri = "http://localhost:" + port;
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private OpenSearchMetricsSink createSink(int bulkSize, long flushMs) {
        return new OpenSearchMetricsSink(ctx(baseUri, null, null), "test", bulkSize, flushMs);
    }

    private OpenSearchMetricsSink createSinkWithAuth(String user, String pass) {
        return new OpenSearchMetricsSink(ctx(baseUri, user, pass), "test", 100, 60000);
    }

    @Test
    void createIndexTemplateSuccess() {
        server.createContext("/", exchange -> {
            byte[] resp = "{\"acknowledged\":true}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();
        var sink = createSink(100, 60000);
        assertDoesNotThrow(sink::createIndexTemplate);
        sink.close();
    }

    @Test
    void createIndexTemplateNonSuccessStatus() {
        server.createContext("/", exchange -> {
            byte[] resp = "{\"error\":\"bad request\"}".getBytes();
            exchange.sendResponseHeaders(400, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();
        var sink = createSink(100, 60000);
        assertDoesNotThrow(sink::createIndexTemplate);
        sink.close();
    }

    @Test
    void createIndexTemplateWithAuth() {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server.createContext("/", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "{\"acknowledged\":true}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();
        var sink = createSinkWithAuth("user", "pass");
        sink.createIndexTemplate();
        assertNotNull(capturedAuth.get());
        assertTrue(capturedAuth.get().startsWith("Basic "));
        sink.close();
    }

    @Test
    void sendBulkSuccessPath() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger bulkCalls = new AtomicInteger();
        server.createContext("/", exchange -> {
            bulkCalls.incrementAndGet();
            byte[] resp = "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"1\",\"result\":\"created\",\"status\":201}}]}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();
        var sink = createSink(100, 60000);
        sink.submit(minimalDoc());
        sink.flush();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, bulkCalls.get());
        sink.close();
    }

    @Test
    void sendBulkWithAuthHeader() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server.createContext("/", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"1\",\"result\":\"created\",\"status\":201}}]}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();
        var sink = createSinkWithAuth("admin", "secret");
        sink.submit(minimalDoc());
        sink.flush();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(capturedAuth.get());
        assertTrue(capturedAuth.get().startsWith("Basic "));
        sink.close();
    }

    @Test
    void submitTriggersFlushAtBulkSize() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            byte[] resp = "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"1\",\"result\":\"created\",\"status\":201}}]}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();
        var sink = createSink(2, 60000);
        sink.submit(minimalDoc());
        sink.submit(minimalDoc());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        sink.close();
    }

    private static ValidationDocument minimalDoc() {
        return new ValidationDocument(
            "2025-03-17T10:00:00Z", "test-" + System.nanoTime(),
            null, null, null, null,
            null, null, null, null, null, null, null, null
        );
    }

    private static ConnectionContext ctx(String uri, String username, String password) {
        return new ConnectionContext.IParams() {
            @Override public String getHost() { return uri; }
            @Override public String getUsername() { return username; }
            @Override public String getPassword() { return password; }
            @Override public String getAwsRegion() { return null; }
            @Override public String getAwsServiceSigningName() { return null; }
            @Override public Path getCaCert() { return null; }
            @Override public Path getClientCert() { return null; }
            @Override public Path getClientCertKey() { return null; }
            @Override public boolean isDisableCompression() { return true; }
            @Override public boolean isInsecure() { return true; }
        }.toConnectionContext();
    }
}
