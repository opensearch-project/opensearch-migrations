package org.opensearch.migrations;

import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;

/**
 * Tests focused on running end-to-end test cases for Data Generator
 */
@Tag("isolatedTest")
@Slf4j
class DataGeneratorEndToEndTest {

    private static Stream<Arguments> scenarios() {
        return SupportedClusters.supportedSourcesOrTargets(true)
                .stream()
                // Exclude ES 5 from DataGenerator as not currently supported
                .filter(version -> !VersionMatchers.isES_5_X.test(version.getVersion()))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "Cluster {0}")
    @MethodSource(value = "scenarios")
    void generateData(SearchClusterContainer.ContainerVersion version) {
        try (var cluster = new SearchClusterContainer(version)) {
            generateData(cluster);
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
