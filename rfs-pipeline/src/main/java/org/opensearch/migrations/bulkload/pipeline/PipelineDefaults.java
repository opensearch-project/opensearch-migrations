package org.opensearch.migrations.bulkload.pipeline;

/**
 * Single source of truth for pipeline batch defaults.
 * Used by both {@code PipelineRunner} (builder defaults) and the CLI ({@code RfsMigrateDocuments}).
 */
/**
 * @deprecated Defaults belong in the CLI layer, not the library. Use the constants in
 * {@code RfsMigrateDocuments.Args} instead. Library constructors require explicit values.
 */
@Deprecated(forRemoval = true)
public final class PipelineDefaults {

    private PipelineDefaults() {}

    /** @deprecated Use CLI-layer defaults instead. */
    @Deprecated(forRemoval = true)
    public static final int MAX_DOCS_PER_BATCH = 1000;

    /** @deprecated Use CLI-layer defaults instead. */
    @Deprecated(forRemoval = true)
    public static final long MAX_BYTES_PER_BATCH = 10L * 1024 * 1024;

    /** @deprecated Use CLI-layer defaults instead. */
    @Deprecated(forRemoval = true)
    public static final int BATCH_CONCURRENCY = 10;
}
