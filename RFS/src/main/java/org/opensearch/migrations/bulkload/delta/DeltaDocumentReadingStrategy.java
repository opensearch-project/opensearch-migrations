package org.opensearch.migrations.bulkload.delta;

import java.util.function.BiFunction;

import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentReadingStrategy;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Flux;

@AllArgsConstructor
public class DeltaDocumentReadingStrategy implements DocumentReadingStrategy {
    private final BiFunction<String, Integer, ShardMetadata> baseShardMetadataFactory;
    private final BiFunction<String, Integer, ShardMetadata> shardMetadataFactory;
    private final DeltaMode deltaMode;

    @Override
    public SnapshotShardUnpacker createUnpacker(
        SnapshotShardUnpacker.Factory unpackerFactory,
        String indexName,
        int shardNumber
    ) {
        ShardMetadata baseShardMetadata = baseShardMetadataFactory.apply(indexName, shardNumber);
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        return unpackerFactory.create(baseShardMetadata, shardMetadata);
    }

    @Override
    public Flux<RfsLuceneDocument> readDocuments(
        LuceneIndexReader reader,
        String indexName,
        int shardNumber,
        int startingDocId
    ) {
        ShardMetadata baseShardMetadata = baseShardMetadataFactory.apply(indexName, shardNumber);
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        if (deltaMode != DeltaMode.UPDATES_ONLY) {
            throw new UnsupportedOperationException("Unsupported delta mode given " + deltaMode);
        }
        return reader.readDeltaDocuments(
            baseShardMetadata.getSegmentFileName(),
            shardMetadata.getSegmentFileName(),
            startingDocId
        );
    }
}
