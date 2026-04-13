/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.solr;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A declarative test case definition, deserialized from TypeScript-generated JSON.
 * <p>
 * Defines transforms to apply, data to seed, request to send, and assertion rules.
 * Every test always compares with real Solr — assertion rules control how diffs are handled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record TestCaseDefinition(
    String name,
    String description,
    List<String> requestTransforms,
    List<String> responseTransforms,
    String collection,
    List<Map<String, Object>> documents,
    Boolean seedSolr,
    Boolean seedOpenSearch,
    String method,
    String requestBody,
    String requestPath,
    List<RequestStep> requestSequence,
    List<AssertionRule> assertionRules,
    Map<String, Object> solrSchema,
    Map<String, Object> opensearchMapping,
    List<String> solrVersions,
    List<String> plugins,
    Map<String, Object> transformBindings
) {
    /** A per-path assertion rule controlling how diffs are handled. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AssertionRule(String path, String rule, String expected, Integer skip, String reason) {}

    /** A step in a multi-request test sequence (e.g. cursor pagination page 2, 3, ...). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RequestStep(String requestPath, List<AssertionRule> assertionRules) {}
}
