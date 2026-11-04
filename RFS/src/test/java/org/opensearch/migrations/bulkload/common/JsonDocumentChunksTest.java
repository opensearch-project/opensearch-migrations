package org.opensearch.migrations.bulkload.common;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

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
    void acceptsTheUtf8BomAllowedByRawSourceValidation() throws Exception {
        try (var chunks = new JsonDocumentChunks("\uFEFF{\"text\":\"value\"}".getBytes(StandardCharsets.UTF_8), 8192)) {
            var params = MAPPER.readTree(chunks.next()).path("script").path("params");
            assertTrue(params.path("events").toString().contains("[\"string\",\"value\"]"));
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
    }

    @Test
    void generatesOnlyRequestedPartsAndRestartsAtZeroForEachSubscription() {
        var input = new BulkNdjson.EncodedOperation(new byte[0],
            ("{\"value\":\"" + "x".repeat(100_000) + "\"}").getBytes(StandardCharsets.UTF_8), true);
        var produced = new AtomicInteger();
        OperationSplitter splitter = JsonDocumentChunks::split;
        var parts = splitter.split(input, 4096).doOnNext(part -> produced.incrementAndGet());
        StepVerifier.create(parts, 0)
            .then(() -> assertEquals(0, produced.get()))
            .thenRequest(1).assertNext(part -> {
                assertEquals(0, part.sequence());
                assertTrue(part.body().length <= 4096);
            })
            .then(() -> assertEquals(1, produced.get()))
            .thenRequest(1).assertNext(part -> assertEquals(1, part.sequence()))
            .then(() -> assertEquals(2, produced.get()))
            .thenCancel().verify();
        StepVerifier.create(parts, 1)
            .assertNext(part -> assertEquals(0, part.sequence()))
            .thenCancel().verify();
        assertEquals(3, produced.get(), "Cancellation must stop generation");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstPartDoesNotMaterializeAnEntireLargeStringOrFieldName(boolean fieldName) {
        // Allocate input outside the measurement, just as the encoded-operation boundary does.
        byte[] input = (fieldName ? "{\"" + "x".repeat(16 * 1024 * 1024) + "\":0}"
            : "{\"field\":\"" + "x".repeat(16 * 1024 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        try (var warmup = new JsonDocumentChunks("{\"warmup\":\"text\"}".getBytes(StandardCharsets.UTF_8), 8192)) {
            warmup.next();
        }
        var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(allocations.isThreadAllocatedMemorySupported());
        allocations.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long before = allocations.getThreadAllocatedBytes(thread);
        try (var chunks = new JsonDocumentChunks(input, 8192)) {
            byte[] part = chunks.next();
            long allocated = allocations.getThreadAllocatedBytes(thread) - before;
            assertTrue(part.length <= 8192);
            assertTrue(allocated < 1024 * 1024, () -> "A first 8 KiB part allocated " + allocated + " bytes");
            assertTrue(chunks.hasNext());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2048, 2051, 4096})
    void preservesExplicitEscapesAndEscapedSurrogatePairsAcrossParts(int limit) throws Exception {
        String escaped = "\\uD83D\\uDE00\\u00E9\\u96EA\\\"\\\\\\/\\b\\f\\n\\r\\t".repeat(1000);
        String source = "{\"" + escaped + "\":\"" + escaped + "\"}";
        var expected = MAPPER.readTree(source);
        var key = new StringBuilder();
        var value = new StringBuilder();
        try (var chunks = new JsonDocumentChunks(source.getBytes(StandardCharsets.UTF_8), limit)) {
            while (chunks.hasNext()) {
                var part = chunks.next();
                assertTrue(part.length <= limit);
                for (var event : MAPPER.readTree(part).path("script").path("params").path("events")) {
                    if (event.size() != 2 || !event.get(1).isTextual()) {
                        continue;
                    }
                    String fragment = event.get(1).asText();
                    if (!fragment.isEmpty()) {
                        assertFalse(Character.isLowSurrogate(fragment.charAt(0)));
                        assertFalse(Character.isHighSurrogate(fragment.charAt(fragment.length() - 1)));
                    }
                    (event.get(0).asText().startsWith("key") ? key : value).append(fragment);
                }
            }
        }
        assertEquals(expected.fieldNames().next(), key.toString());
        assertEquals(expected.elements().next().asText(), value.toString());
    }
}
