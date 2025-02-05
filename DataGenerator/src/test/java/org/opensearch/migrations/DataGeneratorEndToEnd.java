package org.opensearch.migrations;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;

/**
 * Tests focused on running end to end test cases for Data Generator
 */
@Tag("isolatedTest")
@Slf4j
class DataGeneratorEndToEnd {

    @Test
    void generateData_OS_2_14() throws Exception {
        try (var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)) {
            generateData(targetCluster);
        }
    }

    @Test
    void generateData_ES_6_8() throws Exception {
        try (var targetCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23)) {
            generateData(targetCluster);
        }
    }

    @SneakyThrows
    void generateData(final SearchClusterContainer targetCluster) {
        // ACTION: Set up the target clusters
        targetCluster.start();

        var arguments = new DataGeneratorArgs();
        arguments.targetArgs.host = targetCluster.getUrl();

        // ACTION: Generate the data on the target cluster
        var dataGenerator = new DataGenerator();
        dataGenerator.run(arguments);

        // VERIFY: Get index state on the target cluster
        var requestContext = DocumentMigrationTestContext.factory().noOtelTracking();
        var targetDetails = new SearchClusterRequests(requestContext);
        var client = new RestClient(arguments.targetArgs.toConnectionContext());

        // Make sure the cluster has refreshed before querying it                
        var refreshResponse = client.post("_refresh", "", requestContext.createUnboundRequestContext());
        assertThat(refreshResponse.body, refreshResponse.statusCode, equalTo(200));

        // Confirm all indexes have the expected number of docs
        var defaultCount = arguments.workloadOptions.getTotalDocs();
        var expectedIndexes = Map.of(
            "geonames", defaultCount,
            "logs-181998", defaultCount,
            "logs-191998", defaultCount,
            "logs-201998", defaultCount,
            "logs-211998", defaultCount,
            "logs-221998", defaultCount,
            "logs-231998", defaultCount,
            "logs-241998", defaultCount,
            "sonested", defaultCount,
            "nyc_taxis", defaultCount
        );

        var indexMap = targetDetails.getMapOfIndexAndDocCount(client);
        expectedIndexes.forEach((index, expectedDocs) -> 
            assertThat(indexMap, hasEntry(index, expectedDocs))
        );
    }
}
