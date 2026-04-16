package org.opensearch.migrations.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
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
        if (arguments.fileSystemRepoPath != null) {
            backupDir = Path.of(arguments.fileSystemRepoPath);
            try {
                collectionNames = SolrSnapshotReader.discoverCollections(backupDir);
            } catch (IOException e) {
                throw new ParameterException("Failed to list backup directory: " + backupDir + ": " + e.getMessage());
            }
        } else if (arguments.s3LocalDirPath != null) {
            // Solr BACKUP API writes under the s3RepoUri path: s3://<bucket>/<repoPath>/<backupName>/
            var repoUri = new S3Uri(arguments.s3RepoUri);
            String backupS3Uri;
            if (arguments.snapshotName != null) {
                backupS3Uri = repoUri.key.isEmpty()
                    ? "s3://" + repoUri.bucketName + "/" + arguments.snapshotName
                    : "s3://" + repoUri.bucketName + "/" + repoUri.key + "/" + arguments.snapshotName;
            } else {
                backupS3Uri = arguments.s3RepoUri;
            }
            var s3Repo = S3Repo.createRaw(
                Path.of(arguments.s3LocalDirPath),
                new S3Uri(backupS3Uri),
                arguments.s3Region,
                Optional.ofNullable(arguments.s3Endpoint).map(URI::create).orElse(null)
            );
            // Discover collections from S3 listing, download only schema metadata (no index data)
            backupDir = s3Repo.getRepoRootDir();
            collectionNames = s3Repo.listTopLevelDirectories();
            for (var collection : collectionNames) {
                s3Repo.downloadPrefix(collection + "/zk_backup_0");
            }
        } else {
            throw new ParameterException("Solr snapshot requires --file-system-repo-path or S3 args");
        }

        // Parse schemas from backup directory
        var schemas = new LinkedHashMap<String, JsonNode>();
        for (var name : collectionNames) {
            schemas.put(name, SolrSchemaXmlParser.findAndParse(backupDir.resolve(name)));
        }

        if (!arguments.dataFilterArgs.indexAllowlist.isEmpty()) {
            schemas.keySet().retainAll(arguments.dataFilterArgs.indexAllowlist);
        }

        log.atInfo().setMessage("Solr snapshot reader: found {} collection(s) in {}").addArgument(schemas.size()).addArgument(backupDir).log();
        return new SolrSnapshotReader(arguments.sourceVersion, backupDir, schemas);
    }

    ClusterReader getRemoteReader(ConnectionContext connection) {
        return SnapshotReaderRegistry.getRemoteReader(connection, arguments.versionStrictness.allowLooseVersionMatches);
    }

    ClusterReader getSnapshotReader(Version sourceVersion, SourceRepo repo) {
        return SnapshotReaderRegistry.getSnapshotReader(sourceVersion, repo, arguments.versionStrictness.allowLooseVersionMatches);
    }
}
