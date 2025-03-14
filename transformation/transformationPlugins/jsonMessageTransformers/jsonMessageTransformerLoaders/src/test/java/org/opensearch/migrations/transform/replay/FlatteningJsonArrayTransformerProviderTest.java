package org.opensearch.migrations.transform.replay;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class FlatteningJsonArrayTransformerProviderTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> parseAsMap(String json) throws Exception {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    @Test
    public void testFlatteningJsonArrayTransformer() throws Exception {
        // Define a simple JOLT transformation that renames "oldKey" to "newKey"
        String simpleJoltTransform = "{ \"JsonJoltTransformerProvider\": { \"script\": " +
                "{ \"operation\": \"shift\", \"spec\": { \"oldKey\": \"newKey\" } } } }";


        String wrappedConfig = "[{ \"FlatteningJsonArrayTransformerProvider\": " + simpleJoltTransform + " }]";

        // Create the transformer using TransformationLoader
        var transformer = new TransformationLoader().getTransformerFactoryLoader(
                null,
                null,
                wrappedConfig
        );

        // Define input list
        List<Map<String, Object>> inputList = List.of(
                parseAsMap("{ \"oldKey\": \"value1\" }"),
                parseAsMap("{ \"oldKey\": \"value2\" }")
        );

        // Apply transformation
        Object transformedOutput = transformer.transformJson(inputList);

        // Ensure the output is a flattened list
        Assertions.assertInstanceOf(List.class, transformedOutput);
        List<?> transformedList = (List<?>) transformedOutput;

        // Check that all items were transformed correctly and in correct order
        Assertions.assertEquals(2, transformedList.size());
        Assertions.assertEquals("value1", ((Map<?, ?>) transformedList.get(0)).get("newKey"));
        Assertions.assertEquals("value2", ((Map<?, ?>) transformedList.get(1)).get("newKey"));
    }
}
