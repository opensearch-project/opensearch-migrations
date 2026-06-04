package org.opensearch.migrations.replay.sink;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Writes tuples as gzip-compressed JSON lines to S3.
 *
 * <p>Each "file" is first written to a local temp file and then uploaded with a
 * single PutObject request when rotation is reached. This avoids buffering the full
 * compressed object in memory and keeps the sink compatible with the standard
 * {@link S3AsyncClient}, whose blocking stream request body can deadlock when opened
 * from a single-threaded event loop.</p>
 *
 * <p>S3 key format: {@code {prefix}{replayerId}/{yyyy/MM/dd/HH}/tuples-{sinkIndex}-{timestamp}-{seq}.log.gz}</p>
 *
 * <p>Each instance is single-threaded (one per Netty event loop). The {@code sinkIndex}
 * is embedded in keys to avoid collisions between concurrent writers.</p>
 */
@Slf4j
public class S3TupleSink implements TupleSink {
    static final Duration DEFAULT_UPLOAD_RETRY_DELAY = Duration.ofSeconds(10);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHARD_FORMAT =
        DateTimeFormatter.ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final S3AsyncClient s3Client;
    private final String bucket;
    private final String prefix;
    private final String replayerId;
    private final int sinkIndex;
    private final long rotateAfterBytes;
    private final Duration rotateAfterAge;
    private final int rotateAfterTuples;
    private final Duration uploadRetryDelay;
    private final ScheduledExecutorService retryExecutor;
    private final AtomicInteger activeUploads = new AtomicInteger();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicLong sequenceCounter = new AtomicLong();

    private OutputStream fileOutputStream;
    private GZIPOutputStream gzipOut;
    private String currentKey;
    private Path currentFile;
    private long uncompressedBytes;
    private int tupleCount;
    private Instant fileOpenedAt;
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();

    public S3TupleSink(
        S3AsyncClient s3Client,
        String bucket,
        String prefix,
        String replayerId,
        int sinkIndex,
        long rotateAfterBytes,
        Duration rotateAfterAge,
        int rotateAfterTuples
    ) {
        this(
            s3Client,
            bucket,
            prefix,
            replayerId,
            sinkIndex,
            rotateAfterBytes,
            rotateAfterAge,
            rotateAfterTuples,
            DEFAULT_UPLOAD_RETRY_DELAY
        );
    }

