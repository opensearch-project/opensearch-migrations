package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBufAllocator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
@WrapWithNettyLeakDetection
public class JsonEmitterTest {

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

    @Test
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testEmitterWorksRoundTrip() throws IOException {
        try (JsonEmitter jse = new JsonEmitter(ByteBufAllocator.DEFAULT)) {
            var mapper = new ObjectMapper();

            var originalTree = mapper.readTree(new StringReader("{\"index\":{\"_id\":\"1\"}}"));
            var result = emitToString(jse, originalTree);
            var streamedToStringRoundTripped = mapper.writeValueAsString(
                mapper.readTree(new StringReader(result))
            );
            var originalRoundTripped = mapper.writeValueAsString(originalTree);
            Assertions.assertEquals(originalRoundTripped, streamedToStringRoundTripped);
        }
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testMapWithNullValueDoesNotThrow() throws IOException {
        try (JsonEmitter jse = new JsonEmitter(ByteBufAllocator.DEFAULT)) {
            var map = new LinkedHashMap<String, Object>();
            map.put("key", "value");
            map.put("nullKey", null);

            var result = emitToString(jse, map);
            Assertions.assertEquals("{\"key\":\"value\",\"nullKey\":null}", result);
        }
    }
}
