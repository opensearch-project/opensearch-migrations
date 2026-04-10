package org.opensearch.migrations.transform.shim.reporting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Tag("isolatedTest")
class OpenSearchMetricsSinkIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    @Container
    static final SearchClusterContainer opensearch = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4);

    private static ConnectionContext ctx(String uri) {
        return new ConnectionContext.IParams() {
            @Override public String getHost() { return uri; }
            @Override public String getUsername() { return null; }
            @Override public String getPassword() { return null; }
            @Override public String getAwsRegion() { return null; }
            @Override public String getAwsServiceSigningName() { return null; }
            @Override public Path getCaCert() { return null; }
            @Override public Path getClientCert() { return null; }
            @Override public Path getClientCertKey() { return null; }
            @Override public boolean isDisableCompression() { return true; }
            @Override public boolean isInsecure() { return true; }
        }.toConnectionContext();
    }

    @Test
    void templateCreatedAndDocumentIndexed() throws Exception {
        String uri = opensearch.getUrl();
        var sink = new OpenSearchMetricsSink(ctx(uri), "test-metrics", 1, 60000);
        sink.createIndexTemplate();

        var templateResp = httpGet(uri + "/_index_template/test-metrics-template");
        assertEquals(200, templateResp.statusCode(), "Template should be created");

        sink.submit(new ValidationDocument(
            "2025-03-17T10:00:00Z", "integration-test-1",
            new ValidationDocument.RequestRecord("GET", "/solr/test/select?q=*:*", null, null),
            null, "test-collection", "/solr/{collection}/select",
            100L, 95L, 5.0, 12L, 15L, 3L, null, null
        ));

        Thread.sleep(3000);
        httpPost(uri + "/test-metrics-*/_refresh", "");

        var searchResp = httpGet(uri + "/test-metrics-*/_search?size=10");
        assertEquals(200, searchResp.statusCode());
        var searchBody = MAPPER.readTree(searchResp.body());
        int totalHits = searchBody.get("hits").get("total").get("value").asInt();
        assertTrue(totalHits >= 1, "Should have at least 1 indexed document, got " + totalHits);

        sink.close();
    }

    @Test
    void multipleDocumentsIndexedWithBulk() throws Exception {
        String uri = opensearch.getUrl();
        var sink = new OpenSearchMetricsSink(ctx(uri), "bulk-test", 3, 60000);
        sink.createIndexTemplate();

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
