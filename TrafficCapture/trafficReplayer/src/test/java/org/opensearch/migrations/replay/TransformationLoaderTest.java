package org.opensearch.migrations.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class TransformationLoaderTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    private static Map<String,Object> parseAsMap(String contents) throws Exception {
        return mapper.readValue(contents.getBytes(), new TypeReference<>() {});
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

}
