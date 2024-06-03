package com.rfs.integration;

import org.junit.jupiter.api.Test;
import com.rfs.framework.SimpleRestoreFromSnapshot;

/**
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster
 */
public class EndToEndTest {

    public Object sourceCluster;
    public Object targetCluster;
    public SimpleRestoreFromSnapshot simpleRfsInstance;

    @Test
    public void ES_v6_8_to_OS_v2_X_Migration() throws Exception {
        // Setup
        // PSEUDO: Create a source cluster running ES 6.8
        // PSEUDO: Update global cluster state
        //    - Change a 6.8 relevant setting
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
        simpleRfsInstance.fullMigrationViaLocalSnapshot(targetCluster.toString());
        // PSEUDO: Shutdown source cluster

        // Validation

        // PSEUDO: Verfiy global cluster state
        //    - 6.8 Setting is transformed
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

        // Looking for inspriation?
        //   - OS Breaking Changes https://opensearch.org/docs/latest/breaking-changes/
        //   - ES Breaking Changes https://www.elastic.co/guide/en/elasticsearch/reference/7.0/breaking-changes-7.0.html
    }
}
