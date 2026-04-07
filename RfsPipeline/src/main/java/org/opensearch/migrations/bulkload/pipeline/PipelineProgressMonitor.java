package org.opensearch.migrations.bulkload.pipeline;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Polls {@link DocumentMigrationPipeline} for progress on a fixed timer and logs a heartbeat.
 *
 * <p>Diffs consecutive snapshots to show per-interval timing breakdown: what percentage
 * of aggregate batch time was spent reading, queuing, and writing. This shows the steady-state
 * bottleneck, not lifetime averages.
 */
@Slf4j
public class PipelineProgressMonitor implements AutoCloseable {

    private static final long DEFAULT_INTERVAL_MS = 30_000;

    private final DocumentMigrationPipeline pipeline;
    private final ScheduledExecutorService scheduler;
    private final long intervalMs;

    // Previous snapshot for diffing
    private volatile DocumentMigrationPipeline.ProgressSnapshot prev;

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
        prev = pipeline.getProgressSnapshot();
        scheduler.scheduleAtFixedRate(this::logHeartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    void logHeartbeat() {
        try {
            var curr = pipeline.getProgressSnapshot();
            if (curr.totalDocs() == 0 && curr.currentPartition() == null) {
                return;
            }

            var p = prev;
            prev = curr;

            // Diff the cumulative nanos to get this interval's values
            long dBufNanos = curr.bufferFillNanos() - (p != null ? p.bufferFillNanos() : 0);
            long dQueueNanos = curr.queueWaitNanos() - (p != null ? p.queueWaitNanos() : 0);
            long dWriteNanos = curr.writeNanos() - (p != null ? p.writeNanos() : 0);
            long dBatchesWritten = curr.batchesWritten() - (p != null ? p.batchesWritten() : 0);
            long dBatchesProduced = curr.batchesProduced() - (p != null ? p.batchesProduced() : 0);

            long totalNanos = dBufNanos + dQueueNanos + dWriteNanos;

            // Per-batch averages for this interval
            long avgBufMs = dBatchesProduced > 0 ? dBufNanos / dBatchesProduced / 1_000_000 : 0;
            long avgQueueMs = dBatchesWritten > 0 ? dQueueNanos / dBatchesWritten / 1_000_000 : 0;
            long avgWriteMs = dBatchesWritten > 0 ? dWriteNanos / dBatchesWritten / 1_000_000 : 0;

            // Percentage breakdown
            int pctBuf = totalNanos > 0 ? (int) (dBufNanos * 100 / totalNanos) : 0;
            int pctQueue = totalNanos > 0 ? (int) (dQueueNanos * 100 / totalNanos) : 0;
            int pctWrite = totalNanos > 0 ? (int) (dWriteNanos * 100 / totalNanos) : 0;

            log.info("Pipeline heartbeat: partition={}, docs={}, bytes={} MB, " +
                    "activeBatches={}/{}, batchesProduced={}, batchesWritten={}, " +
                    "interval[readMs={} ({}%), queueMs={} ({}%), writeMs={} ({}%)]",
                curr.currentPartition(),
                curr.totalDocs(),
                curr.totalBytes() / (1024 * 1024),
                curr.activeBatches(),
                curr.batchConcurrency(),
                curr.batchesProduced(),
                curr.batchesWritten(),
                avgBufMs, pctBuf,
                avgQueueMs, pctQueue,
                avgWriteMs, pctWrite);
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
