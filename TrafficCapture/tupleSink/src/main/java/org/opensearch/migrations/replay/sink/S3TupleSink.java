package org.opensearch.migrations.replay.sink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * Writes tuples as gzip-compressed JSON lines directly to S3 via PutObject.
 *
 * <p>Tuples accumulate in an in-memory gzip buffer until either the uncompressed byte
 * threshold ({@code maxFileSizeBytes}), the tuple count threshold ({@code maxTuplesPerFile}),
 * or the time threshold ({@code maxFileAge}) is reached, at which point the buffer is
 * uploaded to S3 as a single object. Pending futures are completed when the upload succeeds.</p>
 *
 * <p>S3 key format: {@code {prefix}{replayerId}/{yyyy/MM/dd/HH}/tuples-{sinkIndex}-{timestamp}-{seq}.log.gz}</p>
 *
 * <p>Each instance is single-threaded (one per Netty event loop). The {@code sinkIndex}
 * is embedded in filenames to avoid collisions between concurrent writers.</p>
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
    private final long maxFileSizeBytes;
    private final Duration maxFileAge;
    private final int maxTuplesPerFile;
    private final AtomicLong sequenceCounter = new AtomicLong();

    private ByteArrayOutputStream byteBuffer;
    private GZIPOutputStream gzipOut;
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
        long maxFileSizeBytes,
        Duration maxFileAge,
        int maxTuplesPerFile
    ) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.prefix = prefix;
        this.replayerId = replayerId;
        this.sinkIndex = sinkIndex;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxFileAge = maxFileAge;
        this.maxTuplesPerFile = maxTuplesPerFile;
        openNewBuffer();
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
        }
        gzipOut = null;
        byteBuffer = null;
    }

    private boolean shouldRotate() {
        return uncompressedBytes >= maxFileSizeBytes
            || (maxTuplesPerFile > 0 && tupleCount >= maxTuplesPerFile)
            || Duration.between(fileOpenedAt, Instant.now()).compareTo(maxFileAge) >= 0;
    }

    private void rotate() {
        byte[] data;
        try {
            gzipOut.finish();
            data = byteBuffer.toByteArray();
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to finish gzip buffer").log();
            completeAllExceptionally(e);
            openNewBuffer();
            return;
        }

        var key = buildS3Key();
        var request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("application/gzip")
            .contentLength((long) data.length)
            .build();

        var futures = new ArrayList<>(pendingFutures);
        pendingFutures.clear();

        log.atInfo().setMessage("Uploading {} bytes to s3://{}/{}").addArgument(data.length)
            .addArgument(bucket).addArgument(key).log();

        s3Client.putObject(request, AsyncRequestBody.fromBytes(data))
            .whenComplete((response, error) -> {
                if (error != null) {
                    log.atError().setCause(error).setMessage("Failed to upload to s3://{}/{}")
                        .addArgument(bucket).addArgument(key).log();
                    futures.forEach(f -> f.completeExceptionally(error));
                } else {
                    futures.forEach(f -> f.complete(null));
                }
            });

        openNewBuffer();
    }

    private String buildS3Key() {
        var now = Instant.now();
        var timestamp = TIMESTAMP_FORMAT.format(now);
        var shard = SHARD_FORMAT.format(now);
        var seq = sequenceCounter.getAndIncrement();
        var filename = String.format("tuples-%d-%s-%d.log.gz", sinkIndex, timestamp, seq);
        return prefix + replayerId + "/" + shard + "/" + filename;
    }

    private void openNewBuffer() {
        byteBuffer = new ByteArrayOutputStream();
        try {
            gzipOut = new GZIPOutputStream(byteBuffer, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create gzip stream", e);
        }
        uncompressedBytes = 0;
        tupleCount = 0;
        fileOpenedAt = Instant.now();
    }

    private void completeAllExceptionally(Throwable t) {
        pendingFutures.forEach(f -> f.completeExceptionally(t));
        pendingFutures.clear();
    }
}
