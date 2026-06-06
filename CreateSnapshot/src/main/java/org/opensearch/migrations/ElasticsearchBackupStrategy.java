package org.opensearch.migrations;

import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.GcsSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.S3SnapshotCreator;
import org.opensearch.migrations.bulkload.common.SnapshotCreator;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICreateSnapshotContext;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;

import lombok.extern.slf4j.Slf4j;

/**
 * Backup strategy for Elasticsearch / OpenSearch sources.
 * Creates a snapshot via the cluster's snapshot/restore API.
 */
@Slf4j
public class ElasticsearchBackupStrategy implements SourceBackupStrategy {

    private final CreateSnapshot.Args args;
    private final ICreateSnapshotContext context;

    public ElasticsearchBackupStrategy(CreateSnapshot.Args args, ICreateSnapshotContext context) {
        this.args = args;
        this.context = context;
    }

    @Override
    public void run() {
        var clientFactory = new OpenSearchClientFactory(args.sourceArgs.toConnectionContext());
        var client = clientFactory.determineVersionAndCreate();

        var parsedUri = RepoUri.parse(args.repoUri);
        SnapshotCreator snapshotCreator = switch (parsedUri) {
            case RepoUri.FileRepoUri f -> new FileSystemSnapshotCreator(
                args.snapshotName,
                args.snapshotRepoName,
                client,
                f.path(),
                args.indexAllowlist,
                context,
                args.compressionEnabled,
                args.includeGlobalState
            );
            case RepoUri.GcsRepoUri g -> new GcsSnapshotCreator(
                args.snapshotName,
                args.snapshotRepoName,
                client,
                g.rawUri(),
                args.indexAllowlist,
                args.maxSnapshotRateMBPerNode,
                context,
                args.compressionEnabled,
                args.includeGlobalState
            );
            case RepoUri.S3RepoUri s -> new S3SnapshotCreator(
                args.snapshotName,
                args.snapshotRepoName,
                client,
                s.rawUri(),
                args.s3Region,
                args.s3Endpoint,
                args.indexAllowlist,
                args.maxSnapshotRateMBPerNode,
                args.s3RoleArn,
                context,
                args.compressionEnabled,
                args.includeGlobalState
            );
        };

        try {
            if (args.noWait) {
                SnapshotRunner.run(snapshotCreator);
            } else {
                SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            }
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Unexpected error running CreateSnapshot").log();
            throw e;
        }
    }
}
