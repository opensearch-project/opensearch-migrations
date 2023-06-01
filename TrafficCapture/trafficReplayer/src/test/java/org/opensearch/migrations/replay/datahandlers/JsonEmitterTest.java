package org.opensearch.migrations.replay.datahandlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class JsonEmitterTest {
    @Test
    public void testEmitterWorksRoundTrip() throws IOException {
        JsonEmitter jse = new JsonEmitter();
        var mapper = new ObjectMapper();

        //var originalTree = mapper.readTree(new File("bigfile.json"));
        var originalTree = mapper.readTree(new StringReader("{\"index\":{\"_id\":\"1\"}}"));
        var writer = new StringWriter();
        var pac = jse.getChunkAndContinuations(originalTree, 10*1024);
        while (true) {
            var asBytes = new byte[pac.partialSerializedContents.readableBytes()];
            log.info("Got "+asBytes.length+" bytes back");
            pac.partialSerializedContents.readBytes(asBytes);
            var nextChunkStr = new String(asBytes, StandardCharsets.UTF_8);
            writer.append(nextChunkStr);
            if (pac.nextSupplier == null) {
                break;
            }
            pac = pac.nextSupplier.get();
        }
        writer.flush();
        var streamedToStringRoundTripped =
                mapper.writeValueAsString(mapper.readTree(new StringReader(writer.toString())));
        var originalRoundTripped = mapper.writeValueAsString(originalTree);
        Assertions.assertEquals(originalRoundTripped, streamedToStringRoundTripped);
    }
}