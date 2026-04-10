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

    private final GlobalMetadataSource source;
    private final GlobalMetadataSink sink;

    public EsMetadataMigrationPipeline(GlobalMetadataSource source, GlobalMetadataSink sink) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
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
                    .concatMap(indexName -> migrateIndexMetadata(indexName).thenReturn(indexName))
            )
            .doOnComplete(() -> log.info("Full metadata migration complete"));
    }
}
