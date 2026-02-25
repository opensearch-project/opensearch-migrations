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
 * Defines transforms to apply, data to seed, request to send, and expected results.
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
    List<Map<String, Object>> expectedDocs,
    List<String> expectedFields,
    String assertResponseFormat,
    Boolean compareWithSolr,
    List<String> ignorePaths,
    Map<String, Object> opensearchMapping,
    List<String> solrVersions,
    List<String> plugins
) {}
