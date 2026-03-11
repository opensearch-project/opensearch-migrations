package org.opensearch.migrations.transform;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests that pydantic (installed via GraalPy Gradle plugin) is usable in transformations.
 * The bindingsObject is passed as a JSON string so pydantic can parse it with model_validate_json.
 */
public class PydanticTransformerTest {

    private static final String PYDANTIC_METADATA_TRANSFORM =
        "from pydantic import BaseModel\n"
            + "from typing import List as TypingList\n"
            + "\n"
            + "class FieldTypeRule(BaseModel):\n"
            + "    source_type: str\n"
            + "    target_type: str\n"
            + "    remove_keys: TypingList[str] = []\n"
            + "\n"
            + "class TransformConfig(BaseModel):\n"
            + "    rules: TypingList[FieldTypeRule]\n"
            + "\n"
            + "def _apply_rules(node, rules):\n"
            + "    if hasattr(node, 'get') and hasattr(node, '__setitem__'):\n"
            + "        for rule in rules:\n"
            + "            t = node.get('type')\n"
            + "            if t is not None and str(t) == rule.source_type:\n"
            + "                node['type'] = rule.target_type\n"
            + "                for k in rule.remove_keys:\n"
            + "                    if k in node:\n"
            + "                        del node[k]\n"
            + "        for key in list(node.keys()):\n"
            + "            _apply_rules(node[key], rules)\n"
            + "    elif hasattr(node, '__iter__') and not isinstance(node, (str, bytes)):\n"
            + "        for item in node:\n"
            + "            _apply_rules(item, rules)\n"
            + "\n"
            + "def main(context):\n"
            + "    config = TransformConfig.model_validate_json(str(context))\n"
            + "    def transform(document):\n"
            + "        body = document.get('body')\n"
            + "        if body is not None:\n"
            + "            _apply_rules(body, config.rules)\n"
            + "        return document\n"
            + "    return transform\n"
            + "\n"
            + "main\n";

    private static final String CONFIG_JSON =
        "{\"rules\": [{\"source_type\": \"string\", "
            + "\"target_type\": \"text\", \"remove_keys\": [\"doc_values\"]}]}";

    @Test
    @SuppressWarnings("unchecked")
    public void testPydanticMetadataTransform() throws Exception {
        // Pass the raw JSON string as context — pydantic parses it directly
        try (var transformer = new PythonTransformer(PYDANTIC_METADATA_TRANSFORM, CONFIG_JSON)) {
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

            var doc = new HashMap<String, Object>();
            doc.put("body", body);
            doc.put("type", "index");
            doc.put("name", "test_index");

            var result = (Map<String, Object>) transformer.transformJson(doc);
            var resultBody = (Map<String, Object>) result.get("body");
            var resultMappings = (Map<String, Object>) resultBody.get("mappings");
            var resultProps = (Map<String, Object>) resultMappings.get("properties");
            var resultName = (Map<String, Object>) resultProps.get("name");

            Assertions.assertEquals("text", resultName.get("type"));
            Assertions.assertFalse(resultName.containsKey("doc_values"));
            Assertions.assertEquals("standard", resultName.get("analyzer"));
        }
    }

    @Test
    public void testPydanticValidationRejectsInvalidConfig() {
        var badJson = "{\"rules\": [{\"target_type\": \"text\"}]}";
        Assertions.assertThrows(Exception.class, () -> {
            new PythonTransformer(PYDANTIC_METADATA_TRANSFORM, badJson);
        });
    }
}
