package org.opensearch.migrations.transform.shim.reporting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.opensearch.testcontainers.OpensearchContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link OpenSearchMetricsSink} using Testcontainers.
 * Validates index template creation, document indexing, and mapping against a real OpenSearch instance.
 */
@Testcontainers
@Tag("longTest")
class OpenSearchMetricsSinkIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    @Container
    static final OpensearchContainer<?> opensearch = new OpensearchContainer<>("opensearchproject/opensearch:2.19.1")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
        ;

    @Test
    void templateCreatedAndDocumentIndexed() throws Exception {
        String uri = "http://localhost:" + opensearch.getMappedPort(9200);
        var sink = new OpenSearchMetricsSink(uri, "test-metrics", 1, 60000,
            null, null, false);

        // Preflight: create index template
        sink.createIndexTemplate();

        // Verify template exists
        var templateResp = httpGet(uri + "/_index_template/test-metrics-template");
        assertEquals(200, templateResp.statusCode(), "Template should be created");
        var templateBody = MAPPER.readTree(templateResp.body());
        assertTrue(templateBody.has("index_templates"), "Response should contain index_templates");

        // Submit a document (bulkSize=1 triggers immediate send)
        sink.submit(new ValidationDocument(
            "2025-03-17T10:00:00Z", "integration-test-1",
            new ValidationDocument.RequestRecord("GET", "/solr/test/select?q=*:*", null, null),
            null, "test-collection", "/solr/{collection}/select",
            100L, 95L, 5.0, 12L, 15L, 3L, null, null
        ));

        // Wait for async send to complete
        Thread.sleep(3000);

        // Refresh index
        httpPost(uri + "/test-metrics-*/_refresh", "");

        // Verify document was indexed
        var searchResp = httpGet(uri + "/test-metrics-*/_search?size=10");
        assertEquals(200, searchResp.statusCode());
        var searchBody = MAPPER.readTree(searchResp.body());
        int totalHits = searchBody.get("hits").get("total").get("value").asInt();
        assertTrue(totalHits >= 1, "Should have at least 1 indexed document, got " + totalHits);

        // Verify mapping has expected fields
        var mappingResp = httpGet(uri + "/test-metrics-*/_mapping");
        assertEquals(200, mappingResp.statusCode());
        String mappingStr = mappingResp.body();
        assertTrue(mappingStr.contains("collection_name"), "Mapping should have collection_name");
        assertTrue(mappingStr.contains("request_id"), "Mapping should have request_id");
        assertTrue(mappingStr.contains("timestamp"), "Mapping should have timestamp");

        sink.close();
    }

    @Test
    void multipleDocumentsIndexedWithBulk() throws Exception {
        String uri = "http://localhost:" + opensearch.getMappedPort(9200);
        var sink = new OpenSearchMetricsSink(uri, "bulk-test", 3, 60000,
            null, null, false);

        sink.createIndexTemplate();

        // Submit 5 docs (bulkSize=3, so first batch of 3 sends immediately, remaining 2 on flush)
        for (int i = 0; i < 5; i++) {
            sink.submit(new ValidationDocument(
                "2025-03-17T10:00:0" + i + "Z", "bulk-" + i,
                null, null, "collection-" + i, null,
                null, null, null, null, null, null, null, null
            ));
        }

        sink.flush();
        Thread.sleep(3000);
        httpPost(uri + "/bulk-test-*/_refresh", "");

        var searchResp = httpGet(uri + "/bulk-test-*/_search?size=10");
        var searchBody = MAPPER.readTree(searchResp.body());
        int totalHits = searchBody.get("hits").get("total").get("value").asInt();
        assertEquals(5, totalHits, "All 5 documents should be indexed");

        sink.close();
    }

    private static HttpResponse<String> httpGet(String url) throws Exception {
        return HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url)).GET()
                .timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> httpPost(String url, String body) throws Exception {
        return HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
