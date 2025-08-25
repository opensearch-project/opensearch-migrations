package org.opensearch.migrations.bulkload.common;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.tracing.BaseRootRfsContext;

import reactor.core.publisher.Flux;

public interface DocumentReaderEngine {
    SnapshotShardUnpacker createUnpacker(
        SnapshotShardUnpacker.Factory unpackerFactory,
        String indexName,
        int shardNumber
    );

    Flux<RfsLuceneDocument> readDocuments(
        LuceneIndexReader reader,
        String indexName,
        int shardNumber,
        int startingDocId,
        BaseRootRfsContext rootContext
    ) throws IOException;
}
