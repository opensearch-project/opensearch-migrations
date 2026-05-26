package org.opensearch.migrations.bulkload.solr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * IndexMetadata.Factory for Solr backups. Reads schema from the backup directory
 * and converts to OpenSearch mappings via SolrSchemaConverter.
 */
@Slf4j
public class SolrBackupIndexMetadataFactory implements IndexMetadata.Factory {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path backupDir;
    private final Map<String, JsonNode> schemas;
    private final Consumer<String> collectionPreparer;
    private final int solrMajorVersion;

    /**
     * @param collectionPreparer called once per collection before counting shards.
     *                           For S3 sources this downloads shard_backup_metadata so that
     *                           {@link SolrBackupSource#listPartitions} can discover all shards.
     * @param solrMajorVersion source Solr major version; selects the Lucene reader to use when
     *                         counting shards for a backup.
     */
    public SolrBackupIndexMetadataFactory(
        Path backupDir, Map<String, JsonNode> schemas,
        Consumer<String> collectionPreparer, int solrMajorVersion
    ) {
        this.backupDir = backupDir;
        this.schemas = schemas;
        this.collectionPreparer = collectionPreparer;
        this.solrMajorVersion = solrMajorVersion;
    }

    @Override
    public IndexMetadata fromRepo(String snapshotName, String indexName) {
        // Run the preparer first so it can hydrate per-collection state (download the schema
        // and shard_backup_metadata from S3 into the local map / filesystem). Only after the
        // preparer runs is the schemas map guaranteed to contain the entry for indexName.
        if (collectionPreparer != null) {
            collectionPreparer.accept(indexName);
        }

        var schema = schemas.get(indexName);
        var schemaNode = schema != null ? schema.path("schema") : MAPPER.createObjectNode();

        // Discover shard count from backup directory
        var collectionDir = SolrBackupLayout.resolveCollectionDataDir(backupDir.resolve(indexName));
        var source = new SolrBackupSource(collectionDir, indexName, schemaNode, solrMajorVersion);
        int shardCount = source.listPartitions(indexName).size();
        log.info("Solr collection {} has {} shard(s)", indexName, shardCount);

        // Build OpenSearch-compatible index metadata with proper mappings
        var indexNode = MAPPER.createObjectNode();

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(
            schemaNode.path("fields"),
            schemaNode.path("dynamicFields"),
            schemaNode.path("copyFields"),
            schemaNode.path("fieldTypes")
        );
        indexNode.set("mappings", mappings);
        indexNode.set("aliases", MAPPER.createObjectNode());

        var settings = MAPPER.createObjectNode();
        var indexSettings = MAPPER.createObjectNode();
        indexSettings.put("number_of_shards", String.valueOf(shardCount));
        indexSettings.put("number_of_replicas", "1");
        settings.set("index", indexSettings);
        indexNode.set("settings", settings);

        return new SolrIndexMetadata(indexName, indexNode);
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return new SnapshotRepo.Provider() {
            @Override
            public List<SnapshotRepo.Snapshot> getSnapshots() { return List.of(); }

            @Override
            public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
                var indices = new ArrayList<SnapshotRepo.Index>();
                for (var name : schemas.keySet()) {
                    indices.add(new SnapshotRepo.Index() {
                        @Override public String getName() { return name; }
                        @Override public String getId() { return name; }
                    });
                }
                return indices;
            }

            @Override public String getSnapshotId(String snapshotName) { return snapshotName; }
            @Override public String getIndexId(String indexName) { return indexName; }
            @Override public org.opensearch.migrations.bulkload.common.SourceRepo getRepo() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SmileFactory getSmileFactory() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getIndexFileId(String snapshotName, String indexName) {
        return indexName;
    }
}
