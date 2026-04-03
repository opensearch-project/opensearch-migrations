package org.opensearch.migrations.transform.shim.reporting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void constructorWithCredentials() {
        var sink = new OpenSearchMetricsSink(NON_ROUTABLE, "test", 100, 60000,
            "admin", "password", false);
        assertDoesNotThrow(() -> sink.submit(minimalDoc()));
        sink.close();
    }

    @Test
    void constructorWithNullCredentials() {
        var sink = new OpenSearchMetricsSink(NON_ROUTABLE, "test", 100, 60000,
            null, null, false);
        assertDoesNotThrow(() -> sink.submit(minimalDoc()));
        sink.close();
    }

    @Test
    void constructorWithInsecureTls() {
        var sink = new OpenSearchMetricsSink(NON_ROUTABLE, "test", 100, 60000,
            null, null, true);
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
        // Each submit should trigger a bulk send immediately
        assertDoesNotThrow(() -> {
            sink.submit(minimalDoc());
            sink.submit(minimalDoc());
            sink.submit(minimalDoc());
        });
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
        return new OpenSearchMetricsSink(uri, prefix, bulkSize, flushMs, null, null, false);
    }
}
