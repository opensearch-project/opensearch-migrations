package org.opensearch.migrations.cli;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;

import com.beust.jcommander.ParameterException;
import lombok.AllArgsConstructor;

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

        if (arguments.sourceArgs != null && arguments.sourceArgs.host != null) {
            return getRemoteReader(arguments.sourceArgs.toConnectionContext());
        }

        if (arguments.sourceVersion == null) {
            throw new ParameterException("Unable to read from snapshot without --source-version parameter");
        }

        // Get file finder
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

        if (arguments.sourceVersion == null) {
            throw new ParameterException("Unable to read from snapshot without --source-version parameter");
        }
        return getSnapshotReader(arguments.sourceVersion, repo);
    }

    ClusterReader getRemoteReader(ConnectionContext connection) {
        return SnapshotReaderRegistry.getRemoteReader(connection, arguments.versionStrictness.allowLooseVersionMatches);
    }

    ClusterReader getSnapshotReader(Version sourceVersion, SourceRepo repo) {
        return SnapshotReaderRegistry.getSnapshotReader(sourceVersion, repo, arguments.versionStrictness.allowLooseVersionMatches);
    }
}
