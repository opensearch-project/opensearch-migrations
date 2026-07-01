package org.opensearch.migrations;

import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RepoUri;
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
        var snapshotCreator = new SnapshotCreator(
            args.snapshotName,
            args.snapshotRepoName,
            client,
            parsedUri,
            args.indexAllowlist,
            context,
            args.compressionEnabled,
            args.includeGlobalState,
            args.s3Region,
            args.endpoint,
            args.maxSnapshotRateMBPerNode,
            args.s3RoleArn
        );

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
