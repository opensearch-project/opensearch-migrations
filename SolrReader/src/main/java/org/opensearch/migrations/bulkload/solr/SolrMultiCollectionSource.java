package org.opensearch.migrations.bulkload.solr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
 *
 * <p>When constructed with a {@code collectionPreparer}, it is called once per
 * collection before the first {@link #listPartitions} or {@link #readDocuments}
 * call. This enables lazy per-collection S3 downloads (e.g. shard metadata)
 * without coupling to S3Repo.
 *
 * <p>When constructed with a {@code shardPreparer}, it is called once per
 * partition before {@link #readDocuments}. This enables per-shard S3 downloads
 * of only the Lucene segment files needed for that shard.
 */
@Slf4j
public class SolrMultiCollectionSource implements DocumentSource {

    private final Path backupDir;
    private final Map<String, JsonNode> schemas;
    private final Consumer<String> collectionPreparer;
    private final Consumer<SolrShardPartition> shardPreparer;
    private final int solrMajorVersion;
    private final ConcurrentHashMap<String, SolrBackupSource> sources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> preparedCollections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> preparedShards = new ConcurrentHashMap<>();

    /**
     * @param collectionPreparer called once per collection name before first access.
     *                           Use this to download shard metadata on demand.
     * @param shardPreparer called once per shard partition before readDocuments.
     *                      Use this to download only the specific shard's index files.
     * @param solrMajorVersion source Solr major version; selects the matching Lucene reader
     *                         (6 → Lucene 6, 7 → Lucene 7, 8/9 → Lucene 9).
     */
    public SolrMultiCollectionSource(
        Path backupDir, Map<String, JsonNode> schemas,
        Consumer<String> collectionPreparer, Consumer<SolrShardPartition> shardPreparer,
        int solrMajorVersion
    ) {
        this.backupDir = backupDir;
        this.schemas = schemas;
        this.collectionPreparer = collectionPreparer;
        this.shardPreparer = shardPreparer;
        this.solrMajorVersion = solrMajorVersion;
    }

    private void ensureCollectionPrepared(String collection) {
        if (collectionPreparer == null) {
            return;
        }
        preparedCollections.computeIfAbsent(collection, c -> {
            log.atInfo().setMessage("Preparing collection '{}' for reading").addArgument(c).log();
            collectionPreparer.accept(c);
            return true;
        });
    }

    private SolrBackupSource getSource(String collection) {
        return sources.computeIfAbsent(collection, c -> {
            var schema = schemas.get(c);
            var schemaNode = schema != null ? schema.path("schema") : schema;
            var collectionDir = SolrBackupLayout.resolveCollectionDataDir(backupDir.resolve(c));
            return new SolrBackupSource(collectionDir, c, schemaNode, solrMajorVersion);
        });
    }

    @Override
    public List<String> listCollections() {
        return new ArrayList<>(schemas.keySet());
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        ensureCollectionPrepared(collectionName);
        return getSource(collectionName).listPartitions(collectionName);
    }

    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        ensureCollectionPrepared(collectionName);
        return getSource(collectionName).readCollectionMetadata(collectionName);
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        ensureCollectionPrepared(partition.collectionName());
        if (shardPreparer != null && partition instanceof SolrShardPartition solrPartition) {
            preparedShards.computeIfAbsent(solrPartition.name(), key -> {
                log.atInfo().setMessage("Preparing shard '{}' for reading").addArgument(key).log();
                shardPreparer.accept(solrPartition);
                return true;
            });
        }
        return getSource(partition.collectionName()).readDocuments(partition, startingDocOffset);
    }

    @Override
    public void close() throws Exception {
        for (var source : sources.values()) {
            source.close();
        }
    }
}
