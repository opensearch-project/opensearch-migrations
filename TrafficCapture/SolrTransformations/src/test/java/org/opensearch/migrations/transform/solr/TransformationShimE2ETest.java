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
import org.opensearch.migrations.transform.shim.SolrTransformerProvider;
import org.opensearch.testcontainers.OpensearchContainer;

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
 * Every test compares the proxy response with real Solr for full equality.
 * Assertion rules control expected differences: 'ignore' paths are skipped,
 * 'expect-diff' diffs pass but are logged, unmatched diffs fail.
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

        // Start a shared OpenSearch container once for all Solr versions
        var sharedOpenSearch = ShimTestFixture.createOpenSearchContainer(config.defaultOpenSearchImage());
        sharedOpenSearch.start();

        // Group by Solr version: one shared fixture per version to avoid per-test container overhead
        return config.defaultSolrVersions().stream().flatMap(solrVersion -> {
            var testsForVersion = testCases.stream()
                .filter(tc -> {
                    var versions = tc.solrVersions() != null && !tc.solrVersions().isEmpty()
                        ? tc.solrVersions() : config.defaultSolrVersions();
                    return versions.contains(solrVersion);
                })
                .toList();

            return Stream.of(DynamicTest.dynamicTest(
                "all-tests [" + solrVersion + "]",
                () -> {
                    try {
                        runAllTestsForVersion(testsForVersion, solrVersion, sharedOpenSearch);
                    } finally {
                        // Stop shared OpenSearch after the last version
                        if (solrVersion.equals(config.defaultSolrVersions().get(config.defaultSolrVersions().size() - 1))) {
                            sharedOpenSearch.stop();
                        }
                    }
                }
            ));
        });
    }

    /** Run all test cases for a single Solr version, grouping by transform bindings. */
    private void runAllTestsForVersion(
            List<TestCaseDefinition> testCases, String solrImage,
            OpensearchContainer<?> sharedOpenSearch) throws Exception {
        if (testCases.isEmpty()) return;

        var groups = new java.util.LinkedHashMap<String, List<TestCaseDefinition>>();
        for (var tc : testCases) {
            String key = tc.transformBindings() != null ? tc.transformBindings().toString() : "";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tc);
        }

        var allFailures = new ArrayList<String>();
        for (var group : groups.values()) {
            var firstTc = group.get(0);
            var bindings = firstTc.transformBindings() != null ? firstTc.transformBindings() : EMPTY_BINDINGS;
            var requestTransform = composeTransforms(firstTc.requestTransforms(), bindings);
            var responseTransform = composeTransforms(firstTc.responseTransforms());
            var plugins = firstTc.plugins() != null ? firstTc.plugins() : List.<String>of();

            try (var fixture = new ShimTestFixture(solrImage, sharedOpenSearch, requestTransform, responseTransform)) {
                fixture.start(plugins);
                for (var tc : group) {
                    try {
                        executeTestCase(fixture, tc, solrImage);
                    } catch (Throwable e) {
                        log.error("FAILED: {} [{}]: {}", tc.name(), solrImage, e.getMessage());
                        allFailures.add(tc.name() + ": " + e.getMessage());
                    } finally {
                        cleanupData(fixture, tc);
                    }
                }
            }
        }

        if (!allFailures.isEmpty()) {
            fail(allFailures.size() + " test(s) failed for " + solrImage + ":\n" + String.join("\n", allFailures));
        }
    }

    private void executeTestCase(
            ShimTestFixture fixture, TestCaseDefinition tc, String solrImage) throws Exception {
        seedData(fixture, tc);

        var proxyResponse = sendRequest(fixture, fixture.getProxyBaseUrl() + tc.requestPath(), tc);
        var proxyJson = MAPPER.readValue(proxyResponse, new TypeReference<Map<String, Object>>() {});

        compareWithSolr(fixture, tc, proxyJson);

        // Execute request sequence if defined (multi-step tests like cursor pagination)
        if (tc.requestSequence() != null && !tc.requestSequence().isEmpty()) {
            executeRequestSequence(fixture, tc, proxyJson);
        }

        log.info("PASSED: {} [{}]", tc.name(), solrImage);
    }

    /** Clean up Solr core and OpenSearch index after each test case for data isolation. */
    private void cleanupData(ShimTestFixture fixture, TestCaseDefinition tc) {
        try {
            fixture.httpGet(fixture.getSolrBaseUrl() + "/solr/admin/cores?action=UNLOAD&core="
                + tc.collection() + "&deleteIndex=true&deleteDataDir=true&deleteInstanceDir=true");
        } catch (Exception e) {
            log.debug("Cleanup Solr core '{}': {}", tc.collection(), e.getMessage());
        }
        try {
            fixture.httpDelete(fixture.getOpenSearchBaseUrl() + "/" + tc.collection());
        } catch (Exception e) {
            log.debug("Cleanup OpenSearch index '{}': {}", tc.collection(), e.getMessage());
        }
    }

    // --- Compare with real Solr ---

    private void compareWithSolr(
        ShimTestFixture fixture, TestCaseDefinition tc, Map<String, Object> proxyJson
    ) throws Exception {
        var solrResponse = sendRequest(fixture, fixture.getSolrBaseUrl() + tc.requestPath(), tc);
        var solrJson = MAPPER.readValue(solrResponse, new TypeReference<Map<String, Object>>() {});

        var rules = tc.assertionRules() != null ? tc.assertionRules() : List.<TestCaseDefinition.AssertionRule>of();
        compareResponses(tc.name(), solrJson, proxyJson, rules);
    }

    /**
     * Execute a sequence of follow-up requests, substituting {{nextCursorMark}} from each response.
     * Each step is compared against real Solr with the same substitution.
     */
    private void executeRequestSequence(
        ShimTestFixture fixture, TestCaseDefinition tc, Map<String, Object> previousProxyJson
    ) throws Exception {
        var previousSolrResponse = sendRequest(fixture, fixture.getSolrBaseUrl() + tc.requestPath(), tc);
        var previousSolrJson = MAPPER.readValue(previousSolrResponse, new TypeReference<Map<String, Object>>() {});

        for (int i = 0; i < tc.requestSequence().size(); i++) {
            var step = tc.requestSequence().get(i);
            var stepName = tc.name() + " [step " + (i + 2) + "]";

            // Substitute {{nextCursorMark}} from previous responses
            var proxyCursorMark = String.valueOf(previousProxyJson.get("nextCursorMark"));
            var solrCursorMark = String.valueOf(previousSolrJson.get("nextCursorMark"));

            var proxyPath = step.requestPath().replace("{{nextCursorMark}}", proxyCursorMark);
            var solrPath = step.requestPath().replace("{{nextCursorMark}}", solrCursorMark);

            log.info("{}: proxy cursorMark={}, solr cursorMark={}", stepName, proxyCursorMark, solrCursorMark);

            var proxyResponse = fixture.httpGet(fixture.getProxyBaseUrl() + proxyPath);
            var proxyJson = MAPPER.readValue(proxyResponse, new TypeReference<Map<String, Object>>() {});

            var solrResponse = fixture.httpGet(fixture.getSolrBaseUrl() + solrPath);
            var solrJson = MAPPER.readValue(solrResponse, new TypeReference<Map<String, Object>>() {});

            var rules = step.assertionRules() != null ? step.assertionRules()
                : (tc.assertionRules() != null ? tc.assertionRules() : List.<TestCaseDefinition.AssertionRule>of());
            compareResponses(stepName, solrJson, proxyJson, rules);

            previousProxyJson = proxyJson;
            previousSolrJson = solrJson;
        }
    }

    private void compareResponses(
        String testName, Map<String, Object> solrJson, Map<String, Object> proxyJson,
        List<TestCaseDefinition.AssertionRule> rules
    ) throws Exception {
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
                testName, expectedDiffs.size(), JsonDiff.formatReport(expectedDiffs));
        }

        if (!unexpectedDiffs.isEmpty()) {
            var report = JsonDiff.formatReport(unexpectedDiffs);
            log.error("Solr vs Proxy diff for '{}':\n{}", testName, report);
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
            applySolrConfigDefaults(fixture, tc);
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

    /** Apply solrconfig defaults to the running Solr instance via Config API. */
    @SuppressWarnings("unchecked")
    private void applySolrConfigDefaults(ShimTestFixture fixture, TestCaseDefinition tc) throws Exception {
        if (tc.transformBindings() == null) return;
        var solrConfig = (Map<String, Object>) tc.transformBindings().get("solrConfig");
        if (solrConfig == null) return;

        var selectConfig = (Map<String, Object>) solrConfig.get("/select");
        if (selectConfig == null) return;

        var handlerDef = new java.util.LinkedHashMap<String, Object>();
        handlerDef.put("name", "/select");
        handlerDef.put("class", "solr.SearchHandler");
        if (selectConfig.containsKey("defaults")) {
            handlerDef.put("defaults", selectConfig.get("defaults"));
        }
        if (selectConfig.containsKey("invariants")) {
            handlerDef.put("invariants", selectConfig.get("invariants"));
        }

        var configUrl = fixture.getSolrBaseUrl() + "/solr/" + tc.collection() + "/config";
        fixture.httpPost(configUrl, MAPPER.writeValueAsString(Map.of("update-requesthandler", handlerDef)));
        log.info("Applied solrconfig defaults to Solr collection '{}'", tc.collection());
    }

    private static IJsonTransformer composeTransforms(List<String> names) throws IOException {
        return composeTransforms(names, EMPTY_BINDINGS);
    }

    private static IJsonTransformer composeTransforms(List<String> names, Map<?, ?> bindings) throws IOException {
        if (names == null || names.isEmpty()) {
            return new JavascriptTransformer(IDENTITY_JS, EMPTY_BINDINGS);
        }
        var transformers = new IJsonTransformer[names.size()];
        for (int i = 0; i < names.size(); i++) {
            transformers[i] = new JavascriptTransformer(
                SolrTransformerProvider.JS_POLYFILL + loadTransformJs(names.get(i) + ".js"), bindings);
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
