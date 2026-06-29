package org.opensearch.migrations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.solr.SolrBackupLayout;
import org.opensearch.migrations.bulkload.solr.SolrSchemaXmlParser;
import org.opensearch.migrations.bulkload.solr.SolrShardPartition;
import org.opensearch.migrations.bulkload.solr.SolrSnapshotReader;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the collections, data directories, and lazy S3 downloads for a Solr backup (bare or
 * wrapped, on disk or in S3). Extracted from {@link RfsMigrateDocuments} to be unit-testable.
 */
@Slf4j
public class SolrBackupDiscovery {

    private final S3Repo s3Repo;
    private final Path backupDir;
    private final SolrBackupLayout.SolrBackupMode bareMode;
    private final List<String> collections;
    private final Map<String, String> dataDirByCollection = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> schemas = new LinkedHashMap<>();

    private SolrBackupDiscovery(S3Repo s3Repo, Path backupDir, SolrBackupLayout.BareBackupLayout bare,
                                List<String> indexAllowlist) throws IOException {
        this.s3Repo = s3Repo;
        this.backupDir = backupDir;
        this.bareMode = bare != null ? bare.mode() : null;

        final List<String> resolved;
        if (bare != null && bare.collectionName() != null) {
            log.atInfo().setMessage("Detected bare {} Solr backup; single collection '{}' at '{}'")
                .addArgument(bare.mode()).addArgument(bare.collectionName()).addArgument(bare.dataPath()).log();
            resolved = new ArrayList<>(List.of(bare.collectionName()));
            dataDirByCollection.put(bare.collectionName(), bare.dataPath());
        } else if (s3Repo != null) {
            resolved = new ArrayList<>(s3Repo.listTopLevelDirectories());
        } else {
            resolved = new ArrayList<>(SolrSnapshotReader.discoverCollections(backupDir));
        }
        if (!indexAllowlist.isEmpty()) {
            resolved.retainAll(indexAllowlist);
        }
        this.collections = resolved;
        for (var collection : collections) {
            schemas.put(collection, null);
        }
    }

    public static SolrBackupDiscovery discover(S3Repo s3Repo, Path backupDir, String nameOverride,
                                               List<String> indexAllowlist) throws IOException {
        return new SolrBackupDiscovery(s3Repo, backupDir, detectBareLayout(s3Repo, backupDir, nameOverride),
            indexAllowlist);
    }

    static SolrBackupLayout.BareBackupLayout detectBareLayout(S3Repo s3Repo, Path backupDir, String nameOverride) {
        return s3Repo != null
            ? detectBareSolrLayoutInS3(s3Repo, nameOverride)
            : SolrBackupLayout.classifyBareBackup(backupDir, nameOverride);
    }

    static SolrBackupLayout.BareBackupLayout detectBareSolrLayoutInS3(S3Repo s3Repo, String nameOverride) {
        var bare = SolrBackupLayout.detectBareLayoutFromListing(s3Repo.listTopLevelDirectories());
        if (bare == null) {
            return null;
        }
        if (bare.mode() == SolrBackupLayout.SolrBackupMode.CLOUD && bare.collectionName() == null) {
            var name = nameOverride;
            if (name == null) {
                try {
                    s3Repo.downloadFile("backup.properties");
                    name = SolrBackupLayout.readCollectionNameFromBackupProperties(s3Repo.getRepoRootDir());
                } catch (RuntimeException e) {
                    log.warn("Could not recover collection name from S3 backup.properties: {}", e.getMessage());
                }
            }
            return new SolrBackupLayout.BareBackupLayout(SolrBackupLayout.SolrBackupMode.CLOUD, name, bare.dataPath());
        }
        return nameOverride != null
            ? new SolrBackupLayout.BareBackupLayout(bare.mode(), nameOverride, bare.dataPath())
            : bare;
    }

    public List<String> collections() {
        return collections;
    }

    public Map<String, String> dataDirByCollection() {
        return dataDirByCollection;
    }

    public Map<String, JsonNode> schemas() {
        return schemas;
    }

    /** Only S3 needs lazy per-shard downloads; filesystem backups are already local. */
    public boolean shardPreparationNeeded() {
        return s3Repo != null && bareMode != SolrBackupLayout.SolrBackupMode.STANDALONE;
    }

    public void prepareCollection(String collection) {
        if (s3Repo != null && bareMode == SolrBackupLayout.SolrBackupMode.STANDALONE) {
            // Flat standalone index: download the whole data dir up front.
            s3Repo.downloadPrefix(dataDirByCollection.get(collection));
        } else if (s3Repo != null) {
            String dataDir = null;
            String zkBackupName = null;
            if (dataDirByCollection.containsKey(collection)) {
                dataDir = dataDirByCollection.get(collection);
                zkBackupName = SolrBackupLayout.findLatestZkBackupName(s3Repo.listSubDirectories(dataDir));
            } else {
                var resolved = SolrBackupLayout.resolveCollectionDataPrefix(
                    collection, s3Repo::listSubDirectories);
                if (resolved == null) {
                    log.warn("No zk_backup directories found for collection '{}' in S3", collection);
                } else {
                    dataDir = resolved.joinWith(collection);
                    zkBackupName = resolved.latestZkBackupName();
                    dataDirByCollection.put(collection, dataDir);
                }
            }
            if (dataDir != null) {
                if (zkBackupName != null) {
                    s3Repo.downloadPrefix(SolrBackupLayout.joinPrefix(dataDir, zkBackupName));
                }
                log.atInfo().setMessage("Downloading shard metadata for collection '{}' from S3").addArgument(collection).log();
                s3Repo.downloadPrefix(SolrBackupLayout.joinPrefix(dataDir, "shard_backup_metadata"));
                // Solr 6: stub out snapshot.shardN/ dirs so shards can be counted before download.
                var dataDirFinal = dataDir;
                s3Repo.listSubDirectories(dataDir).stream()
                    .filter(name -> name.startsWith("snapshot."))
                    .forEach(snapshotDirName -> {
                        try {
                            Files.createDirectories(backupDir.resolve(dataDirFinal).resolve(snapshotDirName));
                        } catch (IOException e) {
                            log.warn("Failed to create snapshot stub dir {}/{}", dataDirFinal, snapshotDirName, e);
                        }
                    });
            }
        }
        var dataDir = backupDir.resolve(dataDirByCollection.getOrDefault(collection, collection));
        schemas.put(collection, SolrSchemaXmlParser.findAndParse(dataDir));
    }

    public void prepareShard(SolrShardPartition partition) {
        var dataDir = dataDirByCollection.getOrDefault(partition.collection(), partition.collection());
        var mapping = partition.fileNameMapping();
        if (mapping != null) {
            log.atInfo().setMessage("Downloading {} index files for shard '{}/{}' from S3")
                .addArgument(mapping.size()).addArgument(partition.collection()).addArgument(partition.shard()).log();
            for (var uuid : mapping.values()) {
                s3Repo.downloadFile(SolrBackupLayout.joinPrefix(dataDir, "index/" + uuid));
            }
        } else {
            log.atInfo().setMessage("Downloading index data for shard '{}/{}' from S3")
                .addArgument(partition.collection()).addArgument(partition.shard()).log();
            var shardPath = partition.shard().startsWith("snapshot.")
                ? SolrBackupLayout.joinPrefix(dataDir, partition.shard())
                : SolrBackupLayout.joinPrefix(dataDir, "index");
            s3Repo.downloadPrefix(shardPath);
        }
    }
}
