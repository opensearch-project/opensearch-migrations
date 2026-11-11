package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonObjectSplitterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Nested objects, separators inside strings, escapes, multibyte text, every scalar kind, and loose whitespace.
    private static final String DOCUMENT = """
        { "title" : "a \\"quoted\\" },{ title", "caf\\u00e9": "naïve ☃",
          "count": 12345678901234567890123, "ratio": 0.1, "price": 1.50, "tiny": 1E-7, "none": null, "flag": true,
          "tags": ["x", {"k": 1, "nested": [1.25, {"deep": "}"}]}, []], "empty": {}, "blank": "",
          "profile": { "name": "n", "address": { "street": "s", "geo": {"lat": 1.5, "lon": -2.25}, "zip": "z" },
                       "notes": ["n1", "n2"], "bio": "0123456789012345678901234567890123456789" },
          "tail": "end" }""";

    /** What a partial update does on the target: objects merge member by member, anything else is replaced. */
    static void merge(ObjectNode into, ObjectNode from) {
        from.fields().forEachRemaining(field -> {
            if (field.getValue().isObject() && into.path(field.getKey()).isObject()) {
                merge((ObjectNode) into.get(field.getKey()), (ObjectNode) field.getValue());
            } else {
                into.set(field.getKey(), field.getValue());
            }
        });
    }

    private static List<byte[]> split(String json, int maxFragmentBytes) throws IOException {
        return JsonObjectSplitter.split(json.getBytes(StandardCharsets.UTF_8), maxFragmentBytes);
    }

    @Test
    void fragmentsOfEverySizeMergeBackIntoTheDocument() throws IOException {
        var original = MAPPER.readTree(DOCUMENT);
        int limits = 0;
        int splitWithinNestedObjects = 0;
        for (int max = DOCUMENT.getBytes(StandardCharsets.UTF_8).length; max > 0; max--) {
            List<byte[]> fragments;
            try {
                fragments = split(DOCUMENT, max);
            } catch (IOException tooSmallForTheLargestIndivisibleMember) {
                break;
            }
            limits++;
            var merged = MAPPER.createObjectNode();
            for (var fragment : fragments) {
                assertTrue(fragment.length <= max, "fragment of " + fragment.length + " bytes for limit " + max);
                merge(merged, (ObjectNode) MAPPER.readTree(fragment));
            }
            // Comparing text also checks that member order survives.
            assertEquals(original.toString(), merged.toString(), "limit " + max);
            if (fragments.stream().filter(f -> new String(f, StandardCharsets.UTF_8).startsWith("{\"profile\"")).count() > 1) {
                splitWithinNestedObjects++;
            }
        }
        assertTrue(limits > 400, "limits tried: " + limits);
        assertTrue(splitWithinNestedObjects > 100, "limits that divided a nested object: " + splitWithinNestedObjects);
    }

    @Test
    void membersKeepTheirOriginalBytes() throws IOException {
        var text = new StringBuilder();
        for (var fragment : split(DOCUMENT, 120)) {
            text.append(new String(fragment, StandardCharsets.UTF_8)).append('\n');
        }
        for (var literal : List.of("\"caf\\u00e9\": \"naïve ☃\"", "\"price\": 1.50", "\"tiny\": 1E-7",
            "\"count\": 12345678901234567890123", "\"geo\": {\"lat\": 1.5, \"lon\": -2.25}")) {
            assertTrue(text.toString().contains(literal), literal + " in\n" + text);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\":1,\"big\":\"0123456789012345678901234567890123456789\"}",       // string larger than a fragment
        "{\"a\":1,\"big\":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18]}",     // arrays are not divided
        "{\"a\":1,\"big\":{                                               }}",  // nothing to divide
        "{\"a\":1,\"b\":2,\"a\":3}",                                            // repeated name
        "{\"o\":{\"a\":1,\"a\":2}}",
        "{\"o\":[{\"a\":1,\"a\":2}]}",
        "{\"a\":0.1000000000000000055511151231257827}",                         // rounded by a 64-bit float
        "{\"a\":[{\"b\":123456789012345678.5}]}",
        "{\"a\":1e400}",
        "[1,2]",
        "{\"a\":1"
    })
    void refusesWhatCannotBeDividedFaithfully(String json) {
        assertThrows(IOException.class, () -> split(json, 40));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1", "1.10", "-0.0", "1E-7", "123.456e5", "1.7976931348623157E308"})
    void acceptsFractionsThatA64BitFloatReproduces(String literal) throws IOException {
        assertEquals(1, split("{\"a\":" + literal + "}", 40).size());
    }
}
