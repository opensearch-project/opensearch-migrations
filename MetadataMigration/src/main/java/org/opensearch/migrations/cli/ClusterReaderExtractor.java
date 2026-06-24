package org.opensearch.migrations.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrBackupLayout;
import org.opensearch.migrations.bulkload.solr.SolrSchemaXmlParser;
import org.opensearch.migrations.bulkload.solr.SolrSnapshotReader;
import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;

import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class ClusterReaderExtractor {
    private final MigrateOrEvaluateArgs arguments;

    public ClusterReader extractClusterReader() {
        if (arguments.fileSystemRepoPath == null && arguments.s3RepoUri == null && arguments.sourceArgs.host == null) {
            throw new ParameterException("No details on the source cluster found, please supply a connection details or a snapshot");
        }
        if ((arguments.s3RepoUri != null) && (arguments.s3Region == null || arguments.s3LocalDirPath == null)) {
            throw new ParameterException("If an s3 repo is being used, s3-region and s3-local-dir-path must be set");
        }

        // Solr backup: prefer snapshot over remote when snapshot args are provided
        if (arguments.sourceVersion != null && arguments.sourceVersion.getFlavor() == Flavor.SOLR
                && (arguments.fileSystemRepoPath != null || arguments.s3RepoUri != null)) {
            return getSolrSnapshotReader();
        }

        if (arguments.sourceArgs != null && arguments.sourceArgs.host != null) {
            return getRemoteReader(arguments.sourceArgs.toConnectionContext());
        }

        if (arguments.sourceVersion == null) {
            throw new ParameterException("Unable to read from snapshot without --source-version parameter");
        }

        // Solr backup: read metadata from backup directory
        if (arguments.sourceVersion.getFlavor() == Flavor.SOLR) {
            return getSolrSnapshotReader();
        }

        // ES/OS snapshot path
        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(arguments.sourceVersion, true);

        SourceRepo repo = null;
        if (arguments.fileSystemRepoPath != null) {
            repo = new FileSystemRepo(Path.of(arguments.fileSystemRepoPath), fileFinder);
        } else if (arguments.s3LocalDirPath != null) {
            repo = S3Repo.create(
                Path.of(arguments.s3LocalDirPath),
                new S3Uri(arguments.s3RepoUri),
                arguments.s3Region,
                Optional.ofNullable(arguments.s3Endpoint).map(URI::create).orElse(null),
                fileFinder
            );
        } else {
            throw new ParameterException("Unable to find valid resource provider");
        }

        return getSnapshotReader(arguments.sourceVersion, repo);
    }

    private ClusterReader getSolrSnapshotReader() {
        Path backupDir;
        List<String> collectionNames;
        var dataDirByCollection = new LinkedHashMap<String, String>();
        if (arguments.fileSystemRepoPath != null) {
            backupDir = Path.of(arguments.fileSystemRepoPath);
            var bare = SolrBackupLayout.classifyBareBackup(backupDir, arguments.solrCollectionName);
            if (bare != null && bare.collectionName() != null) {
                collectionNames = List.of(bare.collectionName());
                dataDirByCollection.put(bare.collectionName(), bare.dataPath());
            } else {
                collectionNames = discoverFileSystemCollections(backupDir);
            }
        } else if (arguments.s3LocalDirPath != null) {
            var s3Repo = createSolrS3Repo();
            backupDir = s3Repo.getRepoRootDir();
            var bare = detectBareSolrLayoutInS3(s3Repo, arguments.solrCollectionName);
            if (bare != null && bare.collectionName() != null) {
                collectionNames = List.of(bare.collectionName());
                dataDirByCollection.put(bare.collectionName(), bare.dataPath());
                downloadZkBackupForDataDir(s3Repo, bare.dataPath());
            } else {
                collectionNames = s3Repo.listTopLevelDirectories();
                for (var collection : collectionNames) {
                    downloadZkBackupForCollection(s3Repo, collection);
                }
            }
        } else {
            throw new ParameterException("Solr snapshot requires --file-system-repo-path or S3 args");
        }

        return buildSolrSnapshotReader(backupDir, collectionNames, dataDirByCollection);
    }

    private List<String> discoverFileSystemCollections(Path backupDir) {
        try {
            return SolrSnapshotReader.discoverCollections(backupDir);
        } catch (IOException e) {
            throw new ParameterException("Failed to list backup directory: " + backupDir + ": " + e.getMessage());
        }
    }

    private S3Repo createSolrS3Repo() {
        // Solr's BACKUP API writes to <location>/<snapshotName>/ where <location> is
        // the path portion of s3RepoUri (or / when no subpath is configured).
        var repoUri = new S3Uri(arguments.s3RepoUri);
        var backupS3Uri = arguments.snapshotName != null
            ? SolrBackupLayout.buildBackupS3Uri(repoUri, arguments.snapshotName)
            : arguments.s3RepoUri;
        return S3Repo.createRaw(
            Path.of(arguments.s3LocalDirPath),
            new S3Uri(backupS3Uri),
            arguments.s3Region,
            Optional.ofNullable(arguments.s3Endpoint).map(URI::create).orElse(null)
        );
    }

    /**
     * Download the latest zk_backup_N for a collection from S3, handling both the flat
     * and two-level Solr 8 backup layouts.
     *
     * Solr 8 may produce either:
     *   {@code <snap>/<collection>/zk_backup_N/} (flat), or
     *   {@code <snap>/<collection>/<innerName>/zk_backup_N/} (two-level, Solr 8 incremental).
     *
     * Delegates layout resolution to {@link SolrBackupLayout#resolveCollectionDataPrefix}.
     */
    private void downloadZkBackupForCollection(S3Repo s3Repo, String collection) {
        var resolved = SolrBackupLayout.resolveCollectionDataPrefix(collection, s3Repo::listSubDirectories);
        if (resolved == null) {
            log.warn("No zk_backup directories found for collection '{}' in S3", collection);
            return;
        }
        s3Repo.downloadPrefix(resolved.joinWith(collection) + "/" + resolved.latestZkBackupName());
    }

    private void downloadZkBackupForDataDir(S3Repo s3Repo, String dataDir) {
        var zkName = SolrBackupLayout.findLatestZkBackupName(s3Repo.listSubDirectories(dataDir));
        if (zkName == null) {
            log.warn("No zk_backup found at bare Solr backup root '{}'", dataDir);
            return;
        }
        s3Repo.downloadPrefix(SolrBackupLayout.joinPrefix(dataDir, zkName));
    }

    private static SolrBackupLayout.BareBackupLayout detectBareSolrLayoutInS3(S3Repo s3Repo, String nameOverride) {
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

    private ClusterReader buildSolrSnapshotReader(
        Path backupDir, List<String> collectionNames, Map<String, String> dataDirByCollection
    ) {
        var schemas = new LinkedHashMap<String, JsonNode>();
        for (var name : collectionNames) {
            var dataDir = dataDirByCollection.getOrDefault(name, name);
            schemas.put(name, SolrSchemaXmlParser.findAndParse(backupDir.resolve(dataDir)));
        }

        if (!arguments.dataFilterArgs.indexAllowlist.isEmpty()) {
            schemas.keySet().retainAll(arguments.dataFilterArgs.indexAllowlist);
        }

        log.atInfo().setMessage("Solr snapshot reader: found {} collection(s) in {}").addArgument(schemas.size()).addArgument(backupDir).log();
        return new SolrSnapshotReader(arguments.sourceVersion, backupDir, schemas, dataDirByCollection);
    }

    ClusterReader getRemoteReader(ConnectionContext connection) {
        return SnapshotReaderRegistry.getRemoteReader(connection, arguments.versionStrictness.allowLooseVersionMatches);
    }

    ClusterReader getSnapshotReader(Version sourceVersion, SourceRepo repo) {
        return SnapshotReaderRegistry.getSnapshotReader(sourceVersion, repo, arguments.versionStrictness.allowLooseVersionMatches);
    }
}
