package org.opensearch.migrations.transform.replay;

import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test that verifies Python transformations work through the full
 * TransformationLoader SPI discovery pipeline.
 */
public class PythonTransformationE2ETest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testPythonTransformationViaSPI() throws Exception {
        // This inline Python script adds a "transformed" key and uppercases the "method" value
        var pythonConfig = "[{\"JsonPythonTransformerProvider\": {"
            + "\"bindingsObject\": \"{}\","
            + "\"initializationScript\": \"def main(context):\\n"
            + "    def transform(document):\\n"
            + "        document['transformed_by'] = 'python'\\n"
            + "        return document\\n"
            + "    return transform\\nmain\""
            + "}}]";

        var transformer = new TransformationLoader().getTransformerFactoryLoader(pythonConfig);

        var inputDoc = new LinkedHashMap<String, Object>();
        inputDoc.put("method", "PUT");
        inputDoc.put("URI", "/test-index");
        inputDoc.put("headers", Map.of("host", "localhost"));

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(inputDoc);

        Assertions.assertEquals("python", result.get("transformed_by"),
            "Python transformer should have added 'transformed_by' field");
        Assertions.assertEquals("PUT", result.get("method"),
            "Original fields should be preserved");
        Assertions.assertEquals("/test-index", result.get("URI"),
            "Original fields should be preserved");
    }

    @Test
    public void testPythonTransformationModifiesPayload() throws Exception {
        var pythonConfig = "[{\"JsonPythonTransformerProvider\": {"
            + "\"bindingsObject\": \"{\\\"prefix\\\": \\\"migrated_\\\"}\","
            + "\"initializationScript\": \"def main(context):\\n"
            + "    prefix = context.get('prefix')\\n"
            + "    def transform(document):\\n"
            + "        uri = document.get('URI')\\n"
            + "        if uri is not None:\\n"
            + "            document['URI'] = '/' + str(prefix) + str(uri)[1:]\\n"
            + "        return document\\n"
            + "    return transform\\nmain\""
            + "}}]";

        var transformer = new TransformationLoader().getTransformerFactoryLoader(pythonConfig);

        var inputDoc = new LinkedHashMap<String, Object>();
        inputDoc.put("method", "PUT");
        inputDoc.put("URI", "/my-index");
        inputDoc.put("headers", Map.of("host", "localhost"));

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(inputDoc);

        Assertions.assertEquals("/migrated_my-index", result.get("URI"),
            "Python transformer should have prefixed the URI");
    }
}
