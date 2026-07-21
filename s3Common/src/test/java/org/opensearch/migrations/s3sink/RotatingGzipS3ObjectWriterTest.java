package org.opensearch.migrations.s3sink;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    /** A {@link Clock} whose {@code instant()} tracks a mutable reference, so tests can advance time. */
    private static Clock mutableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
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
    void localAppendFailureIsRetainedAndSurfacesOnGatingFlush() throws Exception {
        // A local append failure (e.g. disk full) is an infrastructure durability failure: the record
        // we were asked to persist is lost, so the failure must be RETAINED and surface on the next
        // flush() — that's what makes the RFS gating flush refuse to mark the work item complete.
        var captured = new CopyOnWriteArrayList<Captured>();
        var failFirstAppend = new AtomicBoolean(true);
        var w = new RotatingGzipS3ObjectWriter<byte[]>(
            capturing(captured), "bucket",
            (now, seq) -> "p/obj-" + seq + ".gz",
            bytes -> bytes,
            RotationPolicy.ofBytes(1 << 20), Duration.ofMillis(10), 1, "writer-test-") {
            @Override
            void appendBytes(byte[] bytes) throws IOException {
                if (failFirstAppend.getAndSet(false)) {
                    throw new IOException("disk full");
                }
                super.appendBytes(bytes);
            }
        };
        try (w) {
            var writeFuture = w.write(rec("a"));
            assertTrue(writeFuture.isCompletedExceptionally(),
                "a retained local append failure should fail the write future");

            var ex = assertThrows(ExecutionException.class, () -> w.flush().get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IOException, "gating flush must re-surface the retained append failure");

            // The stream was rotated to a fresh object after the failure, so a later write still lands.
            w.write(rec("b"));
            w.flush().get(2, TimeUnit.SECONDS);
        }
        // Only "b" made it to S3; "a" was lost with its (surfaced) failure — never silently dropped.
        assertEquals(1, captured.size());
        assertEquals("b\n", decode(captured.get(captured.size() - 1).data()));
    }

    @Test
    void serializationFailureIsNotRetainedSoItCannotBlockGatingFlush() throws Exception {
        // A serialization failure is a per-record "poison pill": a successor would fail to serialize the
        // identical record too, so retaining it would block the gating flush forever. It must surface on
        // the write() but NOT be retained.
        var captured = new CopyOnWriteArrayList<Captured>();
        RotatingGzipS3ObjectWriter.RecordSerializer<String> poison = s -> {
            if ("bad".equals(s)) {
                throw new IOException("cannot serialize");
            }
            return rec(s);
        };
        try (var w = new RotatingGzipS3ObjectWriter<String>(
            capturing(captured), "bucket",
            (now, seq) -> "p/obj-" + seq + ".gz",
            poison,
            RotationPolicy.ofBytes(1 << 20), Duration.ofMillis(10), 1, "writer-test-")) {
            assertTrue(w.write("bad").isCompletedExceptionally(), "serialization failure should fail the write future");
            // The poison record left nothing buffered and nothing retained, so the gating flush passes.
            w.flush().get(2, TimeUnit.SECONDS);
            w.write("good");
            w.flush().get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, captured.size());
        assertEquals("good\n", decode(captured.get(0).data()));
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
        // Drive time explicitly rather than sleeping against a tight max-age: otherwise, on a loaded
        // runner, more than the max-age can elapse between constructing the writer and the first
        // write(), so write() age-rotates the record away before we can observe it as pending.
        var now = new AtomicReference<>(Instant.parse("2024-01-01T00:00:00Z"));
        var maxAge = Duration.ofMillis(1);
        try (var w = new RotatingGzipS3ObjectWriter<byte[]>(
                capturing(captured), "bucket",
                (t, seq) -> "p/obj-" + seq + ".gz",
                bytes -> bytes,
                new RotationPolicy(0, maxAge, 0), Duration.ofMillis(10), 1, "writer-test-",
                mutableClock(now))) {
            assertTrue(!w.hasPendingRecords());
            w.write(rec("a"));   // written at t0 -> not yet aged, so it stays buffered
            assertTrue(w.hasPendingRecords());
            assertTrue(!w.shouldFlushForAge(), "a fresh record must not be flushable for age yet");

            now.set(now.get().plus(maxAge).plusMillis(1));   // advance past max-age
            assertTrue(w.shouldFlushForAge(), "a buffered record past max-age should be flushable");
            w.flushIfAged().get(2, TimeUnit.SECONDS);
            assertEquals(1, captured.size());
        }
    }
}
