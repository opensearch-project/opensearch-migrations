package org.opensearch.migrations.bulkload.pipeline;

/**
 * Single source of truth for pipeline batch defaults.
 * Used by both {@code PipelineRunner} (builder defaults) and the CLI ({@code RfsMigrateDocuments}).
 */
public final class PipelineDefaults {

    private PipelineDefaults() {}

    /** Default maximum documents per bulk batch. */
    public static final int MAX_DOCS_PER_BATCH = 1000;

    /** Default maximum bytes per bulk batch (10 MiB). */
    public static final long MAX_BYTES_PER_BATCH = 10L * 1024 * 1024;

    /** Default number of concurrent batches in flight. */
    public static final int BATCH_CONCURRENCY = 10;
}
