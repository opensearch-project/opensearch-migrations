package org.opensearch.migrations.replay.datahandlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opensearch.migrations.replay.GenerateRandomNestedJsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class JsonAccumulatorTest {
    public static final String TINY = "tiny";
    public static final String MEDIUM = "medium";
    public static final String LARGE = "large";
    GenerateRandomNestedJsonObject randomJsonGenerator = new GenerateRandomNestedJsonObject();

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

    byte[] getData(String key) throws IOException {
        switch (key) {
            case TINY:
                return "{\"name\":\"John\", \"age\":30}".getBytes(StandardCharsets.UTF_8);
            case MEDIUM:
                return randomJsonGenerator.getRandomTreeFormattedAsString(false, 2, 200, 50)
                        .getBytes(StandardCharsets.UTF_8);
            case LARGE:
                return randomJsonGenerator.getRandomTreeFormattedAsString(true, 2, 2000, 400)
                        .getBytes(StandardCharsets.UTF_8);
            default:
                throw new RuntimeException("Unknown key: "+key);
        }
    }

    /**
     * TODO - run these tests with different sized trees.  I'd like to generate some random json
     * data of various sizes and to push those through.
     * @param dataName
     * @param chunkBound
     * @throws IOException
     */
    @ParameterizedTest
    @CsvSource({"tiny,2", "tiny,20000",
            "medium,2", "medium,20000",
            "large,2", "large,20000"
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
