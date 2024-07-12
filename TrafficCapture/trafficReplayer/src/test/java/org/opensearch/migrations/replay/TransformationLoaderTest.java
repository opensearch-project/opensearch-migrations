package org.opensearch.migrations.replay;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TransformationLoaderTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> parseAsMap(String contents) throws Exception {
        return mapper.readValue(contents.getBytes(), new TypeReference<>() {
        });
    }

    @Test
    public void testTransformationLoader() throws Exception {
        var toNewHostTransformer = new TransformationLoader().getTransformerFactoryLoader("testhostname");
        var toOldHostTransformer = new TransformationLoader().getTransformerFactoryLoader("localhost");
        var origDoc = parseAsMap(SampleContents.loadSampleJsonRequestAsString());
        var origDocStr = mapper.writeValueAsString(origDoc);
        var outputWithNewHostname = toNewHostTransformer.transformJson(origDoc);
        var docWithNewHostnameStr = mapper.writeValueAsString(outputWithNewHostname);
        var outputWithOldHostname = toOldHostTransformer.transformJson(outputWithNewHostname);
        var docWithOldHostnameStr = mapper.writeValueAsString(outputWithOldHostname);
        Assertions.assertEquals(origDocStr, docWithOldHostnameStr);
        Assertions.assertNotEquals(origDocStr, docWithNewHostnameStr);
    }

    @Test
    public void testThatSimpleNoopTransformerLoads() throws Exception {
        var noopTransformer = new TransformationLoader().getTransformerFactoryLoader(
            "localhost",
            null,
            "NoopTransformerProvider"
        );
        var origDoc = parseAsMap(SampleContents.loadSampleJsonRequestAsString());
        var output = noopTransformer.transformJson(origDoc);
        Assertions.assertEquals(mapper.writeValueAsString(origDoc), mapper.writeValueAsString(output));
    }

    @Test
    public void testMisconfiguration() throws Exception {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new TransformationLoader().getTransformerFactoryLoader("localhost", null, "Not right")
        );
    }

    @Test
    public void testThatNoConfigMeansNoThrow() throws Exception {
        var transformer = Assertions.assertDoesNotThrow(
            () -> new TransformationLoader().getTransformerFactoryLoader("localhost")
        );
        Assertions.assertNotNull(transformer);
        var origDoc = parseAsMap(SampleContents.loadSampleJsonRequestAsString());
        Assertions.assertNotNull(transformer.transformJson(origDoc));
    }

    final String TEST_INPUT_REQUEST = "{\n"
        + "  \"method\": \"PUT\",\n"
        + "  \"URI\": \"/oldStyleIndex\",\n"
        + "  \"headers\": {\n"
        + "    \"host\": \"127.0.0.1\"\n"
        + "  },\n"
        + "  \"payload\": {\n}\n"
        + "}\n";

    @Test
    public void testUserAgentAppends() throws Exception {
        var userAgentTransformer = new TransformationLoader().getTransformerFactoryLoader("localhost", "tester", null);

        var origDoc = parseAsMap(TEST_INPUT_REQUEST);
        var origDocStr = mapper.writeValueAsString(origDoc);
        var pass1 = userAgentTransformer.transformJson(origDoc);
        var pass1DocStr = mapper.writeValueAsString(origDoc);
        var pass2 = userAgentTransformer.transformJson(pass1);
        var finalUserAgentInHeaders = ((Map<String, Object>) pass2.get("headers")).get("user-agent");
        Assertions.assertEquals("tester; tester", finalUserAgentInHeaders);
    }
}
