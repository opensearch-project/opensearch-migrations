package org.opensearch.migrations.bulkload.worker;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

import org.opensearch.migrations.bulkload.common.DocumentReaderEngine;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.lucene.LuceneReader;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.tracing.BaseRootRfsContext;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
public class RegularDocumentReaderEngine implements DocumentReaderEngine {
    private final BiFunction<String, Integer, ShardMetadata> shardMetadataFactory;
    private final IndexMetadata.Factory indexMetadataFactory;
    private final String snapshotName;
    
    // Cached mapping context per index (created once per work item)
    private String cachedIndexName;
    private FieldMappingContext cachedMappingContext;

    public RegularDocumentReaderEngine(BiFunction<String, Integer, ShardMetadata> shardMetadataFactory) {
        this(shardMetadataFactory, null, null);
    }

    public RegularDocumentReaderEngine(
            BiFunction<String, Integer, ShardMetadata> shardMetadataFactory,
            IndexMetadata.Factory indexMetadataFactory,
            String snapshotName) {
        this.shardMetadataFactory = shardMetadataFactory;
        this.indexMetadataFactory = indexMetadataFactory;
        this.snapshotName = snapshotName;
    }

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
    public FieldMappingContext getFieldMappingContext(String indexName) {
        if (indexMetadataFactory == null || snapshotName == null) {
            log.debug("No indexMetadataFactory or snapshotName, skipping mapping context");
            return null;
        }
        
        // Return cached context if same index
        if (indexName.equals(cachedIndexName) && cachedMappingContext != null) {
            log.debug("Returning cached FieldMappingContext for index {}", indexName);
            return cachedMappingContext;
        }
        
        try {
            log.debug("Fetching IndexMetadata for snapshot={}, index={}", snapshotName, indexName);
            IndexMetadata metadata = indexMetadataFactory.fromRepo(snapshotName, indexName);
            var mappingsNode = metadata.getMappings();
            log.debug("Got mappings node type={} for index {}", 
                mappingsNode != null ? mappingsNode.getNodeType() : "null", indexName);
            cachedMappingContext = new FieldMappingContext(mappingsNode);
            cachedIndexName = indexName;
            log.debug("Created FieldMappingContext for index {}", indexName);
            return cachedMappingContext;
        } catch (Exception e) {
            log.warn("Failed to get mappings for index {}, using heuristic conversion", indexName, e);
            return null;
        }
    }

    @Override
    public DocumentChangeset prepareChangeset(
        LuceneIndexReader reader,
        String indexName,
        int shardNumber,
        int startingDocId,
        BaseRootRfsContext ignored
    ) throws IOException {
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexName, shardNumber);
        var directoryReader = reader.getReader(shardMetadata.getSegmentFileName());
        var mappingContext = getFieldMappingContext(indexName);
        return new DocumentChangeset(
            Flux.empty(),
            LuceneReader.readDocsByLeavesFromStartingPosition(directoryReader, startingDocId, mappingContext),
            LuceneDirectoryReader.getCleanupRunnable(directoryReader)
        );
    }
}
