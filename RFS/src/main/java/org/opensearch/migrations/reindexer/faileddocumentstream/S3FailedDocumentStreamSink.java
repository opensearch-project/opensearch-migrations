package org.opensearch.migrations.reindexer.faileddocumentstream;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <h2>Threading</h2>
 * All access to the per-index writers (serialize, gzip append, rotation, flush, close) is marshalled
 * onto a single dedicated worker thread, exactly as the replayer's {@code S3TupleSink}
 * does. This is deliberate: {@link #write} is invoked from
 * {@code OpenSearchClient.compactPendingDocs}/{@code emitRetryExhaustedToFailedDocumentStream}, which
 * run inline on the reactor-netty event-loop thread that delivered the bulk response. Doing the
 * CPU-bound JSON serialization + gzip deflation and the blocking local temp-file I/O on that event
 * loop would stall other in-flight HTTP requests sharing it — precisely when failures are spiking. By
 * hopping to the worker, the event loop is freed and the writers stay single-threaded without a lock.
 * The async S3 upload (and, for other callers, its retries) still runs on SDK / the writer's own
 * scheduler threads.
 *
 * <p><b>Memory bounding:</b> each index's writer stages its gzip to a local temp file and rotates to a
 * fresh S3 object once its accumulated <em>uncompressed</em> size crosses {@code maxBufferBytes}
 * (default {@value #DEFAULT_MAX_BUFFER_BYTES} bytes), so heap stays bounded regardless of how many
 * records fail.
 *
 * <p><b>At-least-once gating:</b> the writer is configured fail-fast (no upload retry). A failed
 * rotation upload — or a failed local append (e.g. disk full) — makes the gating {@link #flush()} fail
 * too, so the work item is not marked complete and a successor reprocesses the partition and re-emits
 * the failures (deduped downstream). A per-record serialization failure ("poison pill") is the one
 * exception: it is logged and dropped rather than retained, because a successor would fail to
 * serialize the identical record too and would otherwise loop forever.
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

    // Owns all writer state. Only ever touched on the single worker thread, so no lock is needed
    // (the writers themselves are not internally synchronized). See the class-level threading note.
    private final Map<String, RotatingGzipS3ObjectWriter<FailedDocumentStreamRecord>> writers =
        new LinkedHashMap<>();
    // Single worker thread that serializes all writer access and keeps the netty event loop free of
    // serialize/gzip/temp-file work. Non-daemon so buffered records still flush on JVM shutdown.
    private final ExecutorService executor;
    // Set on close() so write() rejects late records synchronously rather than racing executor shutdown.
    private final AtomicBoolean closeRequested = new AtomicBoolean();

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
        this.executor = Executors.newSingleThreadExecutor(makeWorkerThreadFactory(sessionId, workerId));
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
        if (closeRequested.get()) {
            return Mono.error(new IllegalStateException("S3FailedDocumentStreamSink is closed"));
        }
        var result = new CompletableFuture<Void>();
        // Eagerly submit the append onto the worker (serialize + gzip happen there, off the event
        // loop). The append task is submitted synchronously from the bulk-response handler, before the
        // batch's Mono completes, so the subsequent per-batch flush() — submitted from concatMap after
        // the batch completes — is guaranteed by the FIFO worker to observe every record it must cover.
        runOnWorker(() -> {
            try {
                var index = sanitizeIndex(failedDocumentStreamRecord.getTargetIndex());
                var writer = writers.computeIfAbsent(index, this::newWriter);
                var objectFuture = writer.write(failedDocumentStreamRecord);
                // write() returns the object's durability future. On success we complete immediately
                // (the append is buffered; durability is gated by flush()), matching the prior
                // return-Mono.empty()-on-append semantics. A synchronous failure (fail-fast rotation
                // upload, or a retained local append IO failure) surfaces here; the writer also retains
                // it so the gating flush() fails once more.
                if (objectFuture.isCompletedExceptionally()) {
                    objectFuture.whenComplete((v, e) -> result.completeExceptionally(unwrap(e)));
                } else {
                    result.complete(null);
                }
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }, result);
        return Mono.fromFuture(result).onErrorMap(S3FailedDocumentStreamSink::unwrap);
    }

    @Override
    public Mono<Void> flush() {
        var result = new CompletableFuture<Void>();
        runOnWorker(() -> {
            try {
                var futures = new ArrayList<CompletableFuture<Void>>(writers.size());
                for (var writer : writers.values()) {
                    futures.add(writer.flush());
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            result.completeExceptionally(unwrap(e));
                        } else {
                            result.complete(null);
                        }
                    });
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }, result);
        return Mono.fromFuture(result).onErrorMap(S3FailedDocumentStreamSink::unwrap);
    }

    @Override
    public String getLocation() {
        return location;
    }

    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }
        // Drain on the worker and block until done: each writer.close() flushes its remainder and waits
        // for in-flight uploads (durability before returning). Errors are logged rather than thrown —
        // close is best-effort cleanup.
        try {
            runAndAwaitOnWorker(() -> {
                for (var writer : writers.values()) {
                    try {
                        writer.close();
                    } catch (Exception e) {
                        log.atWarn().setCause(e).setMessage("Error closing a failed-document-stream writer").log();
                    }
                }
            });
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Submit writer work to the single worker thread; on rejection (post-close) fail the associated
     * future so a caller blocking on the returned Mono never waits forever.
     */
    private void runOnWorker(Runnable task, CompletableFuture<Void> futureToFailOnReject) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            futureToFailOnReject.completeExceptionally(
                new IllegalStateException("S3FailedDocumentStreamSink is closed", e));
        }
    }

    private void runAndAwaitOnWorker(Runnable task) {
        try {
            executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException e) {
            // already shutting down — nothing to drain
        } catch (ExecutionException e) {
            log.atError().setCause(e.getCause())
                .setMessage("Error draining failed-document-stream sink on close").log();
        }
    }

    private static ThreadFactory makeWorkerThreadFactory(String sessionId, String workerId) {
        return runnable -> {
            var thread = new Thread(runnable, "fds-sink-worker-" + sessionId + "-" + workerId);
            // Daemon so it can never keep the JVM alive. RfsMigrateDocuments' happy path
            // (CompletionStatus.WORK_COMPLETED) returns from main() WITHOUT calling System.exit and
            // relies on natural JVM shutdown; a non-daemon worker here would block that exit and hang
            // the pod (which processes one shard per JVM), stalling the backfill. Durability does not
            // depend on this thread outliving main(): each batch's records are made durable by the
            // gating flush() before the work item is marked complete, and close() (shutdown hook, and
            // the explicit close below) drains the worker synchronously. Unlike the replayer's
            // S3TupleSink — which is non-daemon because the replayer always calls close() before exit —
            // RFS has no such guarantee on the WORK_COMPLETED path, so daemon is the safe choice.
            thread.setDaemon(true);
            return thread;
        };
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
