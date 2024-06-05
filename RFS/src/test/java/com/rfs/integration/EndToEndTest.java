package com.rfs.integration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.rfs.framework.OpenSearchContainer;
import com.rfs.framework.SimpleRestoreFromSnapshot;

/**
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster
 */
public class EndToEndTest {

    protected Object sourceCluster;
    protected Object targetCluster;
    protected SimpleRestoreFromSnapshot simpleRfsInstance;

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    public void ES_v6_8_Migration(final OpenSearchContainer.Version targetVersion) throws Exception {
        // Setup
        // PSEUDO: Create a source cluster running ES 6.8
        // PSEUDO: Create 2 index templates on the cluster
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
        //   - Mapping type parameter is removed
        // 
    }

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    public void ES_v7_10_Migration(final OpenSearchContainer.Version targetVersion) throws Exception {
        // Placeholder
    }

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    public void ES_v7_17_Migration(final OpenSearchContainer.Version targetVersion) throws Exception {
        // Placeholder
    }
}
