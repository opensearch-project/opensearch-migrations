package org.opensearch.migrations.s3sink;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotatingGzipS3ObjectWriterTest {

    private record Captured(String key, byte[] data) {}

    /** Records each upload's key + staged-file bytes (read before the writer deletes the file). */
    private static RotatingGzipS3ObjectWriter.ObjectUploader capturing(List<Captured> out) {
        return (bucket, key, file) -> {
            try {
                out.add(new Captured(key, Files.readAllBytes(file)));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    private static RotatingGzipS3ObjectWriter<byte[]> writer(
        RotatingGzipS3ObjectWriter.ObjectUploader uploader,
        RotationPolicy policy,
        int maxUploadAttempts
    ) {
        return new RotatingGzipS3ObjectWriter<>(
            uploader,
            "bucket",
            (now, seq) -> "p/obj-" + seq + ".gz",
            bytes -> bytes,
            policy,
            Duration.ofMillis(10),
            maxUploadAttempts,
            "writer-test-");
    }

    private static String decode(byte[] gz) throws IOException {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] rec(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void flushUploadsGzippedNewlineDelimitedRecords() throws Exception {
        var captured = new CopyOnWriteArrayList<Captured>();
        try (var w = writer(capturing(captured), RotationPolicy.ofBytes(1 << 20), 1)) {
            w.write(rec("a"));
            w.write(rec("b"));
            w.flush().get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, captured.size());
        assertEquals("p/obj-0.gz", captured.get(0).key());
        assertEquals("a\nb\n", decode(captured.get(0).data()));
    }

    @Test
    void rotationByRecordCountProducesMultipleObjectsWithDistinctKeys() throws Exception {
        var captured = new CopyOnWriteArrayList<Captured>();
        // Rotate every 2 records (no size/age rotation).
        try (var w = writer(capturing(captured), new RotationPolicy(0, null, 2), 1)) {
            w.write(rec("a"));
            w.write(rec("b"));   // crosses count -> rotates object obj-0 (a,b)
            w.write(rec("c"));
            w.flush().get(2, TimeUnit.SECONDS);   // flushes remainder obj-1 (c)
        }
        assertEquals(2, captured.size());
        assertEquals("p/obj-0.gz", captured.get(0).key());
        assertEquals("a\nb\n", decode(captured.get(0).data()));
        assertEquals("p/obj-1.gz", captured.get(1).key());
        assertEquals("c\n", decode(captured.get(1).data()));
    }

    @Test
    void failFastUploadFailureSurfacesOnWriteThenOnceMoreOnFlushThenClears() throws Exception {
        RotatingGzipS3ObjectWriter.ObjectUploader failing =
            (bucket, key, file) -> CompletableFuture.failedFuture(new IOException("boom"));
        try (var w = writer(failing, RotationPolicy.ofBytes(1), 1)) {   // rotate every write, fail fast
            var writeFuture = w.write(rec("a"));
            assertTrue(writeFuture.isCompletedExceptionally(), "fail-fast upload should fail the write future");

            var ex = assertThrows(ExecutionException.class, () -> w.flush().get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IOException, "gating flush re-surfaces the retained failure");

            // Once surfaced, the retained failure is cleared.
            w.flush().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void retriesSameKeyUntilSuccessWhenUnlimitedAttempts() throws Exception {
        var attemptKeys = new CopyOnWriteArrayList<String>();
        var attempts = new AtomicInteger();
        RotatingGzipS3ObjectWriter.ObjectUploader flaky = (bucket, key, file) -> {
            attemptKeys.add(key);
            if (attempts.incrementAndGet() < 3) {
                return CompletableFuture.failedFuture(new IOException("transient"));
            }
            return CompletableFuture.completedFuture(null);
        };
        try (var w = writer(flaky, RotationPolicy.ofBytes(1), 0)) {   // 0 = retry forever
            var f = w.write(rec("a"));   // rotates immediately; upload retried until the 3rd attempt
            f.get(5, TimeUnit.SECONDS);  // completes only after a successful upload
        }
        assertEquals(3, attempts.get());
        // Every retry used the same key.
        assertTrue(attemptKeys.stream().allMatch(k -> k.equals("p/obj-0.gz")));
    }

    @Test
    void closeUploadsBufferedRemainder() throws Exception {
        var captured = new CopyOnWriteArrayList<Captured>();
        var w = writer(capturing(captured), RotationPolicy.ofBytes(1 << 20), 1);
        w.write(rec("only"));
        w.close();   // flushes the trailing record and awaits the upload
        assertEquals(1, captured.size());
        assertEquals("only\n", decode(captured.get(0).data()));
    }

    @Test
    void flushWithoutRecordsDoesNotUpload() throws Exception {
        var captured = new CopyOnWriteArrayList<Captured>();
        try (var w = writer(capturing(captured), RotationPolicy.ofBytes(1 << 20), 1)) {
            w.flush().get(2, TimeUnit.SECONDS);
        }
        assertEquals(0, captured.size());
    }

    @Test
    void writeAfterCloseFails() {
        var captured = new CopyOnWriteArrayList<Captured>();
        var w = writer(capturing(captured), RotationPolicy.ofBytes(1 << 20), 1);
        w.close();
        assertTrue(w.write(rec("a")).isCompletedExceptionally());
    }

    @Test
    void shouldFlushForAgeReflectsBufferedAgedRecords() throws Exception {
        var captured = new CopyOnWriteArrayList<Captured>();
        try (var w = writer(capturing(captured), new RotationPolicy(0, Duration.ofMillis(1), 0), 1)) {
            assertTrue(!w.hasPendingRecords());
            w.write(rec("a"));
            assertTrue(w.hasPendingRecords());
            Thread.sleep(10);
            assertTrue(w.shouldFlushForAge(), "a buffered record past max-age should be flushable");
            w.flushIfAged().get(2, TimeUnit.SECONDS);
            assertEquals(1, captured.size());
        }
    }
}
