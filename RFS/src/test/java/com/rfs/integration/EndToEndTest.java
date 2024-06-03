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
        // PSUEDO: Create a source cluster running ES 6.8
        // PSUEOD: Update global cluster state
        //    - Change a 6.8 relevant setting
        // PSUEOD: Create 2 index templates on the cluster
        //    - logs-*
        //    - data-rolling
        // PSUEOD: Create 5 indices on the cluster
        //    - logs-01-2345
        //    - logs-12-3456
        //    - data-rolling
        //    - playground
        //    - playground2
        // PSUEDO: Add documents 
        //    - 19x http-data docs into logs-01-2345 
        //    - 23x http-data docs into logs-12-3456
        //    - 29x data-rolling
        //    - 5x geonames docs into playground
        //    - 7x geopoint into playground2

        // PSUEDO: Create a target cluster running OS 2.X (Where x is the latest released version)

        // Action
        // PSUEDO: Migrate from the snapshot
        simpleRfsInstance.fullMigrationViaLocalSnapshot(targetCluster.toString());
        // PSUEDO: Shutdown source cluster

        // Validation

        // PSUEOD: Verfiy global cluster state
        //    - 6.8 Setting is transformed
        // PSUEOD: Verify creation of 2 index templates on the cluster
        // PSUEOD: Verify creation of 5 indices on the cluster
        //    - logs-01-2345
        //    - logs-12-3456
        //    - data-rolling
        //    - playground
        //    - playground2
        // PSUEDO: Verify documents
        
        // PSUEDO: Additional validation:
        //   - Mapping type parameter is removed
        // 

        // Looking for inspriation?
        //   - OS Breaking Changes https://opensearch.org/docs/latest/breaking-changes/
        //   - ES Breaking Changes https://www.elastic.co/guide/en/elasticsearch/reference/7.0/breaking-changes-7.0.html
    }
}
