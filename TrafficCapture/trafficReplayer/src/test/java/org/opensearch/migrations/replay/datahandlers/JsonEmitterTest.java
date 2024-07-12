package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.ByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@WrapWithNettyLeakDetection
public class JsonEmitterTest {
    @Test
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testEmitterWorksRoundTrip() throws IOException {
        try (JsonEmitter jse = new JsonEmitter(ByteBufAllocator.DEFAULT)) {
            var mapper = new ObjectMapper();

            var originalTree = mapper.readTree(new StringReader("{\"index\":{\"_id\":\"1\"}}"));
            var writer = new StringWriter();
            var pac = jse.getChunkAndContinuations(originalTree, 10 * 1024);
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
            var streamedToStringRoundTripped = mapper.writeValueAsString(
                mapper.readTree(new StringReader(writer.toString()))
            );
            var originalRoundTripped = mapper.writeValueAsString(originalTree);
            Assertions.assertEquals(originalRoundTripped, streamedToStringRoundTripped);
        }
    }
}
