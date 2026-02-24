/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JavascriptTransformer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.opensearch.testcontainers.OpensearchContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
class TransformationShimE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // JS script: transforms Solr select request → OpenSearch _search request
    private static final String REQUEST_TRANSFORM_JS =
        "(function(bindings) {\n" +
        "  return function(msg) {\n" +
        "    var uri = msg.URI;\n" +
        "    var match = uri.match(/\\/solr\\/([^\\/]+)\\/select/);\n" +
        "    if (match) {\n" +
        "      var collection = match[1];\n" +
        "      msg.URI = '/' + collection + '/_search';\n" +
        "      msg.method = 'POST';\n" +
        "      msg.payload = { inlinedTextBody: JSON.stringify({query:{match_all:{}}}) };\n" +
        "      if (!msg.headers) msg.headers = {};\n" +
        "      msg.headers['content-type'] = 'application/json';\n" +
        "    }\n" +
        "    return msg;\n" +
        "  };\n" +
        "})";

    // JS script: transforms OpenSearch _search response → Solr select response
    private static final String RESPONSE_TRANSFORM_JS =
        "(function(bindings) {\n" +
        "  return function(msg) {\n" +
        "    var payload = msg.payload;\n" +
        "    if (payload && payload.inlinedTextBody) {\n" +
        "      var osResp = JSON.parse(payload.inlinedTextBody);\n" +
        "      if (osResp.hits) {\n" +
        "        var docs = [];\n" +
        "        for (var i = 0; i < osResp.hits.hits.length; i++) {\n" +
        "          docs.push(osResp.hits.hits[i]._source);\n" +
        "        }\n" +
        "        var solrResp = {\n" +
        "          responseHeader: { status: 0, QTime: 0 },\n" +
        "          response: {\n" +
        "            numFound: osResp.hits.total.value,\n" +
        "            start: 0,\n" +
        "            docs: docs\n" +
        "          }\n" +
        "        };\n" +
        "        payload.inlinedTextBody = JSON.stringify(solrResp);\n" +
        "      }\n" +
        "    }\n" +
        "    return msg;\n" +
        "  };\n" +
        "})";

    @Test
    void solrQueryViaProxyMatchesDirectSolrQuery() throws Exception {
        // Use an empty map as bindings context for the JS transformers
        var emptyBindings = Map.of();

        try (
            var solr = createSolrContainer();
            var opensearch = createOpenSearchContainer()
        ) {
            solr.start();
            opensearch.start();

            var solrBaseUrl = "http://" + solr.getHost() + ":" + solr.getMappedPort(8983);
            var osBaseUrl = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);

            // 1. Set up Solr: create core and index a document
            execInContainer(solr, "solr", "create_core", "-c", "testcollection");
            Thread.sleep(2000); // wait for core creation

            httpPost(solrBaseUrl + "/solr/testcollection/update/json/docs?commit=true",
                "[{\"id\":\"1\",\"title\":\"test document\",\"content\":\"hello world\"}]");

            // 2. Set up OpenSearch: index the same document
            httpPut(osBaseUrl + "/testcollection/_doc/1",
                "{\"id\":\"1\",\"title\":\"test document\",\"content\":\"hello world\"}");
            httpPost(osBaseUrl + "/testcollection/_refresh", "");

            // 3. Query Solr directly
            var solrResponse = httpGet(solrBaseUrl + "/solr/testcollection/select?q=*:*&wt=json");
            log.info("Direct Solr response: {}", solrResponse);
            var solrJson = MAPPER.readValue(solrResponse, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            var solrDocs = (List<Map<String, Object>>)
                ((Map<String, Object>) solrJson.get("response")).get("docs");
            assertNotNull(solrDocs);
            assertFalse(solrDocs.isEmpty(), "Solr should return documents");

            // 4. Start the transformation proxy in front of OpenSearch
            IJsonTransformer reqTransformer = new JavascriptTransformer(REQUEST_TRANSFORM_JS, emptyBindings);
            IJsonTransformer respTransformer = new JavascriptTransformer(RESPONSE_TRANSFORM_JS, emptyBindings);

            int proxyPort = findFreePort();
            var proxy = new TransformationShimProxy(
                proxyPort, URI.create(osBaseUrl), reqTransformer, respTransformer);
            proxy.start();

            try {
                // 5. Query the proxy with the same Solr-format request
                var proxyBaseUrl = "http://localhost:" + proxyPort;
                var proxyResponse = httpGet(proxyBaseUrl + "/solr/testcollection/select?q=*:*&wt=json");
                log.info("Proxy response: {}", proxyResponse);
                var proxyJson = MAPPER.readValue(proxyResponse, new TypeReference<Map<String, Object>>() {});

                // 6. Compare: both should have the same document data
                @SuppressWarnings("unchecked")
                var proxyDocs = (List<Map<String, Object>>)
                    ((Map<String, Object>) proxyJson.get("response")).get("docs");
                assertNotNull(proxyDocs, "Proxy response should contain docs");
                assertEquals(solrDocs.size(), proxyDocs.size(), "Same number of documents");

                // Compare the actual document fields (ignoring Solr-specific fields like _version_)
                var solrDoc = filterDocFields(solrDocs.get(0));
                var proxyDoc = filterDocFields(proxyDocs.get(0));
                assertEquals(normalizeValue(solrDoc.get("id")), normalizeValue(proxyDoc.get("id")),
                    "Document id should match");
                assertEquals(normalizeValue(solrDoc.get("title")), normalizeValue(proxyDoc.get("title")),
                    "Document title should match");
                assertEquals(normalizeValue(solrDoc.get("content")), normalizeValue(proxyDoc.get("content")),
                    "Document content should match");

                log.info("SUCCESS: Proxy response matches Solr response for document data");
            } finally {
                proxy.stop();
                reqTransformer.close();
                respTransformer.close();
            }
        }
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> createSolrContainer() {
        return new GenericContainer<>(DockerImageName.parse("solr:8"))
            .withExposedPorts(8983)
            .waitingFor(Wait.forHttp("/solr/admin/info/system")
                .forPort(8983)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    @SuppressWarnings("resource")
    private static OpensearchContainer<?> createOpenSearchContainer() {
        return new OpensearchContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.0.0"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "Admin123!")
            .waitingFor(Wait.forHttp("/")
                .forPort(9200)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static void execInContainer(GenericContainer<?> container, String... command) throws Exception {
        var result = container.execInContainer(command);
        log.info("exec {} → exit={}, stdout={}, stderr={}",
            String.join(" ", command), result.getExitCode(), result.getStdout(), result.getStderr());
    }

    /** Filter to only the fields we indexed (ignore Solr metadata like _version_) */
    private static Map<String, Object> filterDocFields(Map<String, Object> doc) {
        return Map.of(
            "id", doc.get("id"),
            "title", doc.get("title"),
            "content", doc.get("content")
        );
    }

    /** Normalize: unwrap single-element lists (Solr returns multi-valued fields as arrays) */
    private static Object normalizeValue(Object val) {
        if (val instanceof List) {
            var list = (List<?>) val;
            return list.size() == 1 ? list.get(0) : val;
        }
        return val;
    }

    private static String httpGet(String url) throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void httpPost(String url, String body) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("POST {} → {}: {}", url, resp.statusCode(), resp.body());
    }

    private static void httpPut(String url, String body) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("PUT {} → {}: {}", url, resp.statusCode(), resp.body());
    }

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
