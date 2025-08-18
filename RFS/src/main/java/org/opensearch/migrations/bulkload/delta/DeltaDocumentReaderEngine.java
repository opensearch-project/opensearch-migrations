package org.opensearch.migrations.bulkload.delta;

import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentReaderEngine;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Flux;

@AllArgsConstructor
public class DeltaDocumentReaderEngine implements DocumentReaderEngine {
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
        
        // For delta unpacking, combine files from both current and base shard metadata
        Set<ShardFileInfo> filesToUnpack = Stream.concat(
                shardMetadata.getFiles().stream(),
                baseShardMetadata.getFiles().stream())
            .collect(Collectors.toCollection(
                () -> new TreeSet<>(Comparator.comparing(ShardFileInfo::key))));
        
        // TODO: Refactor this away from here for shard downloading and base it on filesToUnpack
        // Currently, for S3 cases, uses TransferManager to download snapshot files
        unpackerFactory.getRepoAccessor().prepBlobFiles(shardMetadata);
        
        return unpackerFactory.create(
            filesToUnpack,
            indexName,
            shardMetadata.getIndexId(),
            shardNumber
        );
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
