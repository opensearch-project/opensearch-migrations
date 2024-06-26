package com.rfs.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.rfs.framework.SearchClusterContainer;
import com.rfs.framework.SimpleRestoreFromSnapshot;

import lombok.extern.slf4j.Slf4j;

/**
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster
 */
@Slf4j
public class EndToEndTest {

    protected Object sourceCluster;
    protected Object targetCluster;
    protected SimpleRestoreFromSnapshot simpleRfsInstance;

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    @Disabled
    public void migrateFrom_ES_v6_8(final SearchClusterContainer.Version targetVersion) throws Exception {
        // Setup
        // PSEUDO: Create a source cluster running ES 6.8
        // PSEUDO: Create 2 templates on the cluster, see https://www.elastic.co/guide/en/elasticsearch/reference/6.8/indices-templates.html
        //    - logs-*
        //    - data-rolling
        // PSEUDO: Create 5 indices on the cluster
        //    - logs-01-2345
        //    - logs-12-3456
        //    - data-rolling
        //    - playground
        //    - playground2
        // PSEUDO: Add documents
        //    - 19x http-data docs into logs-01-2345
        //    - 23x http-data docs into logs-12-3456
        //    - 29x data-rolling
        //    - 5x geonames docs into playground
        //    - 7x geopoint into playground2

        // PSEUDO: Create a target cluster running OS 2.X (Where x is the latest released version)

        // Action
        // PSEUDO: Migrate from the snapshot
        // simpleRfsInstance.fullMigrationViaLocalSnapshot(targetCluster.toString());
        // PSEUDO: Shutdown source cluster

        // Validation

        // PSEUDO: Verify creation of 2 index templates on the cluster
        // PSEUDO: Verify creation of 5 indices on the cluster
        //    - logs-01-2345
        //    - logs-12-3456
        //    - data-rolling
        //    - playground
        //    - playground2
        // PSEUDO: Verify documents

        // PSEUDO: Additional validation:
        if (SearchClusterContainer.OS_V2_14_0.equals(targetVersion)) {
            //   - Mapping type parameter is removed https://opensearch.org/docs/latest/breaking-changes/#remove-mapping-types-parameter
        }
    }

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    @Disabled
    public void migrateFrom_ES_v7_10(final SearchClusterContainer.Version targetVersion) throws Exception {
        // Setup
        // PSEUDO: Create a source cluster running ES 6.8

        migrateFrom_ES_v7_X(null);
    }

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    @Disabled
    public void migrateFrom_ES_v7_17(final SearchClusterContainer.Version targetVersion) throws Exception {
        // Setup
        // PSEUDO: Create a source cluster running ES 6.8

        migrateFrom_ES_v7_X(null);
    }

    private void migrateFrom_ES_v7_X(final SearchClusterContainer sourceCluster) {
        // PSEUDO: Create 2 index templates on the cluster, see https://www.elastic.co/guide/en/elasticsearch/reference/7.17/index-templates.html
        //    - logs-*
        //    - data-rolling
        // PSEUDO: Create 5 indices on the cluster
        //    - logs-01-2345
        //    - logs-12-3456
        //    - data-rolling
        //    - playground
        //    - playground2
        // PSEUDO: Add documents
        //    - 19x http-data docs into logs-01-2345
        //    - 23x http-data docs into logs-12-3456
        //    - 29x data-rolling
        //    - 5x geonames docs into playground
        //    - 7x geopoint into playground2

        // PSEUDO: Create a target cluster running OS 2.X (Where x is the latest released version)

        // Action
        // PSEUDO: Migrate from the snapshot
        // simpleRfsInstance.fullMigrationViaLocalSnapshot(targetCluster.toString());
        // PSEUDO: Shutdown source cluster

        // Validation

        // PSEUDO: Verify creation of 2 index templates on the clustqer
        // PSEUDO: Verify creation of 5 indices on the cluster
        //    - logs-01-2345
        //    - logs-12-3456
        //    - data-rolling
        //    - playground
        //    - playground2
        // PSEUDO: Verify documents

        // PSEUDO: Additional validation:
        //   - Mapping type parameter is removed
        //
    }
}
