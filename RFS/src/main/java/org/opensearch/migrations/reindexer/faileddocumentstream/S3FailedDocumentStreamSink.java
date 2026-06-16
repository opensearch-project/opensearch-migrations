package org.opensearch.migrations.reindexer.faileddocumentstream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * failed document stream sink backed by S3. Buffers gzipped NDJSON records in memory and uploads
 * completed objects to S3 on {@link #flush()} and {@link #close()}.
 *
 * <p>For testing, an {@link S3Uploader} can be injected instead of a real
 * {@link S3AsyncClient} to capture uploads in-process.
 *
 * <p>S3 key layout:
 * <pre>
 *   s3://&lt;bucket&gt;/&lt;prefix&gt;session=&lt;sessionId&gt;/index=&lt;targetIndex&gt;/worker=&lt;workerId&gt;/failed-document-stream-&lt;ts&gt;-&lt;seq&gt;.ndjson.gz
 * </pre>
 *
 * <p>Records are buffered per target index and one S3 object is uploaded per index on
 * each flush, so the {@code index=&lt;targetIndex&gt;} segment in the key always matches the
 * documents inside that object. {@link #getLocation()} still points at the session prefix
 * ({@code .../session=&lt;sessionId&gt;/}), which contains every index's records for the run.
 *
 * <p><b>Memory bounding:</b> to avoid unbounded heap growth when a single shard produces a
 * very large number of terminal failures, each index buffer is rotated to a fresh S3 object
 * once its accumulated <em>uncompressed</em> size crosses {@code maxBufferBytes} (default
 * {@value #DEFAULT_MAX_BUFFER_BYTES} bytes). Rotation uploads inline during {@link #write(FailedDocumentStreamRecord)},
 * so in-memory buffering stays bounded by roughly {@code maxBufferBytes} per active index
 * regardless of how many records fail. The per-shard {@link #flush()} still uploads whatever
 * remains below the threshold, preserving the durability-before-complete contract. If a
 * rotation upload fails mid-shard, the error is retained so the next {@link #flush()} also
 * fails — the work item is not marked complete and a successor reprocesses the partition.
 */
@Slf4j
public class S3FailedDocumentStreamSink implements FailedDocumentStreamSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    @FunctionalInterface
    public interface S3Uploader {
        void upload(String s3Uri, byte[] data, String region) throws IOException;
    }

    private final String bucket;
    private final String prefix;
    private final String sessionId;
    private final String workerId;
    private final String region;
    private final String location;
    private final S3Uploader uploader;
    private final AtomicLong sequenceCounter = new AtomicLong();

    /** Default index segment when a record carries no target index. */
    private static final String UNKNOWN_INDEX = "unknown-index";

    /** Default per-index in-memory rotation threshold (uncompressed bytes): 64 MiB. */
    public static final long DEFAULT_MAX_BUFFER_BYTES = 64L * 1024 * 1024;

    /** One in-memory gzip buffer per target index, flushed to a distinct S3 object. */
    private static final class IndexBuffer {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final GZIPOutputStream gzipOut;
        /** Uncompressed bytes written so far; drives rotation independent of compression ratio. */
        long uncompressedBytes;

        IndexBuffer() throws IOException {
            gzipOut = new GZIPOutputStream(buffer);
        }
    }

    // Guards buffers/pendingUploadError/closed. Bulk writes run concurrently (batchConcurrency
    // defaults to 10) and the per-batch flush runs on the pipeline emission thread, so buffer
    // mutation must be synchronized. S3 uploads are intentionally performed OUTSIDE this lock
    // (on a buffer already removed from the map) so a slow PutObject never blocks other writers.
    private final Object lock = new Object();
    // Keyed by target index, insertion-ordered so flush uploads are deterministic.
    private final Map<String, IndexBuffer> buffers = new LinkedHashMap<>();
    private final long maxBufferBytes;
    // First rotation-upload failure, if any. Retained so the gating flush() also fails and
    // the work item is not marked complete (a successor then reprocesses and re-emits).
    private Throwable pendingUploadError;
    private boolean closed;

    @Builder
    public S3FailedDocumentStreamSink(
        String bucket,
        String prefix,
        String sessionId,
        String workerId,
        String region,
        S3Uploader uploader,
        long maxBufferBytes
    ) {
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        this.sessionId = sessionId;
        this.workerId = workerId;
        this.region = region;
        this.uploader = uploader;
        // A non-positive value (including the Lombok default for an unset long) means "use default".
        this.maxBufferBytes = maxBufferBytes > 0 ? maxBufferBytes : DEFAULT_MAX_BUFFER_BYTES;
        this.location = "s3://" + bucket + "/" + this.prefix + "session=" + sessionId + "/";
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.endsWith("/") ? raw : raw + "/";
    }

    @Override
    public Mono<Void> write(FailedDocumentStreamRecord failedDocumentStreamRecord) {
        // Buffer mutation under the lock; capture a buffer to rotate if the cap is crossed.
        String rotateIndex = null;
        IndexBuffer rotateBuffer = null;
        synchronized (lock) {
            if (closed) {
                return Mono.error(new IllegalStateException("S3FailedDocumentStreamSink is closed"));
            }
            try {
                var index = sanitizeIndex(failedDocumentStreamRecord.getTargetIndex());
                var indexBuffer = buffers.get(index);
                if (indexBuffer == null) {
                    indexBuffer = new IndexBuffer();
                    buffers.put(index, indexBuffer);
                }
                byte[] json = MAPPER.writeValueAsBytes(failedDocumentStreamRecord);
                indexBuffer.gzipOut.write(json);
                indexBuffer.gzipOut.write('\n');
                indexBuffer.uncompressedBytes += (long) json.length + 1;
                // Rotate to a fresh S3 object once this index's buffer crosses the cap, so a
                // shard with a huge number of failures can't grow the heap without bound.
                if (indexBuffer.uncompressedBytes >= maxBufferBytes) {
                    rotateIndex = index;
                    rotateBuffer = buffers.remove(index);
                }
            } catch (IOException e) {
                return Mono.error(e);
            }
        }
        // Upload the rotated object outside the lock (it's been removed from the map, so no
        // other thread can touch it). A failure is recorded so the gating flush() also fails.
        if (rotateBuffer != null) {
            try {
                uploadBuffer(rotateIndex, rotateBuffer);
            } catch (Exception e) {
                synchronized (lock) {
                    if (pendingUploadError == null) {
                        pendingUploadError = e;
                    }
                }
                return Mono.error(e);
            }
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> flush() {
        // Drain the buffers and any prior rotation error under the lock; a failed upload then
        // discards those in-memory records (mirroring the prior single-buffer behavior). The
        // work-item lease then expires and a successor reprocesses the partition, re-emitting
        // the failures. Uploads run outside the lock so concurrent writes aren't blocked on S3.
        Map<String, IndexBuffer> pending;
        Throwable priorError;
        synchronized (lock) {
            pending = new LinkedHashMap<>(buffers);
            buffers.clear();
            // Consume any prior rotation failure here so it blocks completion exactly once and a
            // later shard reusing this sink isn't penalized.
            priorError = pendingUploadError;
            pendingUploadError = null;
        }

        for (var entry : pending.entrySet()) {
            try {
                uploadBuffer(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                return Mono.error(e);
            }
        }
        // A rotation upload that failed earlier in this shard must still block completion.
        if (priorError != null) {
            return Mono.error(priorError);
        }
        return Mono.empty();
    }

    /**
     * Finish the gzip stream for one index buffer and upload it as a single S3 object.
     * Propagates any failure to the caller (gzip {@link IOException} or an uploader error)
     * after logging it.
     */
    private void uploadBuffer(String index, IndexBuffer indexBuffer) throws IOException {
        indexBuffer.gzipOut.finish();
        indexBuffer.gzipOut.close();
        byte[] data = indexBuffer.buffer.toByteArray();
        var s3Uri = "s3://" + bucket + "/" + buildS3Key(index);
        try {
            uploader.upload(s3Uri, data, region);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Failed to upload failed document stream object {}").addArgument(s3Uri).log();
            throw e;
        }
        log.atInfo().setMessage("failed document stream upload complete: {} ({} bytes)")
            .addArgument(s3Uri).addArgument(data.length).log();
    }

    @Override
    public String getLocation() {
        return location;
    }

    @Override
    public void close() {
        boolean needsFlush;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            needsFlush = !buffers.isEmpty() || pendingUploadError != null;
        }
        // flush() reacquires the lock; call it outside our critical section to keep the
        // S3 upload off the lock.
        if (needsFlush) {
            flush().block();
        }
    }

    private String buildS3Key(String index) {
        var timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        var seq = sequenceCounter.getAndIncrement();
        return prefix + "session=" + sessionId + "/index=" + index + "/worker=" + workerId
            + "/failed-document-stream-" + timestamp + "-" + seq + ".ndjson.gz";
    }

    /**
     * OpenSearch index names cannot contain {@code /}, so they are safe to embed directly
     * in an S3 key; we only guard against a missing/blank index (e.g. a failure emitted
     * before the target index was known).
     */
    private static String sanitizeIndex(String index) {
        return (index == null || index.isBlank()) ? UNKNOWN_INDEX : index;
    }

    /**
     * Creates an {@link S3Uploader} backed by the given {@link S3AsyncClient}.
     * Parses the {@code s3://bucket/key} URI and issues a blocking PutObject.
     */
    public static S3Uploader s3ClientUploader(S3AsyncClient s3Client) {
        return (s3Uri, data, region) -> {
            var stripped = s3Uri.replaceFirst("^s3://", "");
            var slashIdx = stripped.indexOf('/');
            var putBucket = stripped.substring(0, slashIdx);
            var putKey = stripped.substring(slashIdx + 1);
            var request = PutObjectRequest.builder()
                .bucket(putBucket)
                .key(putKey)
                .contentType("application/gzip")
                .build();
            s3Client.putObject(request, AsyncRequestBody.fromBytes(data)).join();
        };
    }
}
