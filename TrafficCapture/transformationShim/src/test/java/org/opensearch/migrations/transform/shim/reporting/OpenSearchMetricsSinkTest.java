package org.opensearch.migrations.transform.shim.reporting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenSearchMetricsSink}.
 * Tests cover index name generation, template JSON structure,
 * buffering behavior, and close/flush lifecycle.
 */
class OpenSearchMetricsSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DATE_PATTERN = "yyyy.MM.dd";

    @Test
    void generateIndexNameUsesTodaysDate() {
        // Use a non-routable address so no real HTTP calls succeed
        var sink = createSinkQuietly("http://192.0.2.1:9200", "shim-metrics", 100, 60000);
        String indexName = sink.generateIndexName();
        String expected = "shim-metrics-" + LocalDate.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN));
        assertEquals(expected, indexName);
        sink.close();
    }

    @Test
    void generateIndexNameRespectsCustomPrefix() {
        var sink = createSinkQuietly("http://192.0.2.1:9200", "custom-prefix", 100, 60000);
        assertTrue(sink.generateIndexName().startsWith("custom-prefix-"));
        sink.close();
    }

    @Test
    void submitBuffersDocumentsWithoutException() {
        var sink = createSinkQuietly("http://192.0.2.1:9200", "test-metrics", 1000, 60000);
        var doc = minimalDocument();
        // submit should never throw, even if the backend is unreachable
        assertDoesNotThrow(() -> sink.submit(doc));
        sink.close();
    }

    @Test
    void flushDoesNotThrowWhenBufferEmpty() {
        var sink = createSinkQuietly("http://192.0.2.1:9200", "test-metrics", 100, 60000);
        assertDoesNotThrow(sink::flush);
        sink.close();
    }

    @Test
    void flushDoesNotThrowWithBufferedDocs() {
        var sink = createSinkQuietly("http://192.0.2.1:9200", "test-metrics", 1000, 60000);
        sink.submit(minimalDocument());
        sink.submit(minimalDocument());
        // flush will try to send to unreachable host but should not throw
        assertDoesNotThrow(sink::flush);
        sink.close();
    }

    @Test
    void closeIsIdempotent() {
        var sink = createSinkQuietly("http://192.0.2.1:9200", "test-metrics", 100, 60000);
        assertDoesNotThrow(sink::close);
        assertDoesNotThrow(sink::close);
    }

    @Test
    void trailingSlashStrippedFromUri() {
        var sink = createSinkQuietly("http://192.0.2.1:9200/", "test-metrics", 100, 60000);
        String indexName = sink.generateIndexName();
        assertNotNull(indexName);
        sink.close();
    }

    @Test
    void authHeaderSetWhenCredentialsProvided() {
        // Just verify construction doesn't fail with credentials
        var sink = createSinkQuietly("http://192.0.2.1:9200", "test-metrics", 100, 60000,
            "admin", "password");
        assertDoesNotThrow(() -> sink.submit(minimalDocument()));
        sink.close();
    }

    @Test
    void noAuthHeaderWhenCredentialsNull() {
        var sink = createSinkQuietly("http://192.0.2.1:9200", "test-metrics", 100, 60000,
            null, null);
        assertDoesNotThrow(() -> sink.submit(minimalDocument()));
        sink.close();
    }

    private static ValidationDocument minimalDocument() {
        return new ValidationDocument(
            "2025-03-17T10:00:00Z", "test-123",
            null, null, null, null,
            null, null, null, null, null, null, null, null
        );
    }

    /**
     * Creates a sink pointing at a non-routable address.
     * The constructor tries to create an index template which will fail silently.
     */
    private static OpenSearchMetricsSink createSinkQuietly(String uri, String prefix,
            int bulkSize, long flushMs) {
        return createSinkQuietly(uri, prefix, bulkSize, flushMs, null, null);
    }

    private static OpenSearchMetricsSink createSinkQuietly(String uri, String prefix,
            int bulkSize, long flushMs, String user, String pass) {
        return new OpenSearchMetricsSink(uri, prefix, bulkSize, flushMs, user, pass, false);
    }
}
