package org.opensearch.migrations.transform.replay;

import java.util.HashMap;
import java.util.Map;

import org.opensearch.migrations.transform.TransformationLoader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * E2E test: typed Python metadata transformation loaded via SPI.
 * Uses the bundled python/metadata_transform.py resource with dataclass-based rules.
 */
public class PythonMetadataTransformE2ETest {

    @Test
    @SuppressWarnings("unchecked")
    public void testMetadataFieldTypeRewrite() throws Exception {
        var config = "[{\"JsonPythonTransformerProvider\": {"
            + "\"initializationResourcePath\": \"python/metadata_transform.py\","
            + "\"bindingsObject\": \"{\\\"rules\\\": [{\\\"source_type\\\": \\\"string\\\", "
            + "\\\"target_type\\\": \\\"text\\\", \\\"remove_keys\\\": [\\\"doc_values\\\"]}]}\""
            + "}}]";

        var transformer = new TransformationLoader().getTransformerFactoryLoader(config);

        // Simulate an index metadata item as the adapter would serialize it
        var nameField = new HashMap<String, Object>();
        nameField.put("type", "string");
        nameField.put("doc_values", true);
        nameField.put("analyzer", "standard");

        var properties = new HashMap<String, Object>();
        properties.put("name", nameField);

        var mappings = new HashMap<String, Object>();
        mappings.put("properties", properties);

        var body = new HashMap<String, Object>();
        body.put("mappings", mappings);

        var indexMetadata = new HashMap<String, Object>();
        indexMetadata.put("type", "index");
        indexMetadata.put("name", "test_index");
        indexMetadata.put("body", body);

        var result = (Map<String, Object>) transformer.transformJson(indexMetadata);
        var resultBody = (Map<String, Object>) result.get("body");
        var resultMappings = (Map<String, Object>) resultBody.get("mappings");
        var resultProps = (Map<String, Object>) resultMappings.get("properties");
        var resultName = (Map<String, Object>) resultProps.get("name");

        Assertions.assertEquals("text", resultName.get("type"),
            "Field type should be rewritten from 'string' to 'text'");
        Assertions.assertFalse(resultName.containsKey("doc_values"),
            "doc_values should be removed per rule");
        Assertions.assertEquals("standard", resultName.get("analyzer"),
            "Other field properties should be preserved");
    }
}
