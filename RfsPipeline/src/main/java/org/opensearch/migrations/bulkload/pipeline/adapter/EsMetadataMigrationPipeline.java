package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.Objects;

import org.opensearch.migrations.bulkload.pipeline.PipelineException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ES-specific metadata migration pipeline: global templates → per-index metadata.
 *
 * <p>This pipeline is only used for ES→ES/OpenSearch migrations where global metadata
 * (templates, component templates, index templates) needs to be migrated. Non-ES sources
 * do not have global metadata and should not use this pipeline.
 */
@Slf4j
public class EsMetadataMigrationPipeline {

    private static final int DEFAULT_INDEX_MIGRATION_CONCURRENCY = 5;

    private final GlobalMetadataSource source;
    private final GlobalMetadataSink sink;
    private final int indexMigrationConcurrency;

    public EsMetadataMigrationPipeline(GlobalMetadataSource source, GlobalMetadataSink sink) {
        this(source, sink, DEFAULT_INDEX_MIGRATION_CONCURRENCY);
    }

    public EsMetadataMigrationPipeline(
        GlobalMetadataSource source,
        GlobalMetadataSink sink,
        int indexMigrationConcurrency
    ) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        if (indexMigrationConcurrency < 1) {
            throw new IllegalArgumentException(
                "indexMigrationConcurrency must be >= 1, got " + indexMigrationConcurrency);
        }
        this.indexMigrationConcurrency = indexMigrationConcurrency;
    }

    /**
     * Migrate global metadata (templates) from source to sink.
     */
    public Mono<Void> migrateGlobalMetadata() {
        log.info("Migrating global metadata");
        var globalMetadata = source.readGlobalMetadata();
        return sink.writeGlobalMetadata(globalMetadata)
            .doOnSuccess(v -> log.info("Global metadata migration complete"))
            .onErrorMap(e -> !(e instanceof PipelineException),
                e -> new PipelineException("Failed migrating global metadata", e));
    }

    /**
     * Migrate a single index's metadata from source to sink.
     */
    public Mono<Void> migrateIndexMetadata(String indexName) {
        log.info("Migrating index metadata: {}", indexName);
        IndexMetadataSnapshot metadata = source.readIndexMetadata(indexName);
        return sink.createIndex(metadata)
            .doOnSuccess(v -> log.info("Index metadata migrated: {}", indexName))
            .onErrorMap(e -> !(e instanceof PipelineException),
                e -> new PipelineException("Failed migrating index metadata: " + indexName, e));
    }

    /**
     * Migrate all metadata — global metadata first, then all index metadata.
     *
     * @return a Flux that emits each index name as its metadata is migrated
     */
    public Flux<String> migrateAll() {
        var globalMetadata = source.readGlobalMetadata();
        var indices = globalMetadata.indices();
        log.info("Starting full metadata migration: {} indices", indices.size());

        return Mono.from(sink.writeGlobalMetadata(globalMetadata))
            .thenMany(
                Flux.fromIterable(indices)
                    .flatMap(indexName -> migrateIndexMetadata(indexName).thenReturn(indexName), indexMigrationConcurrency)
            )
            .doOnComplete(() -> log.info("Full metadata migration complete"));
    }
}
