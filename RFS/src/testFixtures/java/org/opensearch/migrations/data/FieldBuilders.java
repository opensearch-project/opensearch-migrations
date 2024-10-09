package org.opensearch.migrations.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class FieldBuilders {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static ObjectNode createField(String type) {
        var field = mapper.createObjectNode();
        field.put("type", type);
        return field;
    }

    public static ObjectNode createFieldTextRawKeyword() {
        var fieldNode = mapper.createObjectNode();
        fieldNode.put("type", "text");
        var fieldsNode = mapper.createObjectNode();
        fieldsNode.set("raw", createField("keyword"));
        fieldNode.set("fields", fieldsNode);
        return fieldNode;
    }
}
