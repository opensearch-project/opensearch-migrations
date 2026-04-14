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

            assertFalse(f1.isDone(), "Future should be pending before flush");
            assertFalse(f2.isDone(), "Future should be pending before flush");

            // flush forces rotation: finish + fsync + close + open new
            sink.flush();
            assertTrue(f1.isDone(), "Future should complete on flush");
            assertTrue(f2.isDone(), "Future should complete on flush");
            f1.get(1, TimeUnit.SECONDS);
            f2.get(1, TimeUnit.SECONDS);

            // Write more — goes to a new file since previous batch rotated
            var f3 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn3.0"), f3);
            sink.flush();
            assertTrue(f3.isDone());
            f3.get(1, TimeUnit.SECONDS);
        }

        // Each flush rotates, so we get 2 data files (close() produces an empty file that's also valid)
        var gzFiles = Files.list(tempDir).filter(p -> p.toString().endsWith(".log.gz")).sorted().toList();
        assertTrue(gzFiles.size() >= 2, "Expected at least 2 files from rotation, got " + gzFiles.size());

        assertEquals(3, countTotalLines(gzFiles));
    }

    @Test
    void batchesMultipleTuplesPerFileWhenBelowThreshold() throws Exception {
        // Large thresholds — tuples should accumulate without rotation
        try (var sink = new GzipJsonLinesSink(tempDir, 10 * 1024 * 1024, Duration.ofMinutes(10))) {
            var futures = new CompletableFuture[5];
            for (int i = 0; i < 5; i++) {
                futures[i] = new CompletableFuture<Void>();
                sink.accept(makeTuple("conn" + i + ".0"), futures[i]);
                // No flush — futures should stay pending
                assertFalse(futures[i].isDone(), "Future " + i + " should be pending (below threshold)");
            }
        }
        // close() flushes all pending futures and produces one file with all 5 tuples
        var gzFiles = Files.list(tempDir).filter(p -> p.toString().endsWith(".log.gz")).sorted().toList();
        // One data file (with 5 tuples) + one empty file opened after close-rotation = 1 file from openNewFile()
        // Actually close() doesn't openNewFile, it just finishes+syncs+closes. So just 1 file.
        assertEquals(5, countTotalLines(gzFiles));
    }

    @Test
    void autoRotatesOnSizeThreshold() throws Exception {
        // 100 byte threshold — each tuple is ~40 bytes uncompressed, so ~3 tuples triggers rotation
        try (var sink = new GzipJsonLinesSink(tempDir, 100, Duration.ofMinutes(10))) {
            var futures = new CompletableFuture[5];
            for (int i = 0; i < 5; i++) {
                futures[i] = new CompletableFuture<Void>();
                sink.accept(makeTuple("conn" + i + ".0"), futures[i]);
            }
            // At least the first batch should have auto-rotated inside accept()
            // The last tuples may still be pending (below threshold in the new file)
        }

        var gzFiles = Files.list(tempDir).filter(p -> p.toString().endsWith(".log.gz")).toList();
        assertTrue(gzFiles.size() > 1, "Expected multiple files from size-based rotation, got " + gzFiles.size());
        assertEquals(5, countTotalLines(gzFiles));
    }

    @Test
    void autoRotatesOnTimeThreshold() throws Exception {
        try (var sink = new GzipJsonLinesSink(tempDir, 10 * 1024 * 1024, Duration.ofMillis(1))) {
            var f1 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), f1);
            // Future pending — time threshold not yet hit at accept time
            // Force flush so f1 completes
            sink.flush();
            assertTrue(f1.isDone());

            Thread.sleep(10); // let the age threshold expire

            var f2 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn2.0"), f2);
            // accept() should detect age threshold and auto-rotate
            assertTrue(f2.isDone(), "Future should complete when age threshold triggers rotation in accept()");
        }

        var gzFiles = Files.list(tempDir).filter(p -> p.toString().endsWith(".log.gz")).toList();
        assertTrue(gzFiles.size() >= 2, "Expected at least 2 files from time rotation, got " + gzFiles.size());
    }

    @Test
    void periodicFlushFlushesUncommittedTuples() throws Exception {
        try (var sink = new GzipJsonLinesSink(tempDir, 10 * 1024 * 1024, Duration.ofMinutes(10))) {
            var f1 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), f1);
            assertFalse(f1.isDone());

            sink.periodicFlush();
            assertTrue(f1.isDone(), "Future should complete on periodicFlush when tuples are pending");
            f1.get(1, TimeUnit.SECONDS);
        }
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

    private int countTotalLines(java.util.List<Path> gzFiles) throws Exception {
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
        return totalLines;
    }
}