    S3TupleSink(
        S3AsyncClient s3Client,
        String bucket,
        String prefix,
        String replayerId,
        int sinkIndex,
        long rotateAfterBytes,
        Duration rotateAfterAge,
        int rotateAfterTuples,
        Duration uploadRetryDelay
    ) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.prefix = prefix;
        this.replayerId = replayerId;
        this.sinkIndex = sinkIndex;
        this.rotateAfterBytes = rotateAfterBytes;
        this.rotateAfterAge = rotateAfterAge;
        this.rotateAfterTuples = rotateAfterTuples;
        this.uploadRetryDelay = uploadRetryDelay;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(makeRetryThreadFactory());
        openNewStream();
    }

    @Override
    public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
        try {
            byte[] json = mapper.writeValueAsBytes(tupleMap);
            gzipOut.write(json);
            gzipOut.write('\n');
            uncompressedBytes += json.length + 1;
            tupleCount++;
            pendingFutures.add(future);
        } catch (IOException e) {
            future.completeExceptionally(e);
            return;
        }
        if (shouldRotate()) {
            rotate(true);
        }
    }

    @Override
    public void flush() {
        if (pendingFutures.isEmpty()) {
            return;
        }
        rotate(true);
    }

    @Override
    public void periodicFlush() {
        if (!pendingFutures.isEmpty()) {
            rotate(true);
        }
    }

    @Override
    public void close() {
        closeRequested.set(true);
        if (gzipOut == null) {
            shutdownRetryExecutorIfDone();
            return;
        }
        if (!pendingFutures.isEmpty()) {
            rotate(false);
        } else {
            closeCurrentStream();
            deleteFile(currentFile);
            clearCurrentStream();
        }
        shutdownRetryExecutorIfDone();
    }

    private boolean shouldRotate() {
        return uncompressedBytes >= rotateAfterBytes
            || (rotateAfterTuples > 0 && tupleCount >= rotateAfterTuples)
            || Duration.between(fileOpenedAt, Instant.now()).compareTo(rotateAfterAge) >= 0;
    }

    private void rotate(boolean openNextStream) {
        var key = currentKey;
        var futures = new ArrayList<>(pendingFutures);
        pendingFutures.clear();

        var file = currentFile;
        if (!closeCurrentStream()) {
            deleteFile(file);
            futures.forEach(f -> f.completeExceptionally(
                new IOException("Failed to finish gzip stream for s3://" + bucket + "/" + key)));
            if (openNextStream) {
                openNewStream();
            } else {
                clearCurrentStream();
            }
            return;
        }

        log.atInfo().setMessage("Completing S3 upload to s3://{}/{}").addArgument(bucket).addArgument(key).log();

        activeUploads.incrementAndGet();
        uploadFileWithRetries(key, file, futures, 1);

        if (openNextStream) {
            openNewStream();
        } else {
            clearCurrentStream();
        }
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

    private String buildS3Key() {
        // Keep time/sequence-based object names for now. Once Kafka identity is threaded
        // into this sink, prefer keys or metadata that include partition/offset ranges
        // plus a stable run id so downstream consumers can dedupe replay attempts.
        var now = Instant.now();
        var timestamp = TIMESTAMP_FORMAT.format(now);
        var shard = SHARD_FORMAT.format(now);
        var seq = sequenceCounter.getAndIncrement();
        var filename = String.format("tuples-%d-%s-%d.log.gz", sinkIndex, timestamp, seq);
        return prefix + replayerId + "/" + shard + "/" + filename;
    }

    private void openNewStream() {
        currentKey = buildS3Key();
        try {
            currentFile = Files.createTempFile("tuple-sink-" + sinkIndex + "-", ".log.gz");
            fileOutputStream = Files.newOutputStream(currentFile);
            gzipOut = new GZIPOutputStream(fileOutputStream, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create gzip temp file for S3 upload", e);
        }
        uncompressedBytes = 0;
        tupleCount = 0;
        fileOpenedAt = Instant.now();
    }

    private void clearCurrentStream() {
        gzipOut = null;
        fileOutputStream = null;
        currentFile = null;
        currentKey = null;
    }

    private void uploadFileWithRetries(
        String key,
        Path file,
        List<CompletableFuture<Void>> futures,
        int attempt
    ) {
        uploadFile(key, file).whenComplete((response, error) -> {
            if (error == null) {
                deleteFile(file);
                futures.forEach(f -> f.complete(null));
                activeUploads.decrementAndGet();
                shutdownRetryExecutorIfDone();
                return;
            }

            log.atWarn().setCause(error).setMessage(
                    "Failed to upload tuple file to s3://{}/{} on attempt {}; retrying in {} ms")
                .addArgument(bucket)
                .addArgument(key)
                .addArgument(attempt)
                .addArgument(uploadRetryDelay::toMillis)
                .log();
            retryExecutor.schedule(
                () -> uploadFileWithRetries(key, file, futures, attempt + 1),
                uploadRetryDelay.toMillis(),
                TimeUnit.MILLISECONDS
            );
        });
    }

    private CompletableFuture<Void> uploadFile(String key, Path file) {
        var putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("application/gzip")
            .build();
        return s3Client.putObject(putRequest, AsyncRequestBody.fromFile(file))
            .thenApply(r -> null);
    }

    private ThreadFactory makeRetryThreadFactory() {
        return runnable -> {
            var thread = new Thread(runnable, "s3-tuple-sink-retry-" + sinkIndex);
            thread.setDaemon(false);
            return thread;
        };
    }

    private void shutdownRetryExecutorIfDone() {
        if (closeRequested.get() && activeUploads.get() == 0) {
            retryExecutor.shutdown();
        }
    }

    private void deleteFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to delete S3 tuple temp file {}")
                .addArgument(file).log();
        }
    }
}
