package org.opensearch.migrations.reindexer.faileddocumentstream;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.s3sink.RotatingGzipS3ObjectWriter;
import org.opensearch.migrations.s3sink.RotationPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * failed document stream sink backed by S3. Records are split per target index and each index streams
 * to its own rotating gzip S3 object via the shared {@link RotatingGzipS3ObjectWriter} (the same
 * durability-sensitive writer used by the replayer's tuple sink). This class adds the
 * failed-document-stream concerns on top: per-index multiplexing, the {@code session/index/worker} key
 * layout, and the at-least-once gating contract.
 *
 * <p>S3 key layout:
 * <pre>
 *   s3://&lt;bucket&gt;/&lt;prefix&gt;session=&lt;sessionId&gt;/index=&lt;targetIndex&gt;/worker=&lt;workerId&gt;/failed-document-stream-&lt;ts&gt;-&lt;seq&gt;.ndjson.gz
 * </pre>
 *
 * <p>{@link #getLocation()} points at the session prefix ({@code .../session=&lt;sessionId&gt;/}),
 * which contains every index's records for the run.
 *
 * <p><b>Memory bounding:</b> each index's writer stages its gzip to a local temp file and rotates to a
 * fresh S3 object once its accumulated <em>uncompressed</em> size crosses {@code maxBufferBytes}
 * (default {@value #DEFAULT_MAX_BUFFER_BYTES} bytes), so heap stays bounded regardless of how many
 * records fail.
 *
 * <p><b>At-least-once gating:</b> the writer is configured fail-fast (no upload retry). A failed
 * rotation upload makes the gating {@link #flush()} fail too, so the work item is not marked complete
 * and a successor reprocesses the partition and re-emits the failures (deduped downstream).
 */
@Slf4j
public class S3FailedDocumentStreamSink implements FailedDocumentStreamSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    /** Default index segment when a record carries no target index. */
    private static final String UNKNOWN_INDEX = "unknown-index";

    /** Default per-index uncompressed rotation threshold: 64 MiB. */
    public static final long DEFAULT_MAX_BUFFER_BYTES = 64L * 1024 * 1024;

    private final String bucket;
    private final String prefix;
    private final String sessionId;
    private final String workerId;
    private final String location;
    private final RotatingGzipS3ObjectWriter.ObjectUploader uploader;
    private final long maxBufferBytes;

    // Guards the per-index writer map and the closed flag. Bulk writes run concurrently
    // (batchConcurrency defaults to 10) and the per-batch flush runs on the pipeline emission thread,
    // so all access to a writer is serialized through this lock (the writer itself is not internally
    // synchronized). Synchronous uploads happen under the lock; production async uploads return
    // immediately so a slow PutObject doesn't block other writers.
    private final Object lock = new Object();
    private final Map<String, RotatingGzipS3ObjectWriter<FailedDocumentStreamRecord>> writers =
        new LinkedHashMap<>();
    private boolean closed;

    @Builder
    public S3FailedDocumentStreamSink(
        String bucket,
        String prefix,
        String sessionId,
        String workerId,
        String region,
        RotatingGzipS3ObjectWriter.ObjectUploader uploader,
        long maxBufferBytes
    ) {
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        this.sessionId = sessionId;
        this.workerId = workerId;
        this.uploader = uploader;
        // A non-positive value (including the Lombok default for an unset long) means "use default".
        this.maxBufferBytes = maxBufferBytes > 0 ? maxBufferBytes : DEFAULT_MAX_BUFFER_BYTES;
        this.location = "s3://" + bucket + "/" + this.prefix + "session=" + sessionId + "/";
        log.atDebug().setMessage("failed document stream sink at {} (region={})")
            .addArgument(location).addArgument(region).log();
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.endsWith("/") ? raw : raw + "/";
    }

    private RotatingGzipS3ObjectWriter<FailedDocumentStreamRecord> newWriter(String index) {
        RotatingGzipS3ObjectWriter.KeyFactory keyFactory = (now, seq) ->
            prefix + "session=" + sessionId + "/index=" + index + "/worker=" + workerId
                + "/failed-document-stream-" + TIMESTAMP_FORMAT.format(now) + "-" + seq + ".ndjson.gz";
        return new RotatingGzipS3ObjectWriter<>(
            uploader,
            bucket,
            keyFactory,
            MAPPER::writeValueAsBytes,
            RotationPolicy.ofBytes(maxBufferBytes),
            Duration.ZERO,   // no retry delay; fail-fast (attempt count 1) below
            1,               // fail fast: a failed upload blocks the gating flush so a successor reprocesses
            "fds-");
    }

    @Override
    public Mono<Void> write(FailedDocumentStreamRecord failedDocumentStreamRecord) {
        CompletableFuture<Void> objectFuture;
        synchronized (lock) {
            if (closed) {
                return Mono.error(new IllegalStateException("S3FailedDocumentStreamSink is closed"));
            }
            try {
                var index = sanitizeIndex(failedDocumentStreamRecord.getTargetIndex());
                var writer = writers.get(index);
                if (writer == null) {
                    writer = newWriter(index);
                    writers.put(index, writer);
                }
                objectFuture = writer.write(failedDocumentStreamRecord);
            } catch (Exception e) {
                return Mono.error(e);
            }
        }
        // A synchronous (fail-fast) rotation-upload failure surfaces here immediately; the writer also
        // retains it so the gating flush() fails once more. A failed serialization/gzip append surfaces
        // here too but is not retained (no rotation happened), matching per-record error semantics.
        if (objectFuture.isCompletedExceptionally()) {
            try {
                objectFuture.getNow(null);
            } catch (Exception t) {
                return Mono.error(unwrap(t));
            }
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> flush() {
        CompletableFuture<Void> all;
        synchronized (lock) {
            var futures = new ArrayList<CompletableFuture<Void>>(writers.size());
            for (var writer : writers.values()) {
                futures.add(writer.flush());
            }
            all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        }
        return Mono.fromFuture(all).onErrorMap(S3FailedDocumentStreamSink::unwrap);
    }

    @Override
    public String getLocation() {
        return location;
    }

    @Override
    public void close() {
        Map<String, RotatingGzipS3ObjectWriter<FailedDocumentStreamRecord>> toClose;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new LinkedHashMap<>(writers);
        }
        // close() flushes each writer's remainder and waits for in-flight uploads (durability before
        // returning). Errors are logged rather than thrown — close is best-effort cleanup.
        for (var writer : toClose.values()) {
            try {
                writer.close();
            } catch (Exception e) {
                log.atWarn().setCause(e).setMessage("Error closing a failed-document-stream writer").log();
            }
        }
    }

    /**
     * OpenSearch index names cannot contain {@code /}, so they are safe to embed directly
     * in an S3 key; we only guard against a missing/blank index (e.g. a failure emitted
     * before the target index was known).
     */
    private static String sanitizeIndex(String index) {
        return (index == null || index.isBlank()) ? UNKNOWN_INDEX : index;
    }

    /** Strip the {@link CompletionException} wrapper(s) so callers see the original upload/IO error. */
    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Creates an {@link RotatingGzipS3ObjectWriter.ObjectUploader} backed by the given
     * {@link S3AsyncClient}, streaming the staged temp file with {@code AsyncRequestBody.fromFile}.
     */
    public static RotatingGzipS3ObjectWriter.ObjectUploader s3ClientUploader(S3AsyncClient s3Client) {
        return RotatingGzipS3ObjectWriter.s3ObjectUploader(s3Client);
    }
}
