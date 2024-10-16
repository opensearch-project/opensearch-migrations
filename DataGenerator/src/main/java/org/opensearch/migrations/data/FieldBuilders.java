package org.opensearch.migrations.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

/** Shared ways to build fields for index mappings */
@UtilityClass
public class FieldBuilders {
    public static final String DATE = "date";
    public static final String GEO_POINT = "geo_point";
    public static final String INTEGER = "integer";
    public static final String KEYWORD = "keyword";
    public static final String LONG = "long";
    public static final String TEXT = "text";

    public static final ObjectMapper mapper = new ObjectMapper();

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
