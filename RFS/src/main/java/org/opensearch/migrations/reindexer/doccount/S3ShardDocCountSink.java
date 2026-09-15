package org.opensearch.migrations.reindexer.doccount;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * Per-shard document-count sink backed by S3, streaming to a rotating gzip object via the shared
 * {@link RotatingGzipS3ObjectWriter}.
 *
 * <p>S3 key layout:
 * <pre>
 *   s3://&lt;bucket&gt;/&lt;prefix&gt;session=&lt;sessionId&gt;/worker=&lt;workerId&gt;/shard-doc-counts-&lt;ts&gt;-&lt;seq&gt;.ndjson.gz
 * </pre>
 *
 * <p>One record is written per shard rather than per document, so a single writer per worker
 * suffices; records carry {@code indexName} for downstream grouping. Writer access is marshalled
 * onto one thread so the writer needs no lock and gzip/temp-file I/O stays off the caller.
 *
 * <p>The writer is fail-fast (no upload retry) so a failed upload fails the gating {@link #flush()},
 * leaving the work item incomplete for a successor to re-emit.
 */
@Slf4j
public class S3ShardDocCountSink implements ShardDocCountSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    /** Uncompressed rotation threshold; one record per shard rarely reaches it. */
    public static final long DEFAULT_MAX_BUFFER_BYTES = 8L * 1024 * 1024;

    private final String location;
    private final RotatingGzipS3ObjectWriter<ShardDocCountRecord> writer;
    private final ExecutorService executor;
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    @Builder
    public S3ShardDocCountSink(
        String bucket,
        String prefix,
        String sessionId,
        String workerId,
        String region,
        RotatingGzipS3ObjectWriter.ObjectUploader uploader,
        long maxBufferBytes
    ) {
        var normalizedPrefix = normalizePrefix(prefix);
        var effectiveMaxBufferBytes = maxBufferBytes > 0 ? maxBufferBytes : DEFAULT_MAX_BUFFER_BYTES;
        this.location = "s3://" + bucket + "/" + normalizedPrefix + "session=" + sessionId + "/";
        RotatingGzipS3ObjectWriter.KeyFactory keyFactory = (now, seq) ->
            normalizedPrefix + "session=" + sessionId + "/worker=" + workerId
                + "/shard-doc-counts-" + TIMESTAMP_FORMAT.format(now) + "-" + seq + ".ndjson.gz";
        this.writer = new RotatingGzipS3ObjectWriter<>(
            uploader,
            bucket,
            keyFactory,
            MAPPER::writeValueAsBytes,
            RotationPolicy.ofBytes(effectiveMaxBufferBytes),
            Duration.ZERO,
            1,
            "sdc-");
        this.executor = Executors.newSingleThreadExecutor(makeWorkerThreadFactory(sessionId, workerId));
        log.atDebug().setMessage("shard doc count sink at {} (region={})")
            .addArgument(location).addArgument(region).log();
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.endsWith("/") ? raw : raw + "/";
    }

    @Override
    public Mono<Void> write(ShardDocCountRecord countRecord) {
        if (closeRequested.get()) {
            return Mono.error(new IllegalStateException("S3ShardDocCountSink is closed"));
        }
        var result = new CompletableFuture<Void>();
        runOnWorker(() -> {
            try {
                var objectFuture = writer.write(countRecord);
                // The append is buffered; durability is gated by flush(). A synchronous failure
                // surfaces here and is also retained so the gating flush() fails again.
                if (objectFuture.isCompletedExceptionally()) {
                    objectFuture.whenComplete((v, e) -> result.completeExceptionally(unwrap(e)));
                } else {
                    result.complete(null);
                }
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }, result);
        return Mono.fromFuture(result).onErrorMap(S3ShardDocCountSink::unwrap);
    }

    @Override
    public Mono<Void> flush() {
        var result = new CompletableFuture<Void>();
        runOnWorker(() -> {
            try {
                writer.flush().whenComplete((v, e) -> {
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
        return Mono.fromFuture(result).onErrorMap(S3ShardDocCountSink::unwrap);
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
        try {
            runAndAwaitOnWorker(() -> {
                try {
                    writer.close();
                } catch (Exception e) {
                    log.atWarn().setCause(e).setMessage("Error closing the shard doc count writer").log();
                }
            });
        } finally {
            executor.shutdown();
        }
    }

    private void runOnWorker(Runnable task, CompletableFuture<Void> futureToFailOnReject) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            futureToFailOnReject.completeExceptionally(
                new IllegalStateException("S3ShardDocCountSink is closed", e));
        }
    }

    private void runAndAwaitOnWorker(Runnable task) {
        try {
            executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException e) {
            // already shutting down
        } catch (ExecutionException e) {
            log.atError().setCause(e.getCause())
                .setMessage("Error draining shard doc count sink on close").log();
        }
    }

    private static ThreadFactory makeWorkerThreadFactory(String sessionId, String workerId) {
        return runnable -> {
            var thread = new Thread(runnable, "sdc-sink-worker-" + sessionId + "-" + workerId);
            // Daemon: the WORK_COMPLETED path returns from main() without System.exit, so a
            // non-daemon worker would hang the pod. Durability is gated by flush() before the work
            // item is marked complete, and close() drains the worker synchronously.
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Strip {@link CompletionException} wrappers so callers see the original error. */
    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static RotatingGzipS3ObjectWriter.ObjectUploader s3ClientUploader(S3AsyncClient s3Client) {
        return RotatingGzipS3ObjectWriter.s3ObjectUploader(s3Client);
    }
}
