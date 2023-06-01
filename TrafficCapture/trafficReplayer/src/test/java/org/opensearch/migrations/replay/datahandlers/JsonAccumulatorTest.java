package org.opensearch.migrations.replay.datahandlers;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class JsonAccumulatorTest {

    public static final String TOY = "toy";
    public static final String MEDIUM = "medium";
    public static final String LARGE = "large";

    @Test
    public void testAccumulationTiny() throws IOException {
        byte[] jsonData = "{\"name\":\"John\", \"age\":30}".getBytes(StandardCharsets.UTF_8);
        var byteBuf = Unpooled.wrappedBuffer(jsonData);
        var jsonParser = new JsonAccumulator();
        var outputJson = jsonParser.consumeByteBuffer(byteBuf.nioBuffer());

        var mapper = new ObjectMapper();
        var jacksonParsedRoundTripped =
                mapper.writeValueAsString(mapper.readTree(jsonData));
        var jsonAccumParsedRoundTripped = mapper.writeValueAsString(outputJson);
        Assertions.assertEquals(jacksonParsedRoundTripped, jsonAccumParsedRoundTripped);
    }

    private static Object readJson(byte[] testFileBytes, int chunkBound) throws IOException {
        var jsonParser = new JsonAccumulator();
        Random r = new Random(2);
        var entireByteBuffer = ByteBuffer.wrap(testFileBytes);
        for (int i=0; i<testFileBytes.length; ) {
            var chunkByteBuffer = entireByteBuffer.duplicate();
            chunkByteBuffer.position(i);
            var chunkSize = Math.min(r.nextInt(chunkBound), chunkByteBuffer.remaining());
            chunkByteBuffer.limit(chunkSize+i);
            i += chunkSize;
            var completedObject = jsonParser.consumeByteBuffer(chunkByteBuffer);
            if (completedObject != null) {
                Assertions.assertEquals(testFileBytes.length, i);
                return completedObject;
            }
        }
        Assertions.fail("Could not produce a complete json parse");
        return null;
    }

    @Test
    public void testAccumulationLarge() throws IOException {
        byte[] testFileBytes;
        try (FileInputStream fis = new FileInputStream("/Users/schohn/es_bank_results_1.json");
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            testFileBytes = bis.readAllBytes();
        }
        var outputJson = readJson(testFileBytes, 2);

        var mapper = new ObjectMapper();
        var jacksonParsedRoundTripped =
                mapper.writeValueAsString(mapper.readTree(testFileBytes));
        var jsonAccumParsedRoundTripped = mapper.writeValueAsString(outputJson);
        Assertions.assertEquals(jacksonParsedRoundTripped, jsonAccumParsedRoundTripped);
    }


    byte[] getData(String key) throws IOException {
        switch (key) {
            case TOY:
                return "{\"name\":\"John\", \"age\":30}".getBytes(StandardCharsets.UTF_8);
            case MEDIUM:
                try (FileInputStream fis = new FileInputStream("FILLMEIN");
                     BufferedInputStream bis = new BufferedInputStream(fis)) {
                    return bis.readAllBytes();
                }
            case LARGE:
                try (FileInputStream fis = new FileInputStream("FILLMEIN");
                     BufferedInputStream bis = new BufferedInputStream(fis)) {
                    return bis.readAllBytes();
                }
            default:
                throw new RuntimeException("Unknown key: "+key);
        }
    }
    @ParameterizedTest
    @CsvSource({"toy,2", "toy,20000",
            //"medium,2","medium,20000",
            //"large,2","large,20000"})
    })
    public void testAccumulation(String dataName, int chunkBound) throws IOException {
        var testFileBytes = getData(dataName);
        var outputJson = readJson(testFileBytes, 2);

        var mapper = new ObjectMapper();
        var jacksonParsedRoundTripped =
                mapper.writeValueAsString(mapper.readTree(testFileBytes));
        var jsonAccumParsedRoundTripped = mapper.writeValueAsString(outputJson);
        Assertions.assertEquals(jacksonParsedRoundTripped, jsonAccumParsedRoundTripped);

    }
}
