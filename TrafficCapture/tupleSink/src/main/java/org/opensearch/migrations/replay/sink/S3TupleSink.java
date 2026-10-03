package org.opensearch.migrations.replay.sink;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.s3sink.RotatingGzipS3ObjectWriter;
import org.opensearch.migrations.s3sink.RotationPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * Writes tuples as gzip-compressed JSON lines to S3.
 *
 * <p>The durability-sensitive mechanics — temp-file staging, gzip close, size/age/count rotation,
 * same-key upload retries, and upload-completion-tied flush/close — live in the shared
 * {@link RotatingGzipS3ObjectWriter} (also used by the RFS failed-document-stream sink). This class
 * adds the replayer-specific concerns: tuple serialization, the per-tuple completion future that
 * drives Kafka offset commits, and a single worker thread that both serializes writer access and
 * self-schedules an age-based flush of trailing tuples.</p>
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
    private final int sinkIndex;
    // Tuples are pre-serialized on the calling thread, so the shared writer just appends the bytes.
    private final RotatingGzipS3ObjectWriter<byte[]> writer;
    // Single work thread that owns all serialized access to the writer (write / flush / close) and
    // self-schedules the age-based flush. Because every writer mutation runs on this one thread, no
    // lock is needed and the Netty event loop is freed from gzip/serialize work. (The async S3 upload
    // and its retries run on SDK / the writer's own scheduler threads.)
    private final ScheduledExecutorService executor;
    // Set on close() so accept() rejects late writes synchronously (rather than racing the executor
    // shutdown), preserving the explicit IllegalStateException behavior from the accept-after-close fix.
    private final AtomicBoolean closeRequested = new AtomicBoolean();

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
        this.sinkIndex = sinkIndex;
        RotatingGzipS3ObjectWriter.KeyFactory keyFactory = (now, seq) ->
            prefix + replayerId + "/" + SHARD_FORMAT.format(now) + "/"
                + String.format("tuples-%d-%s-%d.log.gz", sinkIndex, TIMESTAMP_FORMAT.format(now), seq);
        this.writer = new RotatingGzipS3ObjectWriter<>(
            RotatingGzipS3ObjectWriter.s3ObjectUploader(s3Client),
            bucket,
            keyFactory,
            bytes -> bytes,   // tuples are already serialized on the calling thread
            new RotationPolicy(rotateAfterBytes, rotateAfterAge, rotateAfterTuples),
            uploadRetryDelay,
            0,                // retry the same key indefinitely: the replayer has no other durability net
            "tuple-sink-" + sinkIndex + "-"
        );
        this.executor = Executors.newSingleThreadScheduledExecutor(makeWorkerThreadFactory());
        // Self-scheduled age flush: re-checks file age on its own thread so a sink that stops
        // receiving tuples still rotates its trailing batch (otherwise those tuple futures never
        // complete and the replayer's Kafka offset never advances). Cadence is a fraction of the
        // max age so the worst-case extra latency past max-age is bounded. Min 1s floor avoids a
        // busy schedule for tiny test ages.
        var flushPeriodMs = Math.max(1000L, rotateAfterAge.toMillis() / 2);
        executor.scheduleAtFixedRate(
            this::periodicFlushOnWorker, flushPeriodMs, flushPeriodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
        if (closeRequested.get()) {
            future.completeExceptionally(new IllegalStateException("S3TupleSink is closed"));
            return;
        }
        // Serialize the tuple on the calling (event-loop) thread so a serialization failure can
        // be reported synchronously and the tupleMap isn't retained across threads. The actual
        // buffer write is marshalled onto the worker thread.
        final byte[] json;
        try {
            json = mapper.writeValueAsBytes(tupleMap);
        } catch (IOException e) {
            future.completeExceptionally(e);
            return;
        }
        runOnWorker(() -> {
            // The returned future completes when the object containing this tuple is durably uploaded;
            // chain the caller's future to it so the Kafka offset only advances after durable upload.
            writer.write(json).whenComplete((v, e) -> {
                if (e != null) {
                    future.completeExceptionally(e);
                } else {
                    future.complete(null);
                }
            });
        }, future);
    }

    @Override
    public void flush() {
        runOnWorker(writer::flush, null);
    }

    /** Age-driven safety flush; runs on the worker thread (self-scheduled in the constructor). */
    private void periodicFlushOnWorker() {
        writer.flushIfAged();
    }

    @Override
    public void close() {
        // Drain on the worker thread and block until done: writer.close() flushes the trailing batch
        // and waits for in-flight uploads (so pending tuple futures complete, triggering Kafka offset
        // commits) before the JVM exits. Without this, the replayer would re-deliver already-processed
        // messages on the next startup.
        closeRequested.set(true);
        try {
            runAndAwaitOnWorker(writer::close);
        } finally {
            executor.shutdown();
        }
    }

    /** Submit work to the single worker thread; on rejection (post-close) fail the associated
     * future if any, so callers never wait forever. */
    private void runOnWorker(Runnable task, CompletableFuture<Void> futureToFailOnReject) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            if (futureToFailOnReject != null) {
                futureToFailOnReject.completeExceptionally(e);
            }
        }
    }

    private void runAndAwaitOnWorker(Runnable task) {
        try {
            executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException e) {
            // already shutting down — nothing to drain
        } catch (java.util.concurrent.ExecutionException e) {
            log.atError().setCause(e.getCause()).setMessage("Error draining S3 tuple sink on close").log();
        }
    }

    private ThreadFactory makeWorkerThreadFactory() {
        return runnable -> {
            var thread = new Thread(runnable, "s3-tuple-sink-worker-" + sinkIndex);
            thread.setDaemon(false);
            return thread;
        };
    }
}
