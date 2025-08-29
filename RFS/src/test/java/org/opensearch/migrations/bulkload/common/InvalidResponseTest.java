package org.opensearch.migrations.bulkload.common;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.http.HttpResponse;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;

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
}
