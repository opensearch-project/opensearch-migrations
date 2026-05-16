package org.opensearch.migrations.reindexer.dlq;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Streaming, append-only DLQ sink backed by S3. Mirrors the streaming-gzip pattern
 * established by {@code S3TupleSink}: each "part file" is an in-flight S3
 * {@code PutObject} whose body is written incrementally as gzipped NDJSON.
 *
 * <p>S3 key layout:
 * <pre>
 *   s3://&lt;bucket&gt;/&lt;prefix&gt;session=&lt;sessionId&gt;/worker=&lt;workerId&gt;/dlq-&lt;ts&gt;-&lt;seq&gt;.ndjson.gz
 * </pre>
 *
 * <p>Rotation policy in v1 is intentionally simple — one S3 object per OpenSearch
 * bulk failure batch (i.e. rotate on {@link #flush()}). The byte/count/age
 * thresholds carried in {@link RotationPolicy} are wired through so we can switch
 * to size-based rotation later without changing call sites or the
 * {@link DlqSink} interface.
 *
 * <p>This implementation is single-threaded by contract: it is used from the
 * single worker thread that owns the OpenSearch bulk retry loop.
 */
@Slf4j
public class S3DlqSink implements DlqSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final S3AsyncClient s3Client;
    private final String bucket;
    private final String prefix;
    private final String sessionId;
    private final String workerId;
    private final RotationPolicy policy;
    private final AtomicLong sequenceCounter = new AtomicLong();

    private OutputStream s3OutputStream;
    private GZIPOutputStream gzipOut;
    private CompletableFuture<Void> uploadFuture;
    private String currentKey;
    private long uncompressedBytes;
    private int recordCount;
    private Instant fileOpenedAt;
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();
    private final List<CompletableFuture<Void>> allUploads = new ArrayList<>();
    private boolean closed;

    @Builder
    public S3DlqSink(
        S3AsyncClient s3Client,
        String bucket,
        String prefix,
        String sessionId,
        String workerId,
        RotationPolicy policy
    ) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        this.sessionId = sessionId;
        this.workerId = workerId;
        this.policy = policy != null ? policy : RotationPolicy.onePerFlush();
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.endsWith("/") ? raw : raw + "/";
    }

    @Override
    public synchronized Mono<Void> write(DlqRecord record) {
        if (closed) {
            return Mono.error(new IllegalStateException("S3DlqSink is closed"));
        }
        ensureStreamOpen();
        var future = new CompletableFuture<Void>();
        try {
            byte[] json = mapper.writeValueAsBytes(record);
            gzipOut.write(json);
            gzipOut.write('\n');
            uncompressedBytes += json.length + 1;
            recordCount++;
            pendingFutures.add(future);
        } catch (IOException e) {
            future.completeExceptionally(e);
            return Mono.fromFuture(future);
        }
        if (policy.shouldRotateOnWrite(uncompressedBytes, recordCount, fileOpenedAt)) {
            rotate();
        }
        return Mono.fromFuture(future);
    }

    @Override
    public synchronized Mono<Void> flush() {
        if (pendingFutures.isEmpty() && allUploads.isEmpty()) {
            return Mono.empty();
        }
        if (gzipOut != null && !pendingFutures.isEmpty()) {
            rotate();
        }
        var snapshot = new ArrayList<>(allUploads);
        return Mono.fromFuture(CompletableFuture.allOf(snapshot.toArray(new CompletableFuture[0])));
    }

    @Override
    public String getLocation() {
        return "s3://" + bucket + "/" + prefix + "session=" + sessionId + "/";
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (gzipOut != null && !pendingFutures.isEmpty()) {
            rotate();
        } else if (gzipOut != null) {
            // No buffered records — abandon the empty stream without uploading.
            try {
                gzipOut.close();
            } catch (IOException e) {
                log.atWarn().setCause(e).setMessage("Error closing idle gzip stream during DLQ close").log();
            }
            gzipOut = null;
            s3OutputStream = null;
        }
        try {
            CompletableFuture.allOf(allUploads.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("DLQ uploads did not complete cleanly on close").log();
        }
    }

    private void ensureStreamOpen() {
        if (gzipOut == null) {
            openNewStream();
        }
    }

    private void rotate() {
        var key = currentKey;
        var futures = new ArrayList<>(pendingFutures);
        pendingFutures.clear();

        if (!closeCurrentStream()) {
            var err = new IOException("Failed to finish gzip stream for s3://" + bucket + "/" + key);
            futures.forEach(f -> f.completeExceptionally(err));
            return;
        }

        log.atInfo().setMessage("Completing DLQ upload to s3://{}/{} ({} records)")
            .addArgument(bucket).addArgument(key).addArgument(futures.size()).log();

        var thisUpload = uploadFuture;
        allUploads.add(thisUpload);
        thisUpload.whenComplete((response, error) -> {
            if (error != null) {
                log.atError().setCause(error).setMessage("Failed to upload DLQ object s3://{}/{}")
                    .addArgument(bucket).addArgument(key).log();
                futures.forEach(f -> f.completeExceptionally(error));
            } else {
                futures.forEach(f -> f.complete(null));
            }
        });
    }

    private boolean closeCurrentStream() {
        try {
            gzipOut.finish();
            s3OutputStream.close();
            gzipOut = null;
            s3OutputStream = null;
            return true;
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to close DLQ S3 upload stream").log();
            return false;
        }
    }

    private String buildS3Key() {
        var timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        var seq = sequenceCounter.getAndIncrement();
        return prefix + "session=" + sessionId + "/worker=" + workerId
            + "/dlq-" + timestamp + "-" + seq + ".ndjson.gz";
    }

    private void openNewStream() {
        currentKey = buildS3Key();
        var requestBody = AsyncRequestBody.forBlockingOutputStream(null);
        var putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(currentKey)
            .contentType("application/gzip")
            .build();
        uploadFuture = s3Client.putObject(putRequest, requestBody).thenApply(r -> null);

        s3OutputStream = requestBody.outputStream();
        try {
            gzipOut = new GZIPOutputStream(s3OutputStream, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create gzip stream for DLQ S3 upload", e);
        }
        uncompressedBytes = 0;
        recordCount = 0;
        fileOpenedAt = Instant.now();
    }

    /**
     * Rotation control knob. v1 default is {@link #onePerFlush()} — every {@code flush()}
     * (i.e. every OpenSearch bulk-failure batch) becomes its own object. Use
     * {@link #builder()} for size/count/age thresholds when we want to amortize
     * small failure bursts into larger objects.
     */
    @Value
    @Builder
    public static class RotationPolicy {
        long rotateAfterBytes;
        int rotateAfterRecords;
        Duration rotateAfterAge;

        public static RotationPolicy onePerFlush() {
            return RotationPolicy.builder()
                .rotateAfterBytes(Long.MAX_VALUE)
                .rotateAfterRecords(Integer.MAX_VALUE)
                .rotateAfterAge(Duration.ofDays(365))
                .build();
        }

        boolean shouldRotateOnWrite(long bytes, int records, Instant openedAt) {
            if (rotateAfterBytes > 0 && bytes >= rotateAfterBytes) return true;
            if (rotateAfterRecords > 0 && records >= rotateAfterRecords) return true;
            if (rotateAfterAge != null && openedAt != null
                && Duration.between(openedAt, Instant.now()).compareTo(rotateAfterAge) >= 0) {
                return true;
            }
            return false;
        }
    }
}
