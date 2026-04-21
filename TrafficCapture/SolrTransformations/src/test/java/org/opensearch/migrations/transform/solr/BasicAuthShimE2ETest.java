/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.solr;

import java.net.http.HttpResponse;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that validate the ShimProxy's {@code BasicAuthSigningHandler} pipeline
 * against a real auth-enabled Solr 8 backend.
 * <p>
 * This test class runs alongside {@code TransformationShimE2ETest} without modifying it.
 * It uses {@link AuthShimTestFixture} to manage an auth-enabled Solr container and a
 * ShimProxy configured with a per-target {@code BasicAuthSigningHandler}.
 * <p>
 * Validates:
 * <ul>
 *   <li>Authenticated requests through the proxy succeed (positive path)</li>
 *   <li>Direct unauthenticated requests to Solr are rejected with HTTP 401 (negative path)</li>
 * </ul>
 */
@Slf4j
@Tag("isolatedTest")
class BasicAuthShimE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AuthShimTestFixture fixture;

    @BeforeAll
    static void setUp() throws Exception {
        fixture = new AuthShimTestFixture();
        fixture.start();

        // Pre-create a Solr core for the query tests
        fixture.createSolrCore("authtest");

        // Index test documents directly into Solr using authenticated access
        var jsonDocs = "[{\"id\":\"1\",\"title\":\"Auth Test Doc 1\"},{\"id\":\"2\",\"title\":\"Auth Test Doc 2\"}]";
        fixture.authenticatedSolrPost(
            fixture.getSolrBaseUrl() + "/solr/authtest/update/json/docs?commit=true",
            jsonDocs
        );
        log.info("Test setup complete: Solr core 'authtest' created and documents indexed");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    /**
     * A query through the proxy to auth-enabled Solr succeeds and returns indexed documents.
     * The client sends no Authorization header — the proxy injects it via BasicAuthSigningHandler.
     */
    @Test
    void authenticatedSolrQueryThroughProxy_succeeds() throws Exception {
        var proxyUrl = fixture.getProxyBaseUrl() + "/solr/authtest/select?q=*:*&wt=json";
        HttpResponse<String> response = fixture.httpGetRaw(proxyUrl);

        log.info("Proxy response: status={}, body={}", response.statusCode(), response.body());

        assertEquals(200, response.statusCode(),
            "Proxy should return HTTP 200 for authenticated Solr query, got body: " + response.body());

        var body = MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        assertNotNull(body.get("response"), "Response should contain a 'response' field");

        @SuppressWarnings("unchecked")
        var responseSection = (Map<String, Object>) body.get("response");
        var numFound = ((Number) responseSection.get("numFound")).intValue();
        assertTrue(numFound > 0,
            "Expected at least 1 document in Solr query results, got numFound=" + numFound);

        log.info("authenticatedSolrQueryThroughProxy_succeeds: numFound={}", numFound);
    }

    /**
     * A direct request to the Solr container without auth credentials is rejected with HTTP 401.
     */
    @Test
    void unauthenticatedDirectSolrRequest_returns401() throws Exception {
        HttpResponse<String> response = fixture.httpGetRaw(
            fixture.getSolrBaseUrl() + "/solr/admin/info/system"
        );

        assertEquals(401, response.statusCode(),
            "Direct unauthenticated request to auth-enabled Solr should return HTTP 401");

        log.info("unauthenticatedDirectSolrRequest_returns401: status={}", response.statusCode());
    }
}
