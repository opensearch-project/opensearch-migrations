package org.opensearch.migrations;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for Solr backup via testcontainers.
 */
@Slf4j
@Testcontainers
@Tag("isolatedTest")
public class TestCreateSnapshotSolr {

    @Container
    static final SolrClusterContainer STANDALONE_SOLR = new SolrClusterContainer(SolrClusterContainer.SOLR_8);

    @Container
    static final SolrClusterContainer CLOUD_SOLR = SolrClusterContainer.cloud(SolrClusterContainer.SOLR_8);

    private static SolrHttpClient clientFor(SolrClusterContainer container) {
        return new SolrHttpClient(connectionContextFor(container));
    }

    private static ConnectionContext connectionContextFor(SolrClusterContainer container) {
        return new ConnectionContext.SourceArgs() {{ host = container.getSolrUrl(); insecure = true; }}.toConnectionContext();
    }

    @Test
    public void testDiscoverCores_standaloneSolr() throws Exception {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        var cores = SolrBackupStrategy.discoverCollections(solrUrl, clientFor(STANDALONE_SOLR));

        log.atInfo().setMessage("Discovered standalone cores: {}").addArgument(cores).log();
        Assertions.assertFalse(cores.isEmpty(), "Should discover at least the 'dummy' core");
        Assertions.assertTrue(cores.contains("dummy"), "Should find the 'dummy' core");
    }

    @Test
    public void testDiscoverCollections_solrCloud() throws Exception {
        var solrUrl = CLOUD_SOLR.getSolrUrl();

        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=testcoll&numShards=1&replicationFactor=1&wt=json");

        var collections = SolrBackupStrategy.discoverCollections(solrUrl, clientFor(CLOUD_SOLR));

        log.atInfo().setMessage("Discovered SolrCloud collections: {}").addArgument(collections).log();
        Assertions.assertTrue(collections.contains("testcoll"), "Should find 'testcoll' collection");
    }

    @Test
    public void testIsSolrCloud_standalone_returnsFalse() {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        Assertions.assertFalse(SolrBackupStrategy.isSolrCloud(solrUrl, clientFor(STANDALONE_SOLR)),
            "Standalone Solr should not be detected as SolrCloud");
    }

    @Test
    public void testIsSolrCloud_cloud_returnsTrue() {
        var solrUrl = CLOUD_SOLR.getSolrUrl();
        Assertions.assertTrue(SolrBackupStrategy.isSolrCloud(solrUrl, clientFor(CLOUD_SOLR)),
            "SolrCloud should be detected as SolrCloud");
    }

    @Test
    public void testRunSolrBackup_standalone_discoversAndBacksUp() throws Exception {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = solrUrl;
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = "test_standalone_backup";
        args.fileSystemRepoPath = "/var/solr/data";
        args.noWait = false;

        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        var result = STANDALONE_SOLR.execInContainer("ls", "/var/solr/data");
        log.atInfo().setMessage("Backup directory contents: {}").addArgument(result.getStdout()).log();
        Assertions.assertTrue(result.getStdout().contains("snapshot.test_standalone_backup"),
            "Backup directory should contain the snapshot");
    }

    @Test
    public void testRunSolrBackup_cloud_discoversAndBacksUp() throws Exception {
        var solrUrl = CLOUD_SOLR.getSolrUrl();
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=cloudcoll&numShards=1&replicationFactor=1&wt=json");
        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/cloudcoll/update?commit=true",
            "-H", "Content-Type: application/json",
            "-d", "[{\"id\":\"doc1\",\"title\":\"test\"}]");

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = solrUrl;
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = "test_cloud_backup";
        args.fileSystemRepoPath = "/var/solr/data/backups";
        args.noWait = false;

        CLOUD_SOLR.execInContainer("mkdir", "-p", "/var/solr/data/backups");

        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        var result = CLOUD_SOLR.execInContainer("ls", "/var/solr/data/backups");
        log.atInfo().setMessage("Cloud backup directory contents: {}").addArgument(result.getStdout()).log();
        Assertions.assertTrue(result.getStdout().contains("test_cloud_backup"),
            "Backup directory should contain the backup");
    }
}
