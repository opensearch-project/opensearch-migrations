package org.opensearch.migrations.bulkload.common;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.tracing.BaseRootRfsContext;

import reactor.core.publisher.Flux;

public interface DocumentReaderEngine {

    @SuppressWarnings("java:S100") // Record component accessors are valid method names
    record DocumentChangeset(Flux<LuceneDocumentChange> deletions, Flux<LuceneDocumentChange> additions, Runnable cleanup) {}

    SnapshotShardUnpacker createUnpacker(
        SnapshotShardUnpacker.Factory unpackerFactory,
        String indexName,
        int shardNumber
    );

    DocumentChangeset prepareChangeset(
        LuceneIndexReader reader,
        String indexName,
        int shardNumber,
        int startingDocId,
        BaseRootRfsContext rootContext
    ) throws IOException;

    /** Get field mapping context for type-aware doc_value conversion. May return null. */
    default FieldMappingContext getFieldMappingContext(String indexName) {
        return null;
    }
}
