package org.opensearch.migrations.bulkload.worker;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

import org.opensearch.migrations.bulkload.common.DocumentReaderEngine;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.lucene.LuceneReader;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Flux;

@AllArgsConstructor
public class RegularDocumentReaderEngine implements DocumentReaderEngine {
    private final BiFunction<String, Integer, ShardMetadata> shardMetadataFactory;

    @Override
    public SnapshotShardUnpacker createUnpacker(
        SnapshotShardUnpacker.Factory unpackerFactory,
        String indexName,
        int shardNumber
    ) {
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        
        // Extract files from metadata
        Set<ShardFileInfo> filesToUnpack = new TreeSet<>(Comparator.comparing(ShardFileInfo::key));
        filesToUnpack.addAll(shardMetadata.getFiles());

        return unpackerFactory.create(
            filesToUnpack,
            indexName,
            shardMetadata.getIndexId(),
            shardNumber
        );
    }

    @Override
    public DocumentChangeset prepareChangeset(
        LuceneIndexReader reader,
        String indexName,
        int shardNumber,
        int startingDocId
    ) throws IOException {
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        var directoryReader = reader.getReader(shardMetadata.getSegmentFileName());
        return new DocumentChangeset(
            Flux.empty(),
            LuceneReader.readDocsByLeavesFromStartingPosition(directoryReader, startingDocId),
            LuceneDirectoryReader.getCleanupRunnable(directoryReader)
        );
    }
}
