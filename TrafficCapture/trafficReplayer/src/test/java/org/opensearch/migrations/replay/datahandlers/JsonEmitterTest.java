package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBufAllocator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
@WrapWithNettyLeakDetection
public class JsonEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Exercises every JSON value type: nested objects, arrays, empty array, empty object,
    // null, string, number (int + float), boolean.
    private static final String ALL_TYPES_JSON = "{"
        + "\"string\":\"hello\","
        + "\"number\":42,"
        + "\"float\":3.14,"
        + "\"boolTrue\":true,"
        + "\"boolFalse\":false,"
        + "\"nullVal\":null,"
        + "\"nested\":{\"inner\":\"value\",\"deep\":{\"a\":1}},"
        + "\"array\":[1,\"two\",null,true],"
        + "\"emptyArray\":[],"
        + "\"emptyObject\":{}"
        + "}";

    private static String emitToString(JsonEmitter jse, Object input) throws IOException {
        var writer = new StringWriter();
        var pac = jse.getChunkAndContinuations(input, 10 * 1024);
        while (true) {
            var chunk = pac.partialSerializedContents.toString(StandardCharsets.UTF_8);
            pac.partialSerializedContents.release();
            log.info("Got: " + chunk);
            writer.append(chunk);
            if (pac.nextSupplier == null) {
                break;
            }
            pac = pac.nextSupplier.get();
        }
        writer.flush();
        return writer.toString();
    }

    /** ObjectNode / ArrayNode path — parsed JSON tree covers ObjectNode, ArrayNode, and all value types. */
    @Test
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testObjectNodeAllTypes() throws IOException {
        try (var jse = new JsonEmitter(ByteBufAllocator.DEFAULT)) {
            var tree = MAPPER.readTree(new StringReader(ALL_TYPES_JSON));
            var result = emitToString(jse, tree);
            Assertions.assertEquals(MAPPER.writeValueAsString(tree), result);
        }
    }

    /** Map / Map.Entry path — covers Map and Map.Entry branches with nested maps and null. */
    @Test
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testMapWithNestedMapsAndNull() throws IOException {
        try (var jse = new JsonEmitter(ByteBufAllocator.DEFAULT)) {
            var inner = new LinkedHashMap<String, Object>();
            inner.put("a", 1);

            var map = new LinkedHashMap<String, Object>();
            map.put("key", "value");
            map.put("nullVal", null);
            map.put("nested", inner);
            map.put("empty", new LinkedHashMap<>());

            var result = emitToString(jse, map);
            Assertions.assertEquals(
                "{\"key\":\"value\",\"nullVal\":null,\"nested\":{\"a\":1},\"empty\":{}}",
                result
            );
        }
    }

    /** Native Java array path — covers the o.getClass().isArray() branch. */
    @Test
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testNativeArrayBranch() throws IOException {
        try (var jse = new JsonEmitter(ByteBufAllocator.DEFAULT)) {
            var map = new LinkedHashMap<String, Object>();
            map.put("items", new Object[] { "a", 1, null, true });
            map.put("empty", new Object[] {});

            var result = emitToString(jse, map);
            Assertions.assertEquals("{\"items\":[\"a\",1,null,true],\"empty\":[]}", result);
        }
    }

    /** Top-level ArrayNode with mixed element types. */
    @Test
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testTopLevelArrayNode() throws IOException {
        try (var jse = new JsonEmitter(ByteBufAllocator.DEFAULT)) {
            var arrayNode = MAPPER.createArrayNode();
            arrayNode.add("x");
            arrayNode.add(99);
            arrayNode.addNull();
            arrayNode.add(MAPPER.createObjectNode().put("k", "v"));

            var result = emitToString(jse, arrayNode);
            Assertions.assertEquals("[\"x\",99,null,{\"k\":\"v\"}]", result);
        }
    }

    /** Programmatically built ObjectNode — verifies ObjectNode properties() iteration. */
    @Test
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testProgrammaticObjectNode() throws IOException {
        try (var jse = new JsonEmitter(ByteBufAllocator.DEFAULT)) {
            var node = MAPPER.createObjectNode();
            node.put("str", "val");
            node.putNull("n");
            node.putArray("arr").add(1).add(2);
            node.putObject("obj").put("nested", true);

            var result = emitToString(jse, node);
            Assertions.assertEquals(
                "{\"str\":\"val\",\"n\":null,\"arr\":[1,2],\"obj\":{\"nested\":true}}",
                result
            );
        }
    }
}
