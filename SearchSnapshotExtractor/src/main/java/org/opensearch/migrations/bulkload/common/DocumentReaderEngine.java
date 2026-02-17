package org.opensearch.migrations.bulkload.common;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;

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
        int startingDocId
    ) throws IOException;
}
