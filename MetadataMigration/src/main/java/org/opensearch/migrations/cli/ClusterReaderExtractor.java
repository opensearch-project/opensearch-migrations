package org.opensearch.migrations.cli;

import java.nio.file.Path;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.cluster.ClusterReader;

import com.beust.jcommander.ParameterException;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.S3Repo;
import com.rfs.common.S3Uri;
import com.rfs.common.SourceRepo;
import com.rfs.common.http.ConnectionContext;
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
        
        SourceRepo repo = null;
        if (arguments.fileSystemRepoPath != null) {
            repo = new FileSystemRepo(Path.of(arguments.fileSystemRepoPath));
        } else if (arguments.s3LocalDirPath != null) {
            repo = S3Repo.create(Path.of(arguments.s3LocalDirPath), new S3Uri(arguments.s3RepoUri), arguments.s3Region);
        } else {
            throw new ParameterException("Unable to find valid resource provider");
        }

        return getSnapshotReader(arguments.sourceVersion, repo);
    }

    ClusterReader getRemoteReader(ConnectionContext connection) {
        return ClusterProviderRegistry.getRemoteReader(connection);
    }

    ClusterReader getSnapshotReader(Version sourceVersion, SourceRepo repo) {
        return ClusterProviderRegistry.getSnapshotReader(sourceVersion, repo);
    }
}
