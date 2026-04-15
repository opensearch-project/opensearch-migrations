package org.opensearch.migrations.replay.sink;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Writes tuples as gzip-compressed JSON lines directly to S3 via streaming PutObject.
 *
 * <p>Each "file" is an in-flight S3 PutObject upload. Tuples are gzip-compressed and
 * streamed directly to S3 as they arrive — no in-memory buffering of the full object.
 * When the rotation threshold is reached (by uncompressed size, tuple count, or age),
 * the gzip stream is finished and the upload completes. A new upload is started for
 * subsequent tuples.</p>
 *
 * <p>S3 key format: {@code {prefix}{replayerId}/{yyyy/MM/dd/HH}/tuples-{sinkIndex}-{timestamp}-{seq}.log.gz}</p>
 *
 * <p>Each instance is single-threaded (one per Netty event loop). The {@code sinkIndex}
 * is embedded in keys to avoid collisions between concurrent writers.</p>
 */
@Slf4j
public class S3TupleSink implements TupleSink {
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
    private final AtomicLong sequenceCounter = new AtomicLong();

    private OutputStream s3OutputStream;
    private GZIPOutputStream gzipOut;
    private CompletableFuture<Void> uploadFuture;
    private String currentKey;
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
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.prefix = prefix;
        this.replayerId = replayerId;
        this.sinkIndex = sinkIndex;
        this.rotateAfterBytes = rotateAfterBytes;
        this.rotateAfterAge = rotateAfterAge;
        this.rotateAfterTuples = rotateAfterTuples;
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
            rotate();
        }
    }

    @Override
    public void flush() {
        if (pendingFutures.isEmpty()) {
            return;
        }
        rotate();
    }

    @Override
    public void periodicFlush() {
        if (!pendingFutures.isEmpty()) {
            rotate();
        }
    }

    @Override
    public void close() {
        if (gzipOut == null) {
            return;
        }
        if (!pendingFutures.isEmpty()) {
            rotate();
        } else {
            closeCurrentStream();
        }
        gzipOut = null;
        s3OutputStream = null;
    }

    private boolean shouldRotate() {
        return uncompressedBytes >= rotateAfterBytes
            || (rotateAfterTuples > 0 && tupleCount >= rotateAfterTuples)
            || Duration.between(fileOpenedAt, Instant.now()).compareTo(rotateAfterAge) >= 0;
    }

    private void rotate() {
        var key = currentKey;
        var futures = new ArrayList<>(pendingFutures);
        pendingFutures.clear();

        if (!closeCurrentStream()) {
            futures.forEach(f -> f.completeExceptionally(
                new IOException("Failed to finish gzip stream for s3://" + bucket + "/" + key)));
            openNewStream();
            return;
        }

        log.atInfo().setMessage("Completing S3 upload to s3://{}/{}").addArgument(bucket).addArgument(key).log();

        uploadFuture.whenComplete((response, error) -> {
            if (error != null) {
                log.atError().setCause(error).setMessage("Failed to upload to s3://{}/{}")
                    .addArgument(bucket).addArgument(key).log();
                futures.forEach(f -> f.completeExceptionally(error));
            } else {
                futures.forEach(f -> f.complete(null));
            }
        });

        openNewStream();
    }

    /** Finish gzip and close the output stream, signaling upload completion. Returns true on success. */
    private boolean closeCurrentStream() {
        try {
            gzipOut.finish();
            s3OutputStream.close();
            return true;
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to close S3 upload stream").log();
            return false;
        }
    }

    private String buildS3Key() {
        var now = Instant.now();
        var timestamp = TIMESTAMP_FORMAT.format(now);
        var shard = SHARD_FORMAT.format(now);
        var seq = sequenceCounter.getAndIncrement();
        var filename = String.format("tuples-%d-%s-%d.log.gz", sinkIndex, timestamp, seq);
        return prefix + replayerId + "/" + shard + "/" + filename;
    }

    private void openNewStream() {
        currentKey = buildS3Key();
        var requestBody = AsyncRequestBody.forBlockingOutputStream(null);
        var putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(currentKey)
            .contentType("application/gzip")
            .build();
        uploadFuture = s3Client.putObject(putRequest, requestBody)
            .thenApply(r -> null);

        s3OutputStream = requestBody.outputStream();
        try {
            gzipOut = new GZIPOutputStream(s3OutputStream, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create gzip stream for S3 upload", e);
        }
        uncompressedBytes = 0;
        tupleCount = 0;
        fileOpenedAt = Instant.now();
    }
}
