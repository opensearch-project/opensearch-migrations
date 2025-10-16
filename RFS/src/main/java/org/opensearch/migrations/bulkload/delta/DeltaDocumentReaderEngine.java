package org.opensearch.migrations.bulkload.delta;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.tracing.BaseRootRfsContext;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
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
    ) throws IOException {
        ShardMetadata previousShardMetadata = previousShardMetadataFactory.apply(indexName, shardNumber);
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        LuceneDirectoryReader previousReader = null;
        LuceneDirectoryReader currentReader = null;
        try {
            previousReader = reader.getReader(previousShardMetadata.getSegmentFileName());
            currentReader  = reader.getReader(shardMetadata.getSegmentFileName());

            var deltaResult = DeltaLuceneReader.readDeltaDocsByLeavesFromStartingPosition(
                previousReader, currentReader, startingDocId, rootContext);

            Runnable cleanup = getCleanup(indexName, previousReader, currentReader);
            return (switch (deltaMode) {
                case UPDATES_ONLY -> deltaResult.additions;
                case UPDATES_AND_DELETES ->
                    // Using concat to perform deletions first then additions
                    Flux.concat(
                        deltaResult.deletions,
                        deltaResult.additions
                    );
                case DELETES_ONLY -> deltaResult.deletions;
            }).doFinally(s -> cleanup.run());
        } catch (Exception e) {
            log.atError()
                .setMessage("Exception during delta readDocuments")
                .setCause(e)
                .log();
            getCleanup(indexName, previousReader, currentReader).run();
            throw e;
        }
    }

    private static Runnable getCleanup(String indexName, LuceneDirectoryReader previousReader, LuceneDirectoryReader currentReader) {
        return () -> {
            try {
                if (previousReader != null) {
                    previousReader.close();
                }
                if (currentReader != null) {
                    currentReader.close();
                }
            } catch (IOException e) {
                log.atError()
                    .setMessage("Unable to close reader for index [" + indexName + "]")
                    .setCause(e)
                    .log();
                throw new UncheckedIOException(e);
            }
        };
    }
}
