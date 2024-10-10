package org.opensearch.migrations;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests focused on running end to end test cases for Data Generator
 */
// @Tag("isolatedTest")
@Slf4j
class DataGeneratorEndToEnd {

    @Test
    void generateData_OS_2_14() throws Exception {
        try (var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)) {
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
        var defaultCount = arguments.workloadOptions.totalDocs;
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

        var ops = new ClusterOperations(targetCluster.getUrl());
        System.err.println("geonames:\n" + ops.get("/geonames").getValue() + "\n" + ops.get("/geonames/_search?size=5").getValue());
        System.err.println("sonested:\n" + ops.get("/sonested").getValue() + "\n" + ops.get("/sonested/_search?size=5").getValue());
        System.err.println("logs-211998:\n" + ops.get("/logs-211998").getValue() + "\n" + ops.get("/logs-211998/_search?size=5").getValue());
        System.err.println("nyc_taxis:\n" + ops.get("/nyc_taxis").getValue() + "\n" + ops.get("/nyc_taxis/_search?size=5").getValue());
        var cat = ops.get("/_cat/indices?v");
        System.err.println("indices:\n" + cat.getValue());
        fail("Done!");

    }
}
