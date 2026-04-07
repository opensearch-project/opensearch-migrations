package org.opensearch.migrations.bulkload.pipeline;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Polls {@link DocumentMigrationPipeline} for progress on a fixed timer and logs a heartbeat.
 *
 * <p>Follows the replayer's {@code ActiveContextMonitor} pattern: a {@link ScheduledExecutorService}
 * runs at a fixed rate, independent of pipeline throughput. This keeps logging deterministic
 * and out of the reactive chain.
 */
@Slf4j
public class PipelineProgressMonitor implements AutoCloseable {

    private static final long DEFAULT_INTERVAL_MS = 30_000;

    private final DocumentMigrationPipeline pipeline;
    private final ScheduledExecutorService scheduler;
    private final long intervalMs;

    public PipelineProgressMonitor(DocumentMigrationPipeline pipeline) {
        this(pipeline, DEFAULT_INTERVAL_MS);
    }

    public PipelineProgressMonitor(DocumentMigrationPipeline pipeline, long intervalMs) {
        this.pipeline = pipeline;
        this.intervalMs = intervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "pipeline-progress-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    /** Start the periodic heartbeat logging. */
    public void start() {
        scheduler.scheduleAtFixedRate(this::logHeartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    void logHeartbeat() {
        try {
            var snapshot = pipeline.getProgressSnapshot();
            if (snapshot.totalDocs() == 0 && snapshot.currentPartition() == null) {
                return; // nothing started yet
            }
            log.info("Pipeline heartbeat: partition={}, docs={}, bytes={} MB, activeBatches={}/{}, batchesProduced={}, batchesWritten={}, avgReadInterDocUs={}, avgBufferFillMs={}, avgQueueWaitMs={}, avgWriteMs={}",
                snapshot.currentPartition(),
                snapshot.totalDocs(),
                snapshot.totalBytes() / (1024 * 1024),
                snapshot.activeBatches(),
                snapshot.batchConcurrency(),
                snapshot.batchesProduced(),
                snapshot.batchesWritten(),
                snapshot.avgReadInterDocUs(),
                snapshot.avgBufferFillMs(),
                snapshot.avgQueueWaitMs(),
                snapshot.avgWriteMs());
        } catch (Exception e) {
            log.debug("Error in progress monitor heartbeat", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
