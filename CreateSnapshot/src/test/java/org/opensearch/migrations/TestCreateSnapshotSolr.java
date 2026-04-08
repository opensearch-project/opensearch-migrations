package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for CreateSnapshot against real Solr instances via testcontainers.
 *
 * <p>Regression: standalone Solr core discovery was broken because
 * {@code parseJsonObjectKeys("status")} matched {@code "status":0} in
 * {@code responseHeader} instead of the actual {@code "status":{"dummy":{...}}} object.
 */
@Slf4j
@Testcontainers
@Tag("isolatedTest")
public class TestCreateSnapshotSolr {

    @Container
    static final SolrClusterContainer STANDALONE_SOLR = new SolrClusterContainer(SolrClusterContainer.SOLR_8);

    @Container
    static final SolrClusterContainer CLOUD_SOLR = SolrClusterContainer.cloud(SolrClusterContainer.SOLR_8);

    @Test
    public void testDiscoverCores_standaloneSolr() throws Exception {
        // The standalone container starts with a "dummy" core via solr-precreate
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        var cores = CreateSnapshot.discoverSolrCollections(solrUrl, null, null);

        log.info("Discovered standalone cores: {}", cores);
        Assertions.assertFalse(cores.isEmpty(), "Should discover at least the 'dummy' core");
        Assertions.assertTrue(cores.contains("dummy"), "Should find the 'dummy' core");
    }

    @Test
    public void testDiscoverCollections_solrCloud() throws Exception {
        var solrUrl = CLOUD_SOLR.getSolrUrl();

        // Create a collection in SolrCloud
        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=testcoll&numShards=1&replicationFactor=1&wt=json");

        var collections = CreateSnapshot.discoverSolrCollections(solrUrl, null, null);

        log.info("Discovered SolrCloud collections: {}", collections);
        Assertions.assertTrue(collections.contains("testcoll"), "Should find 'testcoll' collection");
    }

    @Test
    public void testIsSolrCloud_standalone_returnsFalse() {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        boolean isCloud = CreateSnapshot.isSolrCloud(solrUrl, null, null);
        Assertions.assertFalse(isCloud, "Standalone Solr should not be detected as SolrCloud");
    }

    @Test
    public void testIsSolrCloud_cloud_returnsTrue() {
        var solrUrl = CLOUD_SOLR.getSolrUrl();
        boolean isCloud = CreateSnapshot.isSolrCloud(solrUrl, null, null);
        Assertions.assertTrue(isCloud, "SolrCloud should be detected as SolrCloud");
    }

    @Test
    public void testRunSolrBackup_standalone_discoversAndBacksUp() throws Exception {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = solrUrl;
        args.sourceArgs.insecure = true;
        args.snapshotName = "test_standalone_backup";
        args.fileSystemRepoPath = "/var/solr/data";
        args.noWait = false;

        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        // This exercises: version detection → discoverSolrCollections → parseJsonObjectKeys → standalone backup
        creator.run();

        // Verify the backup was created by checking replication details
        var result = STANDALONE_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/dummy/replication?command=details&wt=json");
        log.info("Replication details after backup: {}", result.getStdout());
        Assertions.assertTrue(result.getStdout().contains("backup"),
            "Replication details should show backup info");
    }
}
