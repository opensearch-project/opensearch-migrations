package org.opensearch.migrations.bulkload.common;

import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.bulkload.common.http.HttpResponse;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidResponseTest {

    @Test
    void testGetIllegalArguments_noIllegalArguments() {
        var errorBody = "{\r\n" + //
            "  \"error\": \"Incorrect HTTP method for uri [/a/bc] and method [PUT], allowed: [POST]\",\r\n" + //
            "  \"status\": 405\r\n" + //
            "}";
        var response = new HttpResponse(200, "statusText", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, empty());
    }

    @Test
    void testGetIllegalArguments() {
        var errorBody = "{\r\n" + //
            "  \"error\": {\r\n" + //
            "    \"type\": \"illegal_argument_exception\",\r\n" + //
            "    \"reason\": \"unknown setting [index.creation_date] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "  },\r\n"
            + //
            "  \"status\": 400\r\n"
            + //
            "}";
        var response = new HttpResponse(200, "statusText", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, containsInAnyOrder("index.creation_date"));
    }

    @Test
    void testGetIllegalArguments_inRootCause() {
        var errorBody = "{\r\n" + //
            "  \"error\": {\r\n" + //
            "    \"root_cause\": [\r\n" + //
            "      {\r\n" + //
            "        \"type\": \"illegal_argument_exception\",\r\n" + //
            "        \"reason\": \"unknown setting [index.creation_date] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "      }\r\n"
            + //
            "    ],\r\n"
            + //
            "    \"type\": \"illegal_argument_exception\",\r\n"
            + //
            "    \"reason\": \"unknown setting [index.creation_date] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "  },\r\n"
            + //
            "  \"status\": 400\r\n"
            + //
            "}";
        var response = new HttpResponse(200, "statusText", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, containsInAnyOrder("index.creation_date"));
    }

    @Test
    void testGetIllegalArguments_inRootCauseAndSuppressed() {
        var errorBody = "{\r\n" + //
            "  \"error\": {\r\n" + //
            "    \"root_cause\": [\r\n" + //
            "      {\r\n" + //
            "        \"type\": \"illegal_argument_exception\",\r\n" + //
            "        \"reason\": \"unknown setting [index.creation_date] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "      }\r\n"
            + //
            "    ],\r\n"
            + //
            "    \"type\": \"illegal_argument_exception\",\r\n"
            + //
            "    \"reason\": \"unknown setting [index.creation_date] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\",\r\n"
            + //
            "    \"suppressed\": [\r\n"
            + //
            "      {\r\n"
            + //
            "        \"type\": \"illegal_argument_exception\",\r\n"
            + //
            "        \"reason\": \"unknown setting [index.lifecycle.name] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "      },\r\n"
            + //
            "      {\r\n"
            + //
            "        \"type\": \"illegal_argument_exception\",\r\n"
            + //
            "        \"reason\": \"unknown setting [index.provided_name] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "      }\r\n"
            + //
            "    ]\r\n"
            + //
            "  },\r\n"
            + //
            "  \"status\": 400\r\n"
            + //
            "}";
        var response = new HttpResponse(200, "statusText", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, containsInAnyOrder("index.provided_name", "index.lifecycle.name", "index.creation_date"));
    }

    @Test
    void testGetIllegalArguments_WithSettingsException() {
        var errorBody = "{\r\n" + //
            "  \"error\": {\r\n" + //
            "    \"root_cause\": [\r\n" + //
            "      {\r\n" + //
            "        \"type\": \"settings_exception\",\r\n" + //
            "        \"reason\": \"unknown setting [index.bloom_filter_for_id_field.enabled] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "      }\r\n"
            + //
            "    ],\r\n"
            + //
            "    \"type\": \"settings_exception\",\r\n"
            + //
            "    \"reason\": \"unknown setting [index.bloom_filter_for_id_field.enabled] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "  },\r\n"
            + //
            "  \"status\": 400\r\n"
            + //
            "}";
        var response = new HttpResponse(200, "statusText", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, containsInAnyOrder("index.bloom_filter_for_id_field.enabled"));
    }

    @Test
    void testGetIllegalArguments_WithMixedExceptionTypes() {
        var errorBody = "{\r\n" + //
            "  \"error\": {\r\n" + //
            "    \"root_cause\": [\r\n" + //
            "      {\r\n" + //
            "        \"type\": \"illegal_argument_exception\",\r\n" + //
            "        \"reason\": \"unknown setting [index.creation_date] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "      }\r\n"
            + //
            "    ],\r\n"
            + //
            "    \"type\": \"settings_exception\",\r\n"
            + //
            "    \"reason\": \"unknown setting [index.bloom_filter_for_id_field.enabled] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\",\r\n"
            + //
            "    \"suppressed\": [\r\n"
            + //
            "      {\r\n"
            + //
            "        \"type\": \"illegal_argument_exception\",\r\n"
            + //
            "        \"reason\": \"unknown setting [index.provided_name] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n"
            + //
            "      }\r\n"
            + //
            "    ]\r\n"
            + //
            "  },\r\n"
            + //
            "  \"status\": 400\r\n"
            + //
            "}";
        var response = new HttpResponse(200, "statusText", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, containsInAnyOrder("index.creation_date", "index.bloom_filter_for_id_field.enabled", "index.provided_name"));
    }

    @Test
    void testGetIllegalArguments_versionMaskError() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"illegal_argument_exception\",\r\n" +
            "        \"reason\": \"Version id 8521000 must contain OpenSearch mask\"\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"Version id 8521000 must contain OpenSearch mask\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, containsInAnyOrder("index.version.created"));
    }

    @Test
    void testGetUnsupportedMappingParameters_singleParam() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"mapper_parsing_exception\",\r\n" +
            "        \"reason\": \"unsupported parameters:  [_all : {enabled=false}]\"\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"type\": \"mapper_parsing_exception\",\r\n" +
            "    \"reason\": \"Failed to parse mapping: unsupported parameters:  [_all : {enabled=false}]\",\r\n" +
            "    \"caused_by\": {\r\n" +
            "      \"type\": \"mapper_parsing_exception\",\r\n" +
            "      \"reason\": \"unsupported parameters:  [_all : {enabled=false}]\"\r\n" +
            "    }\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getUnsupportedMappingParameters();

        assertThat(result, containsInAnyOrder("_all"));
    }

    @Test
    void testGetUnsupportedMappingParameters_multipleParams() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"mapper_parsing_exception\",\r\n" +
            "        \"reason\": \"unsupported parameters:  [_all : {enabled=false}] [_parent : {type=parent_type}]\"\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"type\": \"mapper_parsing_exception\",\r\n" +
            "    \"reason\": \"Failed to parse mapping: unsupported parameters:  [_all : {enabled=false}] [_parent : {type=parent_type}]\",\r\n" +
            "    \"caused_by\": {\r\n" +
            "      \"type\": \"mapper_parsing_exception\",\r\n" +
            "      \"reason\": \"unsupported parameters:  [_all : {enabled=false}] [_parent : {type=parent_type}]\"\r\n" +
            "    }\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getUnsupportedMappingParameters();

        assertThat(result, containsInAnyOrder("_all", "_parent"));
    }

    @Test
    void testGetRemovedTokenFilters_topLevel() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"illegal_argument_exception\",\r\n" +
            "        \"reason\": \"The [standard] token filter has been removed.\"\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"The [standard] token filter has been removed.\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, containsInAnyOrder("standard"));
    }

    @Test
    void testGetRemovedTokenFilters_noMatch() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"unknown setting [index.creation_date]\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, empty());
    }

    @Test
    void testGetUnsupportedMappingParameters_notMappingError() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"unknown setting [index.creation_date]\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getUnsupportedMappingParameters();

        assertThat(result, empty());
    }

    // --- getRemovedTokenFilters: suppressed array path ---
    @Test
    void testGetRemovedTokenFilters_inSuppressed() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"The [standard] token filter has been removed.\",\r\n" +
            "    \"suppressed\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"illegal_argument_exception\",\r\n" +
            "        \"reason\": \"The [classic] token filter has been removed.\"\r\n" +
            "      }\r\n" +
            "    ]\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, containsInAnyOrder("standard", "classic"));
    }

    // --- getRemovedTokenFilters: caused_by path ---
    @Test
    void testGetRemovedTokenFilters_inCausedBy() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"The [standard] token filter has been removed.\",\r\n" +
            "    \"caused_by\": {\r\n" +
            "      \"type\": \"illegal_argument_exception\",\r\n" +
            "      \"reason\": \"The [legacy_filter] token filter has been removed\"\r\n" +
            "    }\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, containsInAnyOrder("standard", "legacy_filter"));
    }

    // --- getRemovedTokenFilters: invalid JSON triggers catch block ---
    @Test
    void testGetRemovedTokenFilters_invalidJson() {
        var response = new HttpResponse(400, "Bad Request", Map.of(), "not valid json{{{");
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, empty());
    }

    // --- getRemovedTokenFilters: wrong exception type is ignored ---
    @Test
    void testGetRemovedTokenFilters_wrongType() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"mapper_parsing_exception\",\r\n" +
            "    \"reason\": \"The [standard] token filter has been removed.\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, empty());
    }

    // --- getRemovedTokenFilters: missing type or reason fields ---
    @Test
    void testGetRemovedTokenFilters_missingFields() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"reason\": \"The [standard] token filter has been removed.\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, empty());
    }

    // --- getRemovedTokenFilters: all paths together (root_cause + suppressed + caused_by + top-level) ---
    @Test
    void testGetRemovedTokenFilters_allPaths() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"The [standard] token filter has been removed.\",\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"illegal_argument_exception\",\r\n" +
            "        \"reason\": \"The [old_filter] token filter has been removed\"\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"caused_by\": {\r\n" +
            "      \"type\": \"illegal_argument_exception\",\r\n" +
            "      \"reason\": \"The [another_filter] token filter has been removed\"\r\n" +
            "    },\r\n" +
            "    \"suppressed\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"illegal_argument_exception\",\r\n" +
            "        \"reason\": \"The [suppressed_filter] token filter has been removed\"\r\n" +
            "      }\r\n" +
            "    ]\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, containsInAnyOrder("standard", "old_filter", "another_filter", "suppressed_filter"));
    }

    // --- containsAwarenessAttributeException tests ---
    @Test
    void testContainsAwarenessAttributeException_present() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"validation_exception\",\r\n" +
            "        \"reason\": \"Validation Failed: 1: expected total copies needs to be a multiple of total awareness attributes [3];\"\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"type\": \"validation_exception\",\r\n" +
            "    \"reason\": \"Validation Failed: 1: expected total copies needs to be a multiple of total awareness attributes [3];\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        Optional<String> result = iar.containsAwarenessAttributeException();

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("expected total copies needs to be a multiple of total awareness attributes"));
    }

    @Test
    void testContainsAwarenessAttributeException_notPresent() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"illegal_argument_exception\",\r\n" +
            "        \"reason\": \"unknown setting [index.creation_date]\"\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"unknown setting [index.creation_date]\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        Optional<String> result = iar.containsAwarenessAttributeException();

        assertTrue(result.isEmpty());
    }

    @Test
    void testContainsAwarenessAttributeException_noRootCauses() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"validation_exception\",\r\n" +
            "    \"reason\": \"expected total copies needs to be a multiple of total awareness attributes [3]\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        Optional<String> result = iar.containsAwarenessAttributeException();

        assertTrue(result.isEmpty());
    }

    @Test
    void testContainsAwarenessAttributeException_nullReasonIgnored() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"validation_exception\",\r\n" +
            "        \"reason\": null\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"type\": \"validation_exception\",\r\n" +
            "    \"reason\": \"some reason\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        Optional<String> result = iar.containsAwarenessAttributeException();

        assertTrue(result.isEmpty());
    }

    @Test
    void testContainsAwarenessAttributeException_invalidJson() {
        var response = new HttpResponse(400, "Bad Request", Map.of(), "not valid json{{{");
        var iar = new InvalidResponse("ignored", response);

        Optional<String> result = iar.containsAwarenessAttributeException();

        assertTrue(result.isEmpty());
    }

    @Test
    void testContainsAwarenessAttributeException_noErrorField() {
        var errorBody = "{\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        Optional<String> result = iar.containsAwarenessAttributeException();

        assertTrue(result.isEmpty());
    }

    @Test
    void testContainsAwarenessAttributeException_multipleRootCauses() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"root_cause\": [\r\n" +
            "      {\r\n" +
            "        \"type\": \"illegal_argument_exception\",\r\n" +
            "        \"reason\": \"some other error\"\r\n" +
            "      },\r\n" +
            "      {\r\n" +
            "        \"type\": \"validation_exception\",\r\n" +
            "        \"reason\": \"expected total copies needs to be a multiple of total awareness attributes [2]\"\r\n" +
            "      }\r\n" +
            "    ],\r\n" +
            "    \"type\": \"validation_exception\",\r\n" +
            "    \"reason\": \"Validation Failed\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        Optional<String> result = iar.containsAwarenessAttributeException();

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("expected total copies needs to be a multiple of total awareness attributes"));
    }

    // --- getIllegalArguments: unexpected error type returns empty ---
    @Test
    void testGetIllegalArguments_unexpectedErrorType() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"resource_already_exists_exception\",\r\n" +
            "    \"reason\": \"unknown setting [index.creation_date] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, empty());
    }

    // --- getIllegalArguments: private setting pattern ---
    @Test
    void testGetIllegalArguments_privateSetting() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"private index setting [index.version.created] can not be set explicitly\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, containsInAnyOrder("index.version.created"));
    }

    // --- getIllegalArguments: invalid JSON triggers catch block ---
    @Test
    void testGetIllegalArguments_invalidJson() {
        var response = new HttpResponse(400, "Bad Request", Map.of(), "not valid json{{{");
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, empty());
    }

    // --- getIllegalArguments: null body ---
    @Test
    void testGetIllegalArguments_nullBody() {
        var response = new HttpResponse(400, "Bad Request", Map.of(), null);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, empty());
    }

    // --- getIllegalArguments: reason does not match any pattern ---
    @Test
    void testGetIllegalArguments_noPatternMatch() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"illegal_argument_exception\",\r\n" +
            "    \"reason\": \"some completely different error message\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, empty());
    }

    // --- getIllegalArguments: validation_exception type is accepted ---
    @Test
    void testGetIllegalArguments_validationException() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"validation_exception\",\r\n" +
            "    \"reason\": \"unknown setting [index.some.setting] please check that any required plugins are installed, or check the breaking changes documentation for removed settings\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, containsInAnyOrder("index.some.setting"));
    }

    // --- getIllegalArguments: missing reason field ---
    @Test
    void testGetIllegalArguments_missingReasonField() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"illegal_argument_exception\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getIllegalArguments();

        assertThat(result, empty());
    }

    // --- getUnsupportedMappingParameters: invalid JSON ---
    @Test
    void testGetUnsupportedMappingParameters_invalidJson() {
        var response = new HttpResponse(400, "Bad Request", Map.of(), "not valid json{{{");
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getUnsupportedMappingParameters();

        assertThat(result, empty());
    }

    // --- getUnsupportedMappingParameters: mapper_parsing_exception but reason doesn't match ---
    @Test
    void testGetUnsupportedMappingParameters_noUnsupportedParamInReason() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"mapper_parsing_exception\",\r\n" +
            "    \"reason\": \"Failed to parse mapping: some other issue\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getUnsupportedMappingParameters();

        assertThat(result, empty());
    }

    // --- getUnsupportedMappingParameters: missing reason field ---
    @Test
    void testGetUnsupportedMappingParameters_missingReason() {
        var errorBody = "{\r\n" +
            "  \"error\": {\r\n" +
            "    \"type\": \"mapper_parsing_exception\"\r\n" +
            "  },\r\n" +
            "  \"status\": 400\r\n" +
            "}";
        var response = new HttpResponse(400, "Bad Request", Map.of(), errorBody);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getUnsupportedMappingParameters();

        assertThat(result, empty());
    }

    // --- getRemovedTokenFilters: null body ---
    @Test
    void testGetRemovedTokenFilters_nullBody() {
        var response = new HttpResponse(400, "Bad Request", Map.of(), null);
        var iar = new InvalidResponse("ignored", response);

        var result = iar.getRemovedTokenFilters();

        assertThat(result, empty());
    }

    // --- containsAwarenessAttributeException: null body ---
    @Test
    void testContainsAwarenessAttributeException_nullBody() {
        var response = new HttpResponse(400, "Bad Request", Map.of(), null);
        var iar = new InvalidResponse("ignored", response);

        Optional<String> result = iar.containsAwarenessAttributeException();

        assertTrue(result.isEmpty());
    }
}
