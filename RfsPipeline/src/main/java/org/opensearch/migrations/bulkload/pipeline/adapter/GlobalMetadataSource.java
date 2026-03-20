package org.opensearch.migrations.bulkload.pipeline.adapter;

/**
 * ES-specific source for global metadata (templates) and per-index metadata.
 *
 * <p>This is an optional capability — only ES snapshot sources implement it.
 * Non-ES sources (S3, Solr) do not have global metadata and should not implement this.
 * The core pipeline contract ({@link org.opensearch.migrations.bulkload.pipeline.source.DocumentSource})
 * uses {@link org.opensearch.migrations.bulkload.pipeline.ir.CollectionMetadata} instead.
 */
public interface GlobalMetadataSource extends AutoCloseable {

    /** Read global metadata (templates, component templates, index templates). */
    GlobalMetadataSnapshot readGlobalMetadata();

    /** Read ES-specific metadata for a single index. */
    IndexMetadataSnapshot readIndexMetadata(String indexName);

    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
