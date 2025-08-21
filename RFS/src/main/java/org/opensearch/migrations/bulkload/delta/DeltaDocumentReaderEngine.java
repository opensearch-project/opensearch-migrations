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
import org.opensearch.migrations.bulkload.common.enums.RfsDocumentOperation;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.tracing.BaseRootRfsContext;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Flux;

@AllArgsConstructor
public class DeltaDocumentReaderEngine implements DocumentReaderEngine {
    private final BiFunction<String, Integer, ShardMetadata> previousShardMetadataFactory;
    private final BiFunction<String, Integer, ShardMetadata> shardMetadataFactory;
    private final DeltaMode deltaMode;

    @Override
    public SnapshotShardUnpacker createUnpacker(
        SnapshotShardUnpacker.Factory unpackerFactory,
        String indexName,
        int shardNumber
    ) {
        ShardMetadata previousShardMetadata = previousShardMetadataFactory.apply(indexName, shardNumber);
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        
        // For delta unpacking, combine files from both current and base shard metadata
        Set<ShardFileInfo> filesToUnpack = Stream.concat(
                shardMetadata.getFiles().stream(),
                previousShardMetadata.getFiles().stream())
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
        int startingDocId,
        BaseRootRfsContext rootContext
    ) {
        ShardMetadata previousShardMetadata = previousShardMetadataFactory.apply(indexName, shardNumber);
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        
        if (deltaMode == DeltaMode.UPDATES_ONLY) {
            // Original behavior - only additions
            return reader.readDeltaDocuments(
                previousShardMetadata.getSegmentFileName(),
                shardMetadata.getSegmentFileName(),
                startingDocId,
                rootContext
            );
        } else if (deltaMode == DeltaMode.UPDATES_AND_DELETES) {
            // New behavior - both additions and deletions
            var deltaResult = reader.readDeltaDocumentsWithDeletes(
                previousShardMetadata.getSegmentFileName(),
                shardMetadata.getSegmentFileName(),
                startingDocId,
                rootContext
            );
            
            // Convert deletions to delete documents and merge with additions
            // We need to mark these documents as deletions so they can be processed correctly
            Flux<RfsLuceneDocument> deletionsAsDocuments = Flux.from(deltaResult.deletions)
                .map(doc -> {
                    // Create a new RfsLuceneDocument that represents a delete operation
                    // Preserve the original source content for potential use in transformations
                    return new RfsLuceneDocument(
                        doc.luceneDocNumber,
                        doc.id,
                        doc.type,
                        doc.source,  // Preserve actual source content
                        doc.routing,
                        RfsDocumentOperation.DELETE  // Mark as delete operation
                    );
                });
            
            // Merge both streams - additions and deletions
            return Flux.concat(
                deletionsAsDocuments,
                deltaResult.additions
            );
        } else {
            throw new UnsupportedOperationException("Unsupported delta mode given " + deltaMode);
        }
    }
}
