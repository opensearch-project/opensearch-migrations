package org.opensearch.migrations.s3sink;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * A single rotating gzip object stream backed by S3, sharing the durability-sensitive mechanics that
 * used to be duplicated between the replayer's tuple sink and the RFS failed-document-stream sink:
 *
 * <ul>
 *   <li>gzip JSONL output staged to a <em>local temp file</em> (never buffering the whole compressed
 *       object in memory),</li>
 *   <li>rotation by size / age / record count (see {@link RotationPolicy}),</li>
 *   <li>same-key retry on upload failure (so a transient S3 error doesn't change the object key),</li>
 *   <li>flush/close semantics tied to durable upload completion.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * This class is <strong>not</strong> internally synchronized for its stream state. Callers must
 * serialize {@link #write}, {@link #flush}, {@link #flushIfAged} and {@link #close} per instance —
 * the tuple sink does this via its single worker thread, the failed-document-stream sink via its
 * per-index lock. Asynchronous upload callbacks (on SDK / retry-scheduler threads) only touch the
 * thread-safe completion futures and counters, never the stream buffers.
 *
 * <h2>Per-object durability future</h2>
 * Every record written into the current object shares one {@code currentObjectFuture}: {@link #write}
 * returns it so a caller can be notified (or block) when that record is durably uploaded. The tuple
 * sink chains its per-tuple Kafka-completion futures onto it; the failed-document-stream sink uses it
 * only to detect a synchronous rotation-upload failure and otherwise gates on {@link #flush}.
 *
 * <h2>Retry policy</h2>
 * {@code maxUploadAttempts <= 0} means retry the same key indefinitely (the tuple sink's only
 * durability safety net). {@code maxUploadAttempts == 1} means fail fast on the first error — the
 * failed-document-stream sink relies on this so a failed upload blocks its gating flush and a
 * successor work item reprocesses the partition.
 *
 * @param <T> the in-memory record type; serialized to bytes by {@link RecordSerializer}
 */
@Slf4j
// Not final: package-private tests subclass it to override appendBytes(...) and exercise the
// local-append-failure durability path deterministically (see RotatingGzipS3ObjectWriterTest).
public class RotatingGzipS3ObjectWriter<T> implements AutoCloseable {

    /** Serializes one record to the bytes appended (followed by a newline) to the gzip stream. */
    @FunctionalInterface
    public interface RecordSerializer<T> {
        byte[] serialize(T item) throws IOException;
    }

    /** Produces the S3 object key for a newly opened object. */
    @FunctionalInterface
    public interface KeyFactory {
        String nextKey(Instant now, long sequence);
    }

    /** Uploads a staged gzip temp file to {@code s3://bucket/key}. May throw synchronously. */
    @FunctionalInterface
    public interface ObjectUploader {
        CompletableFuture<Void> upload(String bucket, String key, Path gzipFile);
    }

    private final ObjectUploader uploader;
    private final String bucket;
    private final KeyFactory keyFactory;
    private final RecordSerializer<T> serializer;
    private final RotationPolicy rotationPolicy;
    private final Duration uploadRetryDelay;
    private final int maxUploadAttempts;
    private final String tempFilePrefix;
    // Time source for rotation-age decisions; overridable so tests can drive aging deterministically
    // instead of racing wall-clock sleeps against a tight max-age.
    private final Clock clock;

    private final ScheduledExecutorService uploadScheduler;
    private final AtomicLong sequenceCounter = new AtomicLong();
    private final AtomicInteger activeUploads = new AtomicInteger();
    // Upload result futures not yet observed by a flush(). flush() gates on these so a rotation upload
    // that failed earlier in the shard still blocks completion. Touched by rotate()/flush() on the
    // (serialized) caller thread and removed by the async upload callback, so it must be thread-safe.
    private final Set<CompletableFuture<Void>> outstandingUploads = ConcurrentHashMap.newKeySet();

    // Stream state — caller-serialized.
    private OutputStream fileOutputStream;
    private GZIPOutputStream gzipOut;
    private String currentKey;
    private Path currentFile;
    private long uncompressedBytes;
    private long recordCount;
    private Instant openedAt;
    private CompletableFuture<Void> currentObjectFuture;
    private boolean closed;

    public RotatingGzipS3ObjectWriter(
        ObjectUploader uploader,
        String bucket,
        KeyFactory keyFactory,
        RecordSerializer<T> serializer,
        RotationPolicy rotationPolicy,
        Duration uploadRetryDelay,
        int maxUploadAttempts,
        String tempFilePrefix
    ) {
        this(uploader, bucket, keyFactory, serializer, rotationPolicy, uploadRetryDelay,
            maxUploadAttempts, tempFilePrefix, Clock.systemUTC());
    }

    // Package-private: lets tests supply a controllable Clock to exercise age-based rotation
    // deterministically. Production always goes through the public constructor (system UTC clock).
    RotatingGzipS3ObjectWriter(
        ObjectUploader uploader,
        String bucket,
        KeyFactory keyFactory,
        RecordSerializer<T> serializer,
        RotationPolicy rotationPolicy,
        Duration uploadRetryDelay,
        int maxUploadAttempts,
        String tempFilePrefix,
        Clock clock
    ) {
        this.clock = clock;
        this.uploader = uploader;
        this.bucket = bucket;
        this.keyFactory = keyFactory;
        this.serializer = serializer;
        this.rotationPolicy = rotationPolicy;
        this.uploadRetryDelay = uploadRetryDelay;
        this.maxUploadAttempts = maxUploadAttempts;
        this.tempFilePrefix = tempFilePrefix;
        // Only spin up a retry scheduler when retries are actually possible (maxUploadAttempts != 1).
        // The failed-document-stream sink runs fail-fast (one attempt) with one writer PER INDEX, so
        // this avoids an idle daemon thread per index.
        this.uploadScheduler = (maxUploadAttempts == 1) ? null
            : Executors.newSingleThreadScheduledExecutor(
                makeThreadFactory("s3-object-writer-upload-" + tempFilePrefix));
        openNewStream();
    }

    /**
     * Append a record to the current object, rotating (and uploading) first if the rotation policy is
     * met. Returns the durability future for the object this record landed in: it completes when that
     * object is uploaded, or exceptionally if the upload terminally fails.
     */
    public CompletableFuture<Void> write(T item) {
        if (closed) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("RotatingGzipS3ObjectWriter is closed"));
        }
        final byte[] bytes;
        try {
            bytes = serializer.serialize(item);
        } catch (Exception e) {
            // A serialization failure is a per-record ("poison pill") failure: a successor that
            // reprocesses would fail to serialize the same record too, so retrying forever is
            // pointless. Surface it to the caller (which logs + drops) but do NOT retain it, so it
            // never blocks a gating flush.
            return CompletableFuture.failedFuture(e);
        }
        var objectFuture = currentObjectFuture;
        try {
            appendBytes(bytes);
            uncompressedBytes += (long) bytes.length + 1;
            recordCount++;
        } catch (IOException e) {
            // A local append failure (e.g. disk full) is an infrastructure durability failure, not a
            // per-record one: the current object is now corrupt and we've lost a record we were asked
            // to persist. Retain the failed object future so the next flush() surfaces it and the
            // caller's gating flush refuses to mark the work item complete (at-least-once: a successor
            // reprocesses). Discard the corrupt stream and open a fresh one so later writes in the same
            // batch can still be attempted.
            log.atError().setCause(e)
                .setMessage("Failed to append to gzip stream for s3://{}/{}; retaining failure so the "
                    + "next flush surfaces it").addArgument(bucket).addArgument(currentKey).log();
            discardCurrentStream();
            objectFuture.completeExceptionally(e);
            outstandingUploads.add(objectFuture);
            openNewStream();
            return objectFuture;
        }
        if (rotationPolicy.shouldRotate(uncompressedBytes, recordCount, openedAt, clock.instant())) {
            return rotate(true);
        }
        return objectFuture;
    }

    /** Whether the current object has any buffered records (used to skip no-op flushes). */
    public boolean hasPendingRecords() {
        return recordCount > 0;
    }

    /** Whether the current object has buffered records and has reached its max age. */
    public boolean shouldFlushForAge() {
        return recordCount > 0 && rotationPolicy.isAged(openedAt, clock.instant());
    }

    /**
     * Make everything written so far durable: rotate the current object (if non-empty) and return a
     * future that completes once every not-yet-observed upload — including this one and any earlier
     * rotation in the shard — has finished, failing if any of them failed.
     */
    public CompletableFuture<Void> flush() {
        if (recordCount > 0) {
            rotate(true);
        }
        var snapshot = new ArrayList<>(outstandingUploads);
        outstandingUploads.removeAll(snapshot);
        if (snapshot.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(snapshot.toArray(new CompletableFuture[0]));
    }

    /** Rotate the trailing batch only if it has aged out; for callers driving an age-based safety flush. */
    public CompletableFuture<Void> flushIfAged() {
        if (shouldFlushForAge()) {
            return flush();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (gzipOut != null) {
                if (recordCount > 0) {
                    rotate(false);
                } else {
                    closeCurrentStream();
                    deleteFile(currentFile);
                    clearCurrentStream();
                }
            }
            awaitUploadsComplete();
        } finally {
            if (uploadScheduler != null) {
                uploadScheduler.shutdown();
            }
        }
    }

    /** Block (bounded) until in-flight uploads finish, so durability is reached before returning. */
    private void awaitUploadsComplete() {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (activeUploads.get() > 0) {
            if (System.nanoTime() - deadline > 0) {
                log.atError().setMessage("Timed out waiting for {} in-flight S3 uploads to complete on close")
                    .addArgument(activeUploads::get).log();
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.atWarn().setMessage("Interrupted while waiting for S3 uploads to complete on close").log();
                return;
            }
        }
    }

    /**
     * Finish the current gzip object and start its (retrying) upload, optionally opening a fresh
     * object. Returns the durability future for the object that was just closed.
     */
    private CompletableFuture<Void> rotate(boolean openNextStream) {
        var key = currentKey;
        var file = currentFile;
        var objectFuture = currentObjectFuture;

        if (!closeCurrentStream()) {
            deleteFile(file);
            objectFuture.completeExceptionally(
                new IOException("Failed to finish gzip stream for s3://" + bucket + "/" + key));
            if (openNextStream) {
                openNewStream();
            } else {
                clearCurrentStream();
            }
            return objectFuture;
        }

        log.atInfo().setMessage("Completing S3 upload to s3://{}/{}").addArgument(bucket).addArgument(key).log();
        outstandingUploads.add(objectFuture);
        activeUploads.incrementAndGet();
        uploadWithRetries(key, file, objectFuture, 1);

        if (openNextStream) {
            openNewStream();
        } else {
            clearCurrentStream();
        }
        return objectFuture;
    }

    private void uploadWithRetries(String key, Path file, CompletableFuture<Void> objectFuture, int attempt) {
        CompletableFuture<Void> upload;
        try {
            upload = uploader.upload(bucket, key, file);
        } catch (Exception e) {
            upload = CompletableFuture.failedFuture(e);
        }
        upload.whenComplete((response, error) -> {
            if (error == null) {
                deleteFile(file);
                activeUploads.decrementAndGet();
                // A successful upload is durable, so drop it from the gating set immediately to avoid
                // unbounded growth between flushes. A terminal FAILURE is intentionally left in the set
                // so the next flush() observes it once (the fail-fast gating contract).
                outstandingUploads.remove(objectFuture);
                objectFuture.complete(null);
                return;
            }
            boolean outOfAttempts = maxUploadAttempts > 0 && attempt >= maxUploadAttempts;
            if (outOfAttempts) {
                log.atError().setCause(error).setMessage(
                        "Failed to upload object to s3://{}/{} after {} attempt(s); giving up")
                    .addArgument(bucket).addArgument(key).addArgument(attempt).log();
                deleteFile(file);
                activeUploads.decrementAndGet();
                objectFuture.completeExceptionally(unwrap(error));
                return;
            }
            log.atWarn().setCause(error).setMessage(
                    "Failed to upload object to s3://{}/{} on attempt {}; retrying in {} ms")
                .addArgument(bucket).addArgument(key).addArgument(attempt)
                .addArgument(uploadRetryDelay::toMillis).log();
            try {
                uploadScheduler.schedule(
                    () -> uploadWithRetries(key, file, objectFuture, attempt + 1),
                    uploadRetryDelay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception schedulingError) {
                // Scheduler already shut down (post-close) — surface the original failure.
                deleteFile(file);
                activeUploads.decrementAndGet();
                objectFuture.completeExceptionally(unwrap(error));
            }
        });
    }

    private static Throwable unwrap(Throwable error) {
        return (error instanceof java.util.concurrent.CompletionException && error.getCause() != null)
            ? error.getCause() : error;
    }

    // Append one already-serialized record (plus a record separator) to the current gzip stream.
    // Package-private and overridable purely as a test seam for the local-append-failure path;
    // production behavior is exactly the two gzipOut writes.
    void appendBytes(byte[] bytes) throws IOException {
        gzipOut.write(bytes);
        gzipOut.write('\n');
    }

    /** Finish gzip and close the local temp file before upload. Returns true on success. */
    private boolean closeCurrentStream() {
        try {
            gzipOut.finish();
            fileOutputStream.close();
            return true;
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to close S3 upload stream").log();
            return false;
        }
    }

    private void openNewStream() {
        currentKey = keyFactory.nextKey(clock.instant(), sequenceCounter.getAndIncrement());
        try {
            currentFile = Files.createTempFile(tempFilePrefix, ".gz");
            fileOutputStream = Files.newOutputStream(currentFile);
            gzipOut = new GZIPOutputStream(fileOutputStream, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create gzip temp file for S3 upload", e);
        }
        uncompressedBytes = 0;
        recordCount = 0;
        openedAt = clock.instant();
        currentObjectFuture = new CompletableFuture<>();
    }

    private void clearCurrentStream() {
        gzipOut = null;
        fileOutputStream = null;
        currentFile = null;
        currentKey = null;
        recordCount = 0;
        uncompressedBytes = 0;
        currentObjectFuture = null;
    }

    /**
     * Best-effort teardown of a stream that failed mid-append: close the (likely-corrupt) local
     * handles and delete the temp file. Never uploads — the object is incomplete. The caller is
     * responsible for completing/retaining {@code currentObjectFuture} and opening a fresh stream.
     */
    private void discardCurrentStream() {
        var file = currentFile;
        try {
            if (gzipOut != null) {
                gzipOut.close();
            } else if (fileOutputStream != null) {
                fileOutputStream.close();
            }
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to close corrupt gzip stream during discard").log();
        }
        deleteFile(file);
    }

    private void deleteFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to delete S3 temp file {}").addArgument(file).log();
        }
    }

    private static ThreadFactory makeThreadFactory(String name) {
        return runnable -> {
            var thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * An {@link ObjectUploader} backed by a real {@link S3AsyncClient}, streaming the staged temp file
     * with {@code AsyncRequestBody.fromFile} (no in-memory copy of the compressed object).
     */
    public static ObjectUploader s3ObjectUploader(S3AsyncClient s3Client) {
        return (bucket, key, file) -> {
            var request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/gzip")
                .build();
            return s3Client.putObject(request, AsyncRequestBody.fromFile(file)).thenApply(r -> null);
        };
    }
}
