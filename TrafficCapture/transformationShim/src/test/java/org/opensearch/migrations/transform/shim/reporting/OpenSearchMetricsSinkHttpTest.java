package org.opensearch.migrations.transform.shim.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OpenSearchMetricsSink} that use a local HTTP server
 * to exercise the sendBulk async callback paths and createIndexTemplate responses.
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

    // --- createIndexTemplate ---

    @Test
    void createIndexTemplateSuccess() {
        server.createContext("/_index_template/", exchange -> {
            byte[] resp = "{\"acknowledged\":true}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();

        var sink = new OpenSearchMetricsSink(baseUri, "test", 100, 60000, null, null, false);
        assertDoesNotThrow(sink::createIndexTemplate);
        sink.close();
    }

    @Test
    void createIndexTemplateNonSuccessStatus() {
        server.createContext("/_index_template/", exchange -> {
            byte[] resp = "{\"error\":\"bad request\"}".getBytes();
            exchange.sendResponseHeaders(400, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();

        var sink = new OpenSearchMetricsSink(baseUri, "test", 100, 60000, null, null, false);
        // Should not throw — logs a warning instead
        assertDoesNotThrow(sink::createIndexTemplate);
        sink.close();
    }

    @Test
    void createIndexTemplateWithAuth() {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server.createContext("/_index_template/", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "{\"acknowledged\":true}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();

        var sink = new OpenSearchMetricsSink(baseUri, "test", 100, 60000, "user", "pass", false);
        sink.createIndexTemplate();
        assertNotNull(capturedAuth.get());
        assertTrue(capturedAuth.get().startsWith("Basic "));
        sink.close();
    }

    // --- sendBulk via submit + flush ---

    @Test
    void sendBulkSuccessPath() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger bulkCalls = new AtomicInteger();

        server.createContext("/_bulk", exchange -> {
            bulkCalls.incrementAndGet();
            byte[] resp = "{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();

        var sink = new OpenSearchMetricsSink(baseUri, "test", 100, 60000, null, null, false);
        sink.submit(minimalDoc());
        sink.flush();
        // Wait for async bulk to complete
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Bulk request should have been sent");
        assertEquals(1, bulkCalls.get());
        sink.close();
    }

    @Test
    void sendBulkSuccessWithPartialFailures() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        server.createContext("/_bulk", exchange -> {
            byte[] resp = ("{\"errors\":true,\"items\":["
                + "{\"index\":{\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed\"}}},"
                + "{\"index\":{\"status\":201}}"
                + "]}").getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();

        var sink = new OpenSearchMetricsSink(baseUri, "test", 100, 60000, null, null, false);
        sink.submit(minimalDoc());
        sink.flush();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        sink.close();
    }

    @Test
    void sendBulkErrorStatusCode() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        server.createContext("/_bulk", exchange -> {
            byte[] resp = "{\"error\":\"internal error\"}".getBytes();
            exchange.sendResponseHeaders(500, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();

        var sink = new OpenSearchMetricsSink(baseUri, "test", 100, 60000, null, null, false);
        sink.submit(minimalDoc());
        sink.flush();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // Should not throw — logs a warning
        sink.close();
    }

    @Test
    void sendBulkWithAuthHeader() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedAuth = new AtomicReference<>();

        server.createContext("/_bulk", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "{\"errors\":false,\"items\":[]}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();

        var sink = new OpenSearchMetricsSink(baseUri, "test", 100, 60000, "admin", "secret", false);
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

        server.createContext("/_bulk", exchange -> {
            byte[] resp = "{\"errors\":false,\"items\":[]}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();

        // bulkSize=2, so submitting 2 docs triggers automatic flush
        var sink = new OpenSearchMetricsSink(baseUri, "test", 2, 60000, null, null, false);
        sink.submit(minimalDoc());
        sink.submit(minimalDoc());
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Bulk should auto-trigger at bulkSize");
        sink.close();
    }

    @Test
    void multipleBulkBatches() throws InterruptedException {
        AtomicInteger bulkCalls = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3);

        server.createContext("/_bulk", exchange -> {
            bulkCalls.incrementAndGet();
            byte[] resp = "{\"errors\":false,\"items\":[]}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            latch.countDown();
        });
        server.start();

        var sink = new OpenSearchMetricsSink(baseUri, "test", 2, 60000, null, null, false);
        // 6 docs with bulkSize=2 should produce 3 bulk requests
        for (int i = 0; i < 6; i++) {
            sink.submit(minimalDoc());
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(3, bulkCalls.get());
        sink.close();
    }

    private static ValidationDocument minimalDoc() {
        return new ValidationDocument(
            "2025-03-17T10:00:00Z", "test-" + System.nanoTime(),
            null, null, null, null,
            null, null, null, null, null, null, null, null
        );
    }
}
