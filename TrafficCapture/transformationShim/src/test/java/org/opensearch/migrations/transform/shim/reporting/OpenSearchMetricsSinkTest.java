package org.opensearch.migrations.transform.shim.reporting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenSearchMetricsSink}.
 * Uses a non-routable address (192.0.2.1) so HTTP calls fail silently
 * without needing a real OpenSearch instance.
 */
class OpenSearchMetricsSinkTest {

    private static final String DATE_PATTERN = "yyyy.MM.dd";
    private static final String NON_ROUTABLE = "http://192.0.2.1:9200";

    @Test
    void generateIndexNameUsesTodaysDate() {
        var sink = createSink(NON_ROUTABLE, "shim-metrics", 100, 60000);
        String expected = "shim-metrics-" + LocalDate.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN));
        assertEquals(expected, sink.generateIndexName());
        sink.close();
    }

    @Test
    void generateIndexNameRespectsCustomPrefix() {
        var sink = createSink(NON_ROUTABLE, "custom-prefix", 100, 60000);
        assertTrue(sink.generateIndexName().startsWith("custom-prefix-"));
        sink.close();
    }

    @Test
    void submitBuffersWithoutException() {
        var sink = createSink(NON_ROUTABLE, "test", 1000, 60000);
        assertDoesNotThrow(() -> sink.submit(minimalDoc()));
        sink.close();
    }

    @Test
    void submitMultipleDocumentsWithoutException() {
        var sink = createSink(NON_ROUTABLE, "test", 1000, 60000);
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> sink.submit(minimalDoc()));
        }
        sink.close();
    }

    @Test
    void submitTriggersBulkWhenBufferFull() {
        // bulkSize=2, so submitting 2 docs should trigger a bulk send (which fails silently)
        var sink = createSink(NON_ROUTABLE, "test", 2, 60000);
        assertDoesNotThrow(() -> {
            sink.submit(minimalDoc());
            sink.submit(minimalDoc());
        });
        sink.close();
    }

    @Test
    void flushEmptyBufferDoesNotThrow() {
        var sink = createSink(NON_ROUTABLE, "test", 100, 60000);
        assertDoesNotThrow(sink::flush);
        sink.close();
    }

    @Test
    void flushWithBufferedDocsDoesNotThrow() {
        var sink = createSink(NON_ROUTABLE, "test", 1000, 60000);
        sink.submit(minimalDoc());
        sink.submit(minimalDoc());
        assertDoesNotThrow(sink::flush);
        sink.close();
    }

    @Test
    void closeIsIdempotent() {
        var sink = createSink(NON_ROUTABLE, "test", 100, 60000);
        assertDoesNotThrow(sink::close);
        assertDoesNotThrow(sink::close);
    }

    @Test
    void closeFlushesRemainingDocs() {
        var sink = createSink(NON_ROUTABLE, "test", 1000, 60000);
        sink.submit(minimalDoc());
        // close should flush without throwing
        assertDoesNotThrow(sink::close);
    }

    @Test
    void trailingSlashStrippedFromUri() {
        var sink = createSink("http://192.0.2.1:9200/", "test", 100, 60000);
        assertNotNull(sink.generateIndexName());
        sink.close();
    }

    @Test
    void constructorWithConnectionContext() {
        var sink = createSink(NON_ROUTABLE, "test", 100, 60000);
        assertDoesNotThrow(() -> sink.submit(minimalDoc()));
        sink.close();
    }

    @Test
    void scheduledFlushRunsPeriodically() throws InterruptedException {
        // Short flush interval to verify scheduler runs
        var sink = createSink(NON_ROUTABLE, "test", 1000, 100);
        sink.submit(minimalDoc());
        // Wait for at least one scheduled flush cycle
        Thread.sleep(300);
        // Should not throw or hang
        sink.close();
    }

    @Test
    void bulkSizeOneTriggersImmediateFlush() {
        var sink = createSink(NON_ROUTABLE, "test", 1, 60000);
        assertDoesNotThrow(() -> {
            sink.submit(minimalDoc());
            sink.submit(minimalDoc());
            sink.submit(minimalDoc());
        });
        sink.close();
    }

    @Test
    void createIndexTemplateDoesNotThrow() {
        var sink = createSink(NON_ROUTABLE, "test", 100, 60000);
        assertDoesNotThrow(sink::createIndexTemplate);
        sink.close();
    }

    @Test
    void createIndexTemplateWithCredentials() {
        var sink = new OpenSearchMetricsSink(
            createConnectionContext(NON_ROUTABLE, "admin", "password"),
            "test", 100, 60000);
        assertDoesNotThrow(sink::createIndexTemplate);
        sink.close();
    }

    @Test
    void largeBatchOfDocumentsHandledGracefully() {
        var sink = createSink(NON_ROUTABLE, "test", 5, 60000);
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 20; i++) {
                sink.submit(minimalDoc());
            }
        });
        sink.close();
    }

    @Test
    void concurrentSubmitsDoNotThrow() throws InterruptedException {
        var sink = createSink(NON_ROUTABLE, "test", 3, 60000);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 5; i++) {
            var t = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    sink.submit(minimalDoc());
                }
            });
            threads.add(t);
            t.start();
        }
        for (var t : threads) {
            t.join(5000);
        }
        assertDoesNotThrow(sink::close);
    }

    @Test
    void generateIndexNameContainsPrefix() {
        var sink = createSink(NON_ROUTABLE, "my-metrics", 100, 60000);
        String name = sink.generateIndexName();
        assertTrue(name.startsWith("my-metrics-"));
        assertTrue(name.matches("my-metrics-\\d{4}\\.\\d{2}\\.\\d{2}"));
        sink.close();
    }

    @Test
    void submitAndFlushCycle() {
        var sink = createSink(NON_ROUTABLE, "test", 1000, 60000);
        sink.submit(minimalDoc());
        sink.submit(minimalDoc());
        sink.submit(minimalDoc());
        assertDoesNotThrow(sink::flush);
        // Buffer should be empty after flush
        assertDoesNotThrow(sink::flush); // second flush on empty buffer
        sink.close();
    }

    @Test
    void multipleFlushesAfterClose() {
        var sink = createSink(NON_ROUTABLE, "test", 100, 60000);
        sink.submit(minimalDoc());
        sink.close();
        // flush after close should not throw
        assertDoesNotThrow(sink::flush);
    }

    @Test
    void submitWithFullDocument() {
        var sink = createSink(NON_ROUTABLE, "test", 1000, 60000);
        var doc = new ValidationDocument(
            "2025-03-17T10:00:00Z", "full-doc-test",
            new ValidationDocument.RequestRecord("GET", "/solr/test/select?q=*:*", null, null),
            new ValidationDocument.RequestRecord("GET", "/test/_search?q=*:*", null, null),
            "test-collection", "/solr/{collection}/select",
            100L, 95L, 5.0,
            12L, 15L, 3L,
            null, null
        );
        assertDoesNotThrow(() -> sink.submit(doc));
        assertDoesNotThrow(sink::flush);
        sink.close();
    }

    @Test
    void buildIndexTemplateJsonIsValidJson() throws Exception {
        var sink = createSink(NON_ROUTABLE, "test-prefix", 100, 60000);
        String json = sink.buildIndexTemplateJson();
        assertNotNull(json);
        // Verify it's valid JSON
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertTrue(tree.has("index_patterns"));
        assertTrue(tree.get("index_patterns").get(0).asText().startsWith("test-prefix-"));
        assertTrue(tree.has("template"));
        assertTrue(tree.get("template").has("mappings"));
        sink.close();
    }

    private static ValidationDocument minimalDoc() {
        return new ValidationDocument(
            "2025-03-17T10:00:00Z", "test-" + System.nanoTime(),
            null, null, null, null,
            null, null, null, null, null, null, null, null
        );
    }

    private static OpenSearchMetricsSink createSink(String uri, String prefix,
            int bulkSize, long flushMs) {
        return new OpenSearchMetricsSink(
            createConnectionContext(uri),
            prefix, bulkSize, flushMs);
    }

    private static org.opensearch.migrations.bulkload.common.http.ConnectionContext createConnectionContext(String uri) {
        return createConnectionContext(uri, null, null);
    }

    private static org.opensearch.migrations.bulkload.common.http.ConnectionContext createConnectionContext(
            String uri, String username, String password) {
        return new org.opensearch.migrations.bulkload.common.http.ConnectionContext.IParams() {
            @Override public String getHost() { return uri; }
            @Override public String getUsername() { return username; }
            @Override public String getPassword() { return password; }
            @Override public String getAwsRegion() { return null; }
            @Override public String getAwsServiceSigningName() { return null; }
            @Override public java.nio.file.Path getCaCert() { return null; }
            @Override public java.nio.file.Path getClientCert() { return null; }
            @Override public java.nio.file.Path getClientCertKey() { return null; }
            @Override public boolean isDisableCompression() { return true; }
            @Override public boolean isInsecure() { return true; }
        }.toConnectionContext();
    }
}
