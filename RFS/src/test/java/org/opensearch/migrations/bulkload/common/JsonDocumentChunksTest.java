package org.opensearch.migrations.bulkload.common;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDocumentChunksTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    @ParameterizedTest
    @ValueSource(ints = {2048, 4096, 8192})
    void measuresTheWholeEnvelopeAndPreservesEscapedUnicodeAndLargeKeys(int limit) throws Exception {
        String key = "key.é雪😀\"\\\n".repeat(1200);
        String value = "é雪😀\"\\\n\r\t\u0000".repeat(4000);
        var input = MAPPER.writeValueAsBytes(Map.of(key, value));
        var keys = new StringBuilder();
        var values = new StringBuilder();
        int requests = 0;
        try (var chunks = new JsonDocumentChunks(input, limit)) {
            while (chunks.hasNext()) {
                var bytes = chunks.next();
                assertTrue(bytes.length <= limit, () -> bytes.length + " > " + limit);
                var params = MAPPER.readTree(bytes).path("script").path("params");
                assertEquals(requests++, params.path("seq").asInt());
                assertFalse(params.path("events").isEmpty());
                assertTrue(params.path("events").size() <= 512);
                for (var event : params.path("events")) {
                    String kind = event.get(0).asText();
                    if (kind.startsWith("key")) {
                        keys.append(event.get(1).asText());
                    } else if (kind.startsWith("string")) {
                        String fragment = event.get(1).asText();
                        if (!fragment.isEmpty()) {
                            assertFalse(Character.isLowSurrogate(fragment.charAt(0)));
                            assertFalse(Character.isHighSurrogate(fragment.charAt(fragment.length() - 1)));
                        }
                        values.append(fragment);
                    }
                }
            }
            assertEquals(requests, chunks.chunksProduced());
        }
        assertTrue(requests > 1);
        assertEquals(key, keys.toString());
        assertEquals(value, values.toString());
    }

    @Test
    void fillsAnAsciiRequestExactlyAndHandlesSequenceNumberGrowth() throws Exception {
        try (var chunks = new JsonDocumentChunks(MAPPER.writeValueAsBytes(Map.of("text", "x".repeat(80_000))), 2048)) {
            List<Integer> sizes = new ArrayList<>();
            while (chunks.hasNext()) {
                var bytes = chunks.next();
                MAPPER.readTree(bytes);
                sizes.add(bytes.length);
            }
            assertTrue(sizes.size() > 100);
            assertTrue(sizes.stream().allMatch(size -> size <= 2048));
            assertTrue(sizes.stream().filter(size -> size == 2048).count() > 100);
        }
    }

    @Test
    void handlesEmptyContainersEmptyStringsAndNulls() throws Exception {
        var document = "{\"\": \"\", \"array\": [null, true, false, {}, [], -9223372036854775808, 1e-3]}";
        try (var chunks = new JsonDocumentChunks(document.getBytes(StandardCharsets.UTF_8), 8192)) {
            var request = MAPPER.readTree(chunks.next());
            assertTrue(request.path("script").path("params").path("events").toString().contains("[\"value\",null]"));
            assertFalse(chunks.hasNext());
        }
    }

    @Test
    void rejectsLimitsSmallerThanTheProtocolAndMultipleRootValues() {
        assertThrows(IllegalArgumentException.class,
            () -> new JsonDocumentChunks("{}".getBytes(StandardCharsets.UTF_8), 100));
        assertThrows(IllegalArgumentException.class, () -> {
            try (var chunks = new JsonDocumentChunks("{} {}".getBytes(StandardCharsets.UTF_8), 8192)) {
                while (chunks.hasNext()) {
                    chunks.next();
                }
            }
        });
        assertThrows(UncheckedIOException.class, () -> {
            try (var chunks = new JsonDocumentChunks("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8), 8192)) {
                while (chunks.hasNext()) {
                    chunks.next();
                }
            }
        });
    }
}
