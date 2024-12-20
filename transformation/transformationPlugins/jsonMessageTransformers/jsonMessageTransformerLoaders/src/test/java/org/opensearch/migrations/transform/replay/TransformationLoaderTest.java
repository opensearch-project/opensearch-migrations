package org.opensearch.migrations.transform.replay;

import java.util.Map;

import org.opensearch.migrations.transform.TransformationLoader;

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
        var toNewHostTransformer = new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("testhostname");
        var toOldHostTransformer = new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost");
        var origDoc = parseAsMap(SampleContents.loadSampleJsonRequestAsString());
        var origDocStr = mapper.writeValueAsString(origDoc);
        Object outputWithNewHostname = toNewHostTransformer.transformJson(origDoc);
        var docWithNewHostnameStr = mapper.writeValueAsString(outputWithNewHostname);
        Object outputWithOldHostname = toOldHostTransformer.transformJson(outputWithNewHostname);
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
        Object output = noopTransformer.transformJson(origDoc);
        Assertions.assertEquals(mapper.writeValueAsString(origDoc), mapper.writeValueAsString(output));
    }

    @Test
    public void testMisconfiguration() {
        var transformLoader = new TransformationLoader();
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> transformLoader.getTransformerFactoryLoader("localhost", null, "Not right")
        );
    }

    @Test
    public void testThatNoConfigMeansNoThrow() throws Exception {
        var transformer = Assertions.assertDoesNotThrow(
            () -> new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost")
        );
        Assertions.assertNotNull(transformer);
        var origDoc = parseAsMap(SampleContents.loadSampleJsonRequestAsString());
        Assertions.assertNotNull(transformer.transformJson(origDoc));
    }

    static final String TEST_INPUT_REQUEST = "{\n"
        + "  \"method\": \"PUT\",\n"
        + "  \"URI\": \"/oldStyleIndex\",\n"
        + "  \"headers\": {\n"
        + "    \"host\": \"127.0.0.1\"\n"
        + "  },\n"
        + "  \"payload\": {\n}\n"
        + "}\n";

    @Test
    @SuppressWarnings("unchecked")
    public void testUserAgentAppends() throws Exception {
        var userAgentTransformer = new TransformationLoader().getTransformerFactoryLoader("localhost", "tester", null);

        var origDoc = parseAsMap(TEST_INPUT_REQUEST);
        Object pass1 = userAgentTransformer.transformJson(origDoc);
        Object pass2 = userAgentTransformer.transformJson(pass1);
        var finalUserAgentInHeaders = ((Map<String, Object>) ((Map<String, Object>) pass2).get("headers")).get("user-agent");
        Assertions.assertEquals("tester; tester", finalUserAgentInHeaders);
    }
}
