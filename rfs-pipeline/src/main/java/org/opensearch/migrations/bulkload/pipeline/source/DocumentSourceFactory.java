package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.metrics.SourceExtractionMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating {@link DocumentSource} and {@link MetadataSource} instances
 * based on the configured {@link SourceType}.
 *
 * <p>For {@link SourceType#SNAPSHOT}, the caller must provide pre-built sources
 * since snapshot reading requires external dependencies that rfs-pipeline does not include.
 *
 * <p>For {@link SourceType#SOURCELESS}, the factory creates sources from
 * {@link SourcelessExtractionConfig} entries.
 */
@Slf4j
public final class DocumentSourceFactory {

    private DocumentSourceFactory() {}

    /**
     * Create a {@link DocumentSource} for sourceless extraction mode.
     */
    public static DocumentSource createSourceless(SourcelessExtractionConfig config, SourceExtractionMetrics metrics) {
        log.info("Creating sourceless document source: {}", config);
        return new SourcelessDocumentSource(config, metrics);
    }

    /**
     * Create a multi-index {@link DocumentSource} for sourceless extraction mode.
     */
    public static DocumentSource createSourceless(List<SourcelessExtractionConfig> configs, SourceExtractionMetrics metrics) {
        log.info("Creating multi-index sourceless document source: {} indices", configs.size());
        return new SourcelessDocumentSource(configs, metrics);
    }

    /**
     * Create a {@link MetadataSource} for sourceless extraction mode.
     */
    public static MetadataSource createSourcelessMetadata(SourcelessExtractionConfig config) {
        return new SourcelessMetadataSource(config);
    }

    /**
     * Create a multi-index {@link MetadataSource} for sourceless extraction mode.
     */
    public static MetadataSource createSourcelessMetadata(List<SourcelessExtractionConfig> configs) {
        return new SourcelessMetadataSource(configs);
    }

    /**
     * Select the appropriate {@link DocumentSource} based on source type.
     *
     * @param sourceType       the desired source type
     * @param snapshotSource   a pre-built snapshot source (required for SNAPSHOT, nullable otherwise)
     * @param sourcelessConfig config for sourceless mode (required for SOURCELESS, nullable otherwise)
     * @param metrics          metrics collector (nullable — defaults to NOOP)
     * @return the selected {@link DocumentSource}
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
