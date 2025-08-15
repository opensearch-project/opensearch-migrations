package org.opensearch.migrations.bulkload.worker;

import java.util.function.BiFunction;

import org.opensearch.migrations.bulkload.common.DocumentReadingStrategy;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Flux;

@AllArgsConstructor
public class RegularDocumentReadingStrategy implements DocumentReadingStrategy {
    private final BiFunction<String, Integer, ShardMetadata> shardMetadataFactory;

    @Override
    public SnapshotShardUnpacker createUnpacker(
        SnapshotShardUnpacker.Factory unpackerFactory,
        String indexName,
        int shardNumber
    ) {
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        return unpackerFactory.create(shardMetadata);
    }

    @Override
    public Flux<RfsLuceneDocument> readDocuments(
        LuceneIndexReader reader,
        String indexName,
        int shardNumber,
        int startingDocId
    ) {
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        return reader.readDocuments(shardMetadata.getSegmentFileName(), startingDocId);
    }
}
