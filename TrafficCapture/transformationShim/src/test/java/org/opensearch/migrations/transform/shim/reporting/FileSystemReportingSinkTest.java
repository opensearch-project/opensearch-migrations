package org.opensearch.migrations.transform.shim.reporting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemReportingSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    /** Build a sample ValidationDocument with all fields populated. */
    private static ValidationDocument sampleDocument(String requestId, String timestamp) {
        var drift = new ValidationDocument.ValueDrift("books", 50, 48, 4.0);
        var comparison = new ValidationDocument.ComparisonEntry(
            "facet_field", "category", true,
            Set.of(), Set.of(),
            List.of(drift)
        );
        return new ValidationDocument(
            timestamp,
            requestId,
            new ValidationDocument.RequestRecord("GET", "/solr/mycore/select?q=*:*", Map.of("Host", "solr:8983"), null),
            new ValidationDocument.RequestRecord("GET", "/mycore/_search?q=*:*", Map.of("Host", "os:9200"), null),
            "mycore",
            "/solr/{collection}/select",
            100L, 95L, 5.0,
            12L, 15L, 3L,
            List.of(comparison),
            new ValidationDocument.ResponseRecord(200, null, null),
            new ValidationDocument.ResponseRecord(200, null, null),
            Map.of("warn-offset", 1)
        );
    }

    /** Build a minimal sample document with a unique requestId. */
    private static ValidationDocument sampleDocument() {
        return sampleDocument(UUID.randomUUID().toString(), "2025-03-17T10:00:00Z");
    }

    private static long jsonFileCount(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".json")).count();
        }
    }

    // ---- Tests ----

    @Test
    void submitWritesDocumentAsJsonFile(@TempDir Path tempDir) throws Exception {
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            sink.submit(sampleDocument());
            sink.flush();
            assertEquals(1, jsonFileCount(tempDir));
        }
    }

    @Test
    void submitDoesNotBlockCallingThread(@TempDir Path tempDir) throws Exception {
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            long start = System.nanoTime();
            sink.submit(sampleDocument());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 100, "submit() took " + elapsedMs + "ms, expected < 100ms");
        }
    }

    @Test
    void submitDiscardsWhenQueueFull(@TempDir Path tempDir) throws Exception {
        // Capacity 2, submit 100 docs rapidly — most will be discarded since the
        // writer thread can't keep up with synchronous offer() calls.
        int capacity = 2;
        int submitted = 100;
        try (var sink = new FileSystemReportingSink(tempDir, capacity)) {
            for (int i = 0; i < submitted; i++) {
                sink.submit(sampleDocument());
            }
            sink.flush();
            long count = jsonFileCount(tempDir);
            assertTrue(count < submitted,
                "Expected fewer files than submitted (" + submitted + "), got " + count
                    + " — discard behavior not observed");
        }
    }

    @Test
    void concurrentSubmitsAreThreadSafe(@TempDir Path tempDir) throws Exception {
        int threadCount = 8;
        int docsPerThread = 10;
        int totalDocs = threadCount * docsPerThread;
        try (var sink = new FileSystemReportingSink(tempDir, totalDocs)) {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    for (int i = 0; i < docsPerThread; i++) {
                        sink.submit(sampleDocument());
                    }
                });
            }
            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            sink.flush();
            assertEquals(totalDocs, jsonFileCount(tempDir));
        }
    }

    @Test
    void eachDocumentWrittenAsSeparateFile(@TempDir Path tempDir) throws Exception {
        int n = 5;
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            for (int i = 0; i < n; i++) {
                sink.submit(sampleDocument());
            }
            sink.flush();
            assertEquals(n, jsonFileCount(tempDir));
        }
    }

    @Test
    void jsonOutputMatchesJacksonAnnotations(@TempDir Path tempDir) throws Exception {
        var original = sampleDocument("abc-123", "2025-03-17T10:00:00Z");
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            sink.submit(original);
            sink.flush();
        }
        // Read back the single JSON file
        Path[] files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toArray(Path[]::new);
        }
        assertEquals(1, files.length);
        byte[] jsonBytes = Files.readAllBytes(files[0]);
        var deserialized = MAPPER.readValue(jsonBytes, ValidationDocument.class);
        assertEquals(original, deserialized);
    }

    @Test
    void fileNameContainsTimestampAndRequestId(@TempDir Path tempDir) throws Exception {
        String requestId = "test-uuid-1234";
        String timestamp = "2025-03-17T10:00:00Z";
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            sink.submit(sampleDocument(requestId, timestamp));
            sink.flush();
        }
        Path[] files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toArray(Path[]::new);
        }
        assertEquals(1, files.length);
        String fileName = files[0].getFileName().toString();
        // Colons in timestamp are replaced with dashes
        String expectedName = "2025-03-17T10-00-00Z_test-uuid-1234.json";
        assertEquals(expectedName, fileName);
    }

    @Test
    void constructorCreatesDirectoryIfMissing(@TempDir Path tempDir) throws Exception {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c");
        assertFalse(Files.exists(nested));
        try (var sink = new FileSystemReportingSink(nested, 16)) {
            assertTrue(Files.isDirectory(nested));
        }
    }

    @Test
    void constructorThrowsWhenDirectoryNotCreatable(@TempDir Path tempDir) throws Exception {
        // Create a regular file — using it as a directory path should fail
        Path regularFile = tempDir.resolve("not-a-dir.txt");
        Files.writeString(regularFile, "I am a file");
        assertThrows(IllegalArgumentException.class,
            () -> new FileSystemReportingSink(regularFile, 16));
    }

    @Test
    void flushWritesAllBufferedDocuments(@TempDir Path tempDir) throws Exception {
        int n = 10;
        try (var sink = new FileSystemReportingSink(tempDir, 64)) {
            for (int i = 0; i < n; i++) {
                sink.submit(sampleDocument());
            }
            sink.flush();
            assertEquals(n, jsonFileCount(tempDir));
        }
    }

    @Test
    void flushOnEmptyBufferReturnsImmediately(@TempDir Path tempDir) throws Exception {
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            long start = System.nanoTime();
            sink.flush();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(0, jsonFileCount(tempDir));
            // Should return reasonably fast (within 1 second)
            assertTrue(elapsedMs < 1000, "flush() on empty buffer took " + elapsedMs + "ms");
        }
    }

    @Test
    void closeFlushesRemainingDocuments(@TempDir Path tempDir) throws Exception {
        var sink = new FileSystemReportingSink(tempDir, 64);
        int n = 5;
        for (int i = 0; i < n; i++) {
            sink.submit(sampleDocument());
        }
        // Close without explicit flush — should still write all documents
        sink.close();
        assertEquals(n, jsonFileCount(tempDir));
    }

    @Test
    void closeStopsBackgroundThread(@TempDir Path tempDir) throws Exception {
        var sink = new FileSystemReportingSink(tempDir, 16);
        sink.close();
        // Give a moment for the thread to terminate
        Thread.sleep(200);
        // Find the background thread by name
        boolean threadAlive = Thread.getAllStackTraces().keySet().stream()
            .anyMatch(t -> t.getName().equals("fs-metrics-sink-writer") && t.isAlive());
        assertFalse(threadAlive, "Background flush thread should not be alive after close()");
    }

    @Test
    void submitAfterCloseDiscardsDocument(@TempDir Path tempDir) throws Exception {
        var sink = new FileSystemReportingSink(tempDir, 16);
        sink.close();
        sink.submit(sampleDocument());
        // Brief wait to ensure nothing is written
        Thread.sleep(200);
        assertEquals(0, jsonFileCount(tempDir));
    }

    @Test
    void ioErrorDoesNotCrashBackgroundThread(@TempDir Path tempDir) throws Exception {
        try (var sink = new FileSystemReportingSink(tempDir, 64)) {
            // Make directory read-only to cause write failures
            assertTrue(tempDir.toFile().setWritable(false));
            sink.submit(sampleDocument("fail-doc", "2025-01-01T00:00:00Z"));
            sink.flush();

            // Restore write permission
            assertTrue(tempDir.toFile().setWritable(true));

            // Submit another document — background thread should still be alive
            sink.submit(sampleDocument("success-doc", "2025-01-01T00:00:01Z"));
            sink.flush();

            // At least the second document should be written
            boolean successFileExists;
            try (var stream = Files.list(tempDir)) {
                successFileExists = stream.anyMatch(p ->
                    p.getFileName().toString().contains("success-doc"));
            }
            assertTrue(successFileExists,
                "Background thread should continue processing after I/O error");
        }
    }

    @Test
    void submitNeverThrows(@TempDir Path tempDir) throws Exception {
        try (var sink = new FileSystemReportingSink(tempDir, 1)) {
            // null document
            assertDoesNotThrow(() -> sink.submit(null));

            // full queue — fill it, then submit one more
            sink.submit(sampleDocument());
            assertDoesNotThrow(() -> sink.submit(sampleDocument()));
        }

        // after close
        var closedSink = new FileSystemReportingSink(tempDir, 16);
        closedSink.close();
        assertDoesNotThrow(() -> closedSink.submit(sampleDocument()));
    }

    @Test
    void nullFieldsOmittedFromJson(@TempDir Path tempDir) throws Exception {
        // Create a document with many null fields
        var doc = new ValidationDocument(
            "2025-03-17T10:00:00Z",
            "null-test-id",
            null, // originalRequest
            null, // transformedRequest
            null, // collectionName
            null, // normalizedEndpoint
            null, // baselineHitCount
            null, // candidateHitCount
            null, // hitCountDriftPercentage
            null, // baselineResponseTimeMs
            null, // candidateResponseTimeMs
            null, // responseTimeDeltaMs
            null, // comparisons
            null, // baselineResponse
            null, // candidateResponse
            null  // customMetrics
        );
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            sink.submit(doc);
            sink.flush();
        }
        Path[] files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toArray(Path[]::new);
        }
        assertEquals(1, files.length);
        String json = Files.readString(files[0]);
        // Null fields should be absent from the JSON
        assertFalse(json.contains("\"original_request\""), "null original_request should be omitted");
        assertFalse(json.contains("\"transformed_request\""), "null transformed_request should be omitted");
        assertFalse(json.contains("\"collection_name\""), "null collection_name should be omitted");
        assertFalse(json.contains("\"baseline_hit_count\""), "null baseline_hit_count should be omitted");
        assertFalse(json.contains("\"comparisons\""), "null comparisons should be omitted");
        assertFalse(json.contains("\"custom_metrics\""), "null custom_metrics should be omitted");
        // But non-null fields should be present
        assertTrue(json.contains("\"timestamp\""));
        assertTrue(json.contains("\"request_id\""));
    }

    @Test
    void closeIsIdempotent(@TempDir Path tempDir) throws Exception {
        var sink = new FileSystemReportingSink(tempDir, 16);
        sink.submit(sampleDocument());
        assertDoesNotThrow(() -> {
            sink.close();
            sink.close();
        });
    }

    @Test
    void defaultConstructorUsesDefaultCapacity(@TempDir Path tempDir) throws Exception {
        try (var sink = new FileSystemReportingSink(tempDir)) {
            sink.submit(sampleDocument());
            sink.flush();
            assertEquals(1, jsonFileCount(tempDir));
        }
    }

    @Test
    void constructorThrowsWhenDirectoryCreationFails(@TempDir Path tempDir) throws Exception {
        // Create a file, then try to create a subdirectory under it — impossible
        Path blockingFile = tempDir.resolve("blocker");
        Files.writeString(blockingFile, "I block directory creation");
        Path impossible = blockingFile.resolve("subdir");
        assertThrows(Exception.class, () -> new FileSystemReportingSink(impossible, 16));
    }

    @Test
    void nullTimestampUsesUnknownInFileName(@TempDir Path tempDir) throws Exception {
        var doc = new ValidationDocument(
            null, "req-123",
            null, null, null, null,
            null, null, null, null, null, null, null,
            null, null, null
        );
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            sink.submit(doc);
            sink.flush();
        }
        Path[] files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toArray(Path[]::new);
        }
        assertEquals(1, files.length);
        assertTrue(files[0].getFileName().toString().startsWith("unknown_"));
    }

    @Test
    void nullRequestIdUsesUnknownInFileName(@TempDir Path tempDir) throws Exception {
        var doc = new ValidationDocument(
            "2025-01-01T00:00:00Z", null,
            null, null, null, null,
            null, null, null, null, null, null, null,
            null, null, null
        );
        try (var sink = new FileSystemReportingSink(tempDir, 16)) {
            sink.submit(doc);
            sink.flush();
        }
        Path[] files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toArray(Path[]::new);
        }
        assertEquals(1, files.length);
        assertTrue(files[0].getFileName().toString().endsWith("_unknown.json"));
    }

    @Test
    void flushAfterCloseIsNoop(@TempDir Path tempDir) throws Exception {
        var sink = new FileSystemReportingSink(tempDir, 16);
        sink.close();
        assertDoesNotThrow(sink::flush);
        assertEquals(0, jsonFileCount(tempDir));
    }
}
