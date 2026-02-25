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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JavascriptTransformer;
import org.opensearch.migrations.transform.JsonCompositeTransformer;
import org.opensearch.migrations.transform.shim.TransformationLibrary.TransformationPair;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-driven E2E test runner. Test cases are defined in TypeScript
 * ({@code *.testcase.ts}) and compiled to JSON on the classpath.
 */
@Slf4j
class TransformationShimE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String IDENTITY_JS =
        "(function(bindings) { return function(msg) { return msg; }; })";
    private static final Map<Object, Object> EMPTY_BINDINGS = Map.of();

    @TestFactory
    Stream<DynamicTest> runTestCases() throws Exception {
        var testCases = loadAllTestCases();
        return testCases.stream().map(tc ->
            DynamicTest.dynamicTest(tc.name(), () -> executeTestCase(tc))
        );
    }

    private void executeTestCase(TestCaseDefinition tc) throws Exception {
        var transforms = buildTransforms(tc.requestTransforms(), tc.responseTransforms());

        try (var fixture = new ShimTestFixture(
                "solr:8", "opensearchproject/opensearch:3.0.0", transforms)) {
            fixture.start();

            seedData(fixture, tc);

            var response = fixture.httpGet(fixture.getProxyBaseUrl() + tc.requestPath());
            var json = MAPPER.readValue(response, new TypeReference<Map<String, Object>>() {});

            assertResponseFormat(json, tc);
            assertExpectedDocs(json, tc);

            log.info("PASSED: {}", tc.name());
        }
    }

    // --- Data seeding ---

    private void seedData(ShimTestFixture fixture, TestCaseDefinition tc) throws Exception {
        boolean seedSolr = tc.seedSolr() == null || tc.seedSolr();
        boolean seedOpenSearch = tc.seedOpenSearch() == null || tc.seedOpenSearch();

        if (seedSolr) {
            fixture.createSolrCore(tc.collection());
            fixture.httpPost(
                fixture.getSolrBaseUrl() + "/solr/" + tc.collection() + "/update/json/docs?commit=true",
                MAPPER.writeValueAsString(tc.documents()));
        }

        if (seedOpenSearch) {
            for (var doc : tc.documents()) {
                var id = String.valueOf(doc.get("id"));
                fixture.httpPut(
                    fixture.getOpenSearchBaseUrl() + "/" + tc.collection() + "/_doc/" + id,
                    MAPPER.writeValueAsString(doc));
            }
            fixture.httpPost(fixture.getOpenSearchBaseUrl() + "/" + tc.collection() + "/_refresh", "");
        }
    }

    // --- Assertions ---

    private void assertResponseFormat(Map<String, Object> json, TestCaseDefinition tc) {
        if (tc.assertResponseFormat() == null) return;
        if ("solr".equals(tc.assertResponseFormat())) {
            assertNotNull(json.get("response"), "Expected Solr format (has 'response')");
        } else if ("opensearch".equals(tc.assertResponseFormat())) {
            assertNotNull(json.get("hits"), "Expected OpenSearch format (has 'hits')");
        }
    }

    @SuppressWarnings("unchecked")
    private void assertExpectedDocs(Map<String, Object> json, TestCaseDefinition tc) {
        if (tc.expectedDocs() == null || tc.expectedDocs().isEmpty()) return;

        var responseDocs = extractDocs(json);
        assertNotNull(responseDocs, "Response should contain documents");
        assertFalse(responseDocs.isEmpty(), "Response should have at least one document");

        var fields = tc.expectedFields();
        if (fields == null || fields.isEmpty()) {
            fields = new ArrayList<>(tc.expectedDocs().get(0).keySet());
        }

        // Sort both lists by id for stable comparison
        var sortedExpected = tc.expectedDocs().stream()
            .sorted((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))))
            .toList();
        var sortedActual = responseDocs.stream()
            .sorted((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))))
            .toList();

        assertEquals(sortedExpected.size(), sortedActual.size(),
            "Document count mismatch");

        for (int i = 0; i < sortedExpected.size(); i++) {
            for (var field : fields) {
                assertEquals(
                    normalize(sortedExpected.get(i).get(field)),
                    normalize(sortedActual.get(i).get(field)),
                    "Field '" + field + "' mismatch in doc " + i);
            }
        }
    }

    // --- Transform loading ---

    private static TransformationPair buildTransforms(
            List<String> requestTransforms, List<String> responseTransforms) throws IOException {
        return new TransformationPair(
            composeTransforms(requestTransforms),
            composeTransforms(responseTransforms));
    }

    private static IJsonTransformer composeTransforms(List<String> names) throws IOException {
        if (names == null || names.isEmpty()) {
            return new JavascriptTransformer(IDENTITY_JS, EMPTY_BINDINGS);
        }
        var transformers = new IJsonTransformer[names.size()];
        for (int i = 0; i < names.size(); i++) {
            transformers[i] = new JavascriptTransformer(loadTransformJs(names.get(i) + ".js"), EMPTY_BINDINGS);
        }
        return transformers.length == 1 ? transformers[0] : new JsonCompositeTransformer(transformers);
    }

    // --- Helpers ---

    private static List<TestCaseDefinition> loadAllTestCases() throws IOException {
        var all = new ArrayList<TestCaseDefinition>();
        // Scan classpath for all *.testcases.json files
        var classLoader = TransformationShimE2ETest.class.getClassLoader();
        var resources = classLoader.getResources("transforms");
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            var dir = new java.io.File(url.getPath());
            if (dir.isDirectory()) {
                for (var file : dir.listFiles((d, name) -> name.endsWith(".testcases.json"))) {
                    var cases = MAPPER.readValue(file, new TypeReference<List<TestCaseDefinition>>() {});
                    all.addAll(cases);
                    log.info("Loaded {} test case(s) from {}", cases.size(), file.getName());
                }
            }
        }
        assertTrue(!all.isEmpty(), "No test cases found on classpath");
        return all;
    }

    private static String loadTransformJs(String name) throws IOException {
        var path = "/transforms/" + name;
        try (var stream = TransformationShimE2ETest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Transform not found on classpath: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractDocs(Map<String, Object> json) {
        // Try Solr format first
        var response = (Map<String, Object>) json.get("response");
        if (response != null) {
            return (List<Map<String, Object>>) response.get("docs");
        }
        // Try OpenSearch format
        var hits = (Map<String, Object>) json.get("hits");
        if (hits != null) {
            var hitList = (List<Map<String, Object>>) hits.get("hits");
            if (hitList != null) {
                return hitList.stream()
                    .map(h -> (Map<String, Object>) h.get("_source"))
                    .toList();
            }
        }
        return null;
    }

    /** Unwrap single-element lists (Solr returns multi-valued fields as arrays). */
    private static Object normalize(Object val) {
        if (val instanceof List<?> list && list.size() == 1) return list.get(0);
        return val;
    }
}
