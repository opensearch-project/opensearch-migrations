package org.opensearch.migrations.bulkload.pipeline.source;

import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

/**
 * Port for reading metadata from any source — snapshot or remote cluster.
 *
 * <p>Separated from {@link DocumentSource} because metadata migration and document
 * migration are independent operations with different lifecycles.
 */
public interface MetadataSource extends AutoCloseable {

    /** Read global metadata (templates, component templates, index templates). */
    GlobalMetadataSnapshot readGlobalMetadata();

    /** Read metadata for a specific index. */
    IndexMetadataSnapshot readIndexMetadata(String indexName);

    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
