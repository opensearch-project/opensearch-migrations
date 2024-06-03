package com.rfs.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.ArgumentCaptor;

import com.rfs.common.OpenSearchClient;
import com.rfs.framework.ClusterOperations;
import com.rfs.framework.ElasticsearchContainer;
import com.rfs.framework.SimpleRestoreFromSnapshot;
import com.rfs.framework.SimpleRestoreFromSnapshot_ES_6_8;
import com.rfs.framework.SimpleRestoreFromSnapshot_ES_7_10;
import com.rfs.framework.ElasticsearchContainer.Version;

import lombok.Builder;
import lombok.Data;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.util.List;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.function.Supplier;

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
        // PSUEDO: Create snapshot on source cluster
        // PSUEDO: Migrate from the snapshot
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
