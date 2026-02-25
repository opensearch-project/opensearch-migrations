/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.JavascriptTransformer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
class TransformationShimE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Identity transform — passes through unchanged */
    private static final String IDENTITY_JS =
        "(function(bindings) { return function(msg) { return msg; }; })";

    /** Load a transpiled JS transform from the classpath (built from TypeScript). */
    private static String loadTransformJs(String name) throws IOException {
        var path = "/transforms/" + name;
        try (var stream = TransformationShimE2ETest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Transform not found on classpath: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static TransformationLibrary buildSolrToOpenSearchLibrary() throws IOException {
        var bindings = Map.of();
        return new TransformationLibrary()
            .register("solr-uri-rewrite",
                new JavascriptTransformer(loadTransformJs("solr-to-opensearch-request.js"), bindings),
                new JavascriptTransformer(IDENTITY_JS, bindings))
            .register("solr-response-format",
                new JavascriptTransformer(IDENTITY_JS, bindings),
                new JavascriptTransformer(loadTransformJs("solr-to-opensearch-response.js"), bindings));
    }

    @Test
    void solrQueryViaProxyMatchesDirectSolrQuery() throws Exception {
        var library = buildSolrToOpenSearchLibrary();
        var transforms = library.composeAll();

        try (var fixture = new ShimTestFixture("solr:8", "opensearchproject/opensearch:3.0.0", transforms)) {
            fixture.start();

            // Set up Solr
            fixture.createSolrCore("testcollection");
            fixture.httpPost(fixture.getSolrBaseUrl() + "/solr/testcollection/update/json/docs?commit=true",
                "[{\"id\":\"1\",\"title\":\"test document\",\"content\":\"hello world\"}]");

            // Set up OpenSearch with same data
            fixture.httpPut(fixture.getOpenSearchBaseUrl() + "/testcollection/_doc/1",
                "{\"id\":\"1\",\"title\":\"test document\",\"content\":\"hello world\"}");
            fixture.httpPost(fixture.getOpenSearchBaseUrl() + "/testcollection/_refresh", "");

            // Query Solr directly
            var solrDocs = getSolrDocs(fixture.httpGet(
                fixture.getSolrBaseUrl() + "/solr/testcollection/select?q=*:*&wt=json"));
            assertNotNull(solrDocs);
            assertFalse(solrDocs.isEmpty());

            // Query via proxy (Solr request → OpenSearch → Solr response)
            var proxyDocs = getSolrDocs(fixture.httpGet(
                fixture.getProxyBaseUrl() + "/solr/testcollection/select?q=*:*&wt=json"));
            assertNotNull(proxyDocs);
            assertEquals(solrDocs.size(), proxyDocs.size());

            // Compare document fields
            assertDocFieldsMatch(solrDocs.get(0), proxyDocs.get(0), "id", "title", "content");
            log.info("SUCCESS: Proxy response matches Solr response");
        }
    }

    @Test
    void composeSelectedTransformationsOnly() throws Exception {
        var library = buildSolrToOpenSearchLibrary();
        // Only compose the URI rewrite — response stays in OpenSearch format
        var transforms = library.compose("solr-uri-rewrite");

        try (var fixture = new ShimTestFixture("solr:8", "opensearchproject/opensearch:3.0.0", transforms)) {
            fixture.start();

            fixture.httpPut(fixture.getOpenSearchBaseUrl() + "/testcollection/_doc/1",
                "{\"id\":\"1\",\"title\":\"test\"}");
            fixture.httpPost(fixture.getOpenSearchBaseUrl() + "/testcollection/_refresh", "");

            // Query via proxy — request is rewritten, but response is raw OpenSearch format
            var response = fixture.httpGet(
                fixture.getProxyBaseUrl() + "/solr/testcollection/select?q=*:*");
            var json = MAPPER.readValue(response, new TypeReference<Map<String, Object>>() {});

            // Should have OpenSearch "hits" structure (not Solr "response" structure)
            assertNotNull(json.get("hits"), "Response should be in OpenSearch format (has 'hits')");
            log.info("SUCCESS: Selective composition works — only URI rewrite applied");
        }
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getSolrDocs(String responseBody) throws Exception {
        var json = MAPPER.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
        return (List<Map<String, Object>>) ((Map<String, Object>) json.get("response")).get("docs");
    }

    private static void assertDocFieldsMatch(Map<String, Object> expected, Map<String, Object> actual,
                                              String... fields) {
        for (var field : fields) {
            assertEquals(normalize(expected.get(field)), normalize(actual.get(field)),
                "Field '" + field + "' should match");
        }
    }

    /** Unwrap single-element lists (Solr returns multi-valued fields as arrays). */
    private static Object normalize(Object val) {
        if (val instanceof List<?> list && list.size() == 1) return list.get(0);
        return val;
    }
}
