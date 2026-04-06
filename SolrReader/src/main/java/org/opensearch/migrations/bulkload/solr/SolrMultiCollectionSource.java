package org.opensearch.migrations.bulkload.solr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * DocumentSource that wraps multiple Solr collections from a backup directory.
 * Each collection is backed by a SolrBackupSource. Partitions are looked up
 * lazily per collection.
 */
@Slf4j
public class SolrMultiCollectionSource implements DocumentSource {

    private final Path backupDir;
    private final Map<String, JsonNode> schemas;
    private final ConcurrentHashMap<String, SolrBackupSource> sources = new ConcurrentHashMap<>();

    public SolrMultiCollectionSource(Path backupDir, Map<String, JsonNode> schemas) {
        this.backupDir = backupDir;
        this.schemas = schemas;
    }

    private SolrBackupSource getSource(String collection) {
        return sources.computeIfAbsent(collection, c -> {
            var schema = schemas.get(c);
            var schemaNode = schema != null ? schema.path("schema") : schema;
            return new SolrBackupSource(backupDir.resolve(c), c, schemaNode);
        });
    }

    @Override
    public List<String> listCollections() {
        return new ArrayList<>(schemas.keySet());
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        return getSource(collectionName).listPartitions(collectionName);
    }

    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        return getSource(collectionName).readCollectionMetadata(collectionName);
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        return getSource(partition.collectionName()).readDocuments(partition, startingDocOffset);
    }

    @Override
    public void close() throws Exception {
        for (var source : sources.values()) {
            source.close();
        }
    }
}
