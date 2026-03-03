package org.opensearch.migrations.bulkload.pipeline.source;

import org.opensearch.migrations.bulkload.pipeline.metrics.SourceExtractionMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating {@link DocumentSource} instances based on the configured {@link SourceType}.
 *
 * <p>This is the central point for source type detection and selection. Callers provide
 * the desired source type and configuration, and the factory returns the appropriate
 * {@link DocumentSource} implementation.
 *
 * <p>For {@link SourceType#SNAPSHOT}, the caller must provide a pre-built {@link DocumentSource}
 * (e.g. a LuceneSnapshotSource) since snapshot reading requires external dependencies
 * that rfs-pipeline intentionally does not include.
 *
 * <p>For {@link SourceType#SOURCELESS}, the factory creates a {@link SourcelessDocumentSource}
 * from the provided {@link SourcelessExtractionConfig}.
 */
@Slf4j
public final class DocumentSourceFactory {

    private DocumentSourceFactory() {}

    /**
     * Create a {@link DocumentSource} for sourceless extraction mode.
     *
     * @param config  the sourceless extraction configuration
     * @param metrics metrics collector (nullable — defaults to NOOP)
     * @return a new {@link SourcelessDocumentSource}
     */
    public static DocumentSource createSourceless(SourcelessExtractionConfig config, SourceExtractionMetrics metrics) {
        log.info("Creating sourceless document source: {}", config);
        return new SourcelessDocumentSource(config, metrics);
    }

    /**
     * Select the appropriate {@link DocumentSource} based on source type.
     *
     * <p>For {@link SourceType#SNAPSHOT}, returns the provided snapshotSource.
     * For {@link SourceType#SOURCELESS}, creates a new {@link SourcelessDocumentSource}.
     *
     * @param sourceType      the desired source type
     * @param snapshotSource  a pre-built snapshot source (required when sourceType is SNAPSHOT, nullable otherwise)
     * @param sourcelessConfig config for sourceless mode (required when sourceType is SOURCELESS, nullable otherwise)
     * @param metrics         metrics collector (nullable — defaults to NOOP)
     * @return the selected {@link DocumentSource}
     * @throws IllegalArgumentException if required parameters are missing for the selected source type
     */
    public static DocumentSource select(
        SourceType sourceType,
        DocumentSource snapshotSource,
        SourcelessExtractionConfig sourcelessConfig,
        SourceExtractionMetrics metrics
    ) {
        return switch (sourceType) {
            case SNAPSHOT -> {
                if (snapshotSource == null) {
                    throw new IllegalArgumentException("snapshotSource is required for SNAPSHOT source type");
                }
                log.info("Selected SNAPSHOT source type");
                yield snapshotSource;
            }
            case SOURCELESS -> {
                if (sourcelessConfig == null) {
                    throw new IllegalArgumentException("sourcelessConfig is required for SOURCELESS source type");
                }
                yield createSourceless(sourcelessConfig, metrics);
            }
        };
    }
}
