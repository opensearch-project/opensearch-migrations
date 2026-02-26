/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.solr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JavascriptTransformer;
import org.opensearch.migrations.transform.JsonCompositeTransformer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Data-driven E2E test runner with version matrix support.
 * <p>
 * Test cases are defined in TypeScript ({@code *.testcase.ts}) and compiled to JSON.
 * Every test always compares with real Solr. Assertion rules control how diffs are handled:
 * 'ignore' paths are skipped, 'expect-diff' diffs pass but are logged, unmatched diffs fail.
 */
@Slf4j
@Tag("isolatedTest")
class TransformationShimE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String IDENTITY_JS =
        "(function(bindings) { return function(msg) { return msg; }; })";
    private static final Map<Object, Object> EMPTY_BINDINGS = Map.of();

    @TestFactory
    Stream<DynamicTest> runTestCases() throws Exception {
        var config = loadMatrixConfig();
        var testCases = loadAllTestCases();

        return testCases.stream().flatMap(tc -> {
            var versions = tc.solrVersions() != null && !tc.solrVersions().isEmpty()
                ? tc.solrVersions()
                : config.defaultSolrVersions();
            return versions.stream().map(solrVersion ->
                DynamicTest.dynamicTest(
                    tc.name() + " [" + solrVersion + "]",
                    () -> executeTestCase(tc, solrVersion, config.defaultOpenSearchImage())
                )
            );
        });
    }

    private void executeTestCase(
            TestCaseDefinition tc, String solrImage, String openSearchImage) throws Exception {
        var requestTransform = composeTransforms(tc.requestTransforms());
        var responseTransform = composeTransforms(tc.responseTransforms());
        var plugins = tc.plugins() != null ? tc.plugins() : List.<String>of();

        try (var fixture = new ShimTestFixture(solrImage, openSearchImage, requestTransform, responseTransform)) {
            fixture.start(plugins);

            seedData(fixture, tc);

            var proxyResponse = sendRequest(fixture, fixture.getProxyBaseUrl() + tc.requestPath(), tc);
            var proxyJson = MAPPER.readValue(proxyResponse, new TypeReference<Map<String, Object>>() {});

            compareWithSolr(fixture, tc, proxyJson);

            log.info("PASSED: {} [{}]", tc.name(), solrImage);
        }
    }

    // --- Compare with real Solr ---

    private void compareWithSolr(
        ShimTestFixture fixture, TestCaseDefinition tc, Map<String, Object> proxyJson
    ) throws Exception {
        var solrResponse = sendRequest(fixture, fixture.getSolrBaseUrl() + tc.requestPath(), tc);
        var solrJson = MAPPER.readValue(solrResponse, new TypeReference<Map<String, Object>>() {});

        var rules = tc.assertionRules() != null ? tc.assertionRules() : List.<TestCaseDefinition.AssertionRule>of();
        var diffs = JsonDiff.diff(solrJson, proxyJson, rules);

        // Separate unexpected diffs (no rule or non-expect-diff rule) from expected ones
        var unexpectedDiffs = diffs.stream()
            .filter(d -> d.matchedRule() == null)
            .toList();
        var expectedDiffs = diffs.stream()
            .filter(d -> d.matchedRule() != null)
            .toList();

        if (!expectedDiffs.isEmpty()) {
            log.info("'{}': {} expected difference(s) covered by rules:\n{}",
                tc.name(), expectedDiffs.size(), JsonDiff.formatReport(expectedDiffs));
        }

        if (!unexpectedDiffs.isEmpty()) {
            var report = JsonDiff.formatReport(unexpectedDiffs);
            log.error("Solr vs Proxy diff for '{}':\n{}", tc.name(), report);
            log.info("Full Solr response:\n{}", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(solrJson));
            log.info("Full Proxy response:\n{}", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(proxyJson));
            fail("Proxy response differs from Solr response (" + unexpectedDiffs.size() + " unexpected differences):\n" + report);
        }
    }

    // --- Request dispatch by method ---

    private String sendRequest(ShimTestFixture fixture, String url, TestCaseDefinition tc) throws Exception {
        var method = tc.method() != null ? tc.method().toUpperCase() : "GET";
        return switch (method) {
            case "POST" -> fixture.httpPost(url, tc.requestBody() != null ? tc.requestBody() : "");
            case "PUT" -> fixture.httpPut(url, tc.requestBody() != null ? tc.requestBody() : "");
            case "DELETE" -> fixture.httpDelete(url);
            case "HEAD" -> String.valueOf(fixture.httpHead(url));
            default -> fixture.httpGet(url);
        };
    }

    // --- Data seeding ---

    private void seedData(ShimTestFixture fixture, TestCaseDefinition tc) throws Exception {
        boolean seedSolr = tc.seedSolr() == null || tc.seedSolr();
        boolean seedOpenSearch = tc.seedOpenSearch() == null || tc.seedOpenSearch();

        if (seedSolr) {
            fixture.createSolrCore(tc.collection(), tc.solrSchema());
            fixture.httpPost(
                fixture.getSolrBaseUrl() + "/solr/" + tc.collection() + "/update/json/docs?commit=true",
                MAPPER.writeValueAsString(tc.documents()));
        }

        if (seedOpenSearch) {
            if (tc.opensearchMapping() != null && !tc.opensearchMapping().isEmpty()) {
                fixture.httpPut(
                    fixture.getOpenSearchBaseUrl() + "/" + tc.collection(),
                    MAPPER.writeValueAsString(Map.of("mappings", tc.opensearchMapping())));
            }
            for (var doc : tc.documents()) {
                var id = String.valueOf(doc.get("id"));
                fixture.httpPut(
                    fixture.getOpenSearchBaseUrl() + "/" + tc.collection() + "/_doc/" + id,
                    MAPPER.writeValueAsString(doc));
            }
            fixture.httpPost(fixture.getOpenSearchBaseUrl() + "/" + tc.collection() + "/_refresh", "");
        }
    }

    // --- Transform loading ---

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

    // --- Config & test case loading ---

    private static MatrixConfig loadMatrixConfig() throws IOException {
        try (var stream = TransformationShimE2ETest.class.getResourceAsStream("/transforms/matrix.config.json")) {
            if (stream == null) {
                log.warn("No matrix.config.json found, using defaults");
                return new MatrixConfig(List.of("mirror.gcr.io/library/solr:8"), "mirror.gcr.io/opensearchproject/opensearch:3.3.0");
            }
            return MAPPER.readValue(stream, MatrixConfig.class);
        }
    }

    private static List<TestCaseDefinition> loadAllTestCases() throws IOException {
        var all = new ArrayList<TestCaseDefinition>();
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

    // --- Helpers ---

    private static String loadTransformJs(String name) throws IOException {
        var path = "/transforms/" + name;
        try (var stream = TransformationShimE2ETest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Transform not found on classpath: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
