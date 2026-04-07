package org.opensearch.migrations.replay.sink;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GzipJsonLinesSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Map<String, Object> makeTuple(String id) {
        var map = new LinkedHashMap<String, Object>();
        map.put("connectionId", id);
        map.put("numRequests", 1);
        return map;
    }

    @Test
    void futuresCompleteOnEndOfBatch() throws Exception {
        try (var sink = new GzipJsonLinesSink(tempDir, 10 * 1024 * 1024, Duration.ofMinutes(10))) {
            var f1 = new CompletableFuture<Void>();
            var f2 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), f1);
            sink.accept(makeTuple("conn2.0"), f2);

            assertFalse(f1.isDone());
            assertFalse(f2.isDone());

            // onEndOfBatch rotates: finish + fsync + close + open new
            sink.onEndOfBatch();
            assertTrue(f1.isDone(), "Future should complete on onEndOfBatch");
            assertTrue(f2.isDone(), "Future should complete on onEndOfBatch");
            f1.get(1, TimeUnit.SECONDS);
            f2.get(1, TimeUnit.SECONDS);

            // Write more — goes to a new file since previous batch rotated
            var f3 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn3.0"), f3);
            sink.onEndOfBatch();
            assertTrue(f3.isDone());
            f3.get(1, TimeUnit.SECONDS);
        }

        // Each onEndOfBatch rotates, so we get 2 data files (close() produces an empty file that's also valid)
        var gzFiles = Files.list(tempDir).filter(p -> p.toString().endsWith(".log.gz")).sorted().toList();
        assertTrue(gzFiles.size() >= 2, "Expected at least 2 files from rotation, got " + gzFiles.size());

        int totalLines = 0;
        for (var file : gzFiles) {
            try (var reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(file.toFile()))))) {
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    var parsed = MAPPER.readValue(line, Map.class);
                    assertTrue(((String) parsed.get("connectionId")).startsWith("conn"));
                    totalLines++;
                }
            }
        }
        assertEquals(3, totalLines);
    }

    @Test
    void onIdleFlushesUncommittedTuples() throws Exception {
        try (var sink = new GzipJsonLinesSink(tempDir, 10 * 1024 * 1024, Duration.ofMinutes(10))) {
            var f1 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), f1);
            assertFalse(f1.isDone());

            sink.onIdle();
            assertTrue(f1.isDone(), "Future should complete on onIdle when tuples are pending");
            f1.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void rotatesOnSizeThreshold() throws Exception {
        // 100 byte threshold — each tuple is ~40 bytes, so 3 tuples should trigger rotation
        try (var sink = new GzipJsonLinesSink(tempDir, 100, Duration.ofMinutes(10))) {
            var futures = new CompletableFuture[5];
            for (int i = 0; i < 5; i++) {
                futures[i] = new CompletableFuture<Void>();
                sink.accept(makeTuple("conn" + i + ".0"), futures[i]);
                sink.onEndOfBatch();
                assertTrue(futures[i].isDone(), "Future " + i + " should be done after onEndOfBatch");
            }
        }

        var gzFiles = Files.list(tempDir).filter(p -> p.toString().endsWith(".log.gz")).toList();
        assertTrue(gzFiles.size() > 1, "Expected multiple files from rotation, got " + gzFiles.size());

        int totalLines = 0;
        for (var file : gzFiles) {
            try (var reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(file.toFile()))))) {
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    var parsed = MAPPER.readValue(line, Map.class);
                    assertTrue(((String) parsed.get("connectionId")).startsWith("conn"));
                    totalLines++;
                }
            }
        }
        assertEquals(5, totalLines);
    }

    @Test
    void rotatesOnTimeThreshold() throws Exception {
        try (var sink = new GzipJsonLinesSink(tempDir, 10 * 1024 * 1024, Duration.ofMillis(1))) {
            var f1 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), f1);
            sink.onEndOfBatch();
            assertTrue(f1.isDone());
            f1.get(1, TimeUnit.SECONDS);

            Thread.sleep(10);

            var f2 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn2.0"), f2);
            sink.onEndOfBatch();
            assertTrue(f2.isDone());
        }

        var gzFiles = Files.list(tempDir).filter(p -> p.toString().endsWith(".log.gz")).toList();
        assertTrue(gzFiles.size() >= 2, "Expected at least 2 files from time rotation, got " + gzFiles.size());
    }

    @Test
    void constructorThrowsForInvalidPath() {
        var badPath = Path.of("/dev/null/impossible");
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
            java.io.UncheckedIOException.class,
            () -> new GzipJsonLinesSink(badPath, 1024, Duration.ofMinutes(1))
        );
        assertTrue(ex.getMessage().contains("Failed to"));
    }
}
