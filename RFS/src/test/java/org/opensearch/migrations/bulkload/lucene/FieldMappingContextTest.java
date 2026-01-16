package org.opensearch.migrations.bulkload.lucene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FieldMappingContextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testNullMappings() {
        FieldMappingContext context = new FieldMappingContext(null);
        assertTrue(context.getFieldNames().isEmpty());
        assertNull(context.getFieldInfo("any_field"));
    }

    @Test
    void testDirectProperties() throws Exception {
        String json = """
            {
                "properties": {
                    "name": {"type": "keyword"},
                    "age": {"type": "integer"}
                }
            }
            """;
        JsonNode mappings = MAPPER.readTree(json);
        FieldMappingContext context = new FieldMappingContext(mappings);

        assertEquals(2, context.getFieldNames().size());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("name").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("age").type());
    }

    @Test
    void testTypedMappings() throws Exception {
        // ES 6.x style: {"_doc": {"properties": {...}}}
        String json = """
            {
                "_doc": {
                    "properties": {
                        "title": {"type": "text"},
                        "count": {"type": "long"}
                    }
                }
            }
            """;
        JsonNode mappings = MAPPER.readTree(json);
        FieldMappingContext context = new FieldMappingContext(mappings);

        assertEquals(2, context.getFieldNames().size());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("title").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("count").type());
    }

    @Test
    void testArrayMappings() throws Exception {
        // ES 1.x-5.x multi-type style
        String json = """
            [
                {
                    "my_type": {
                        "properties": {
                            "field1": {"type": "keyword"}
                        }
                    }
                }
            ]
            """;
        JsonNode mappings = MAPPER.readTree(json);
        FieldMappingContext context = new FieldMappingContext(mappings);

        assertEquals(1, context.getFieldNames().size());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("field1").type());
    }

    @Test
    void testNestedProperties() throws Exception {
        String json = """
            {
                "properties": {
                    "user": {
                        "properties": {
                            "name": {"type": "keyword"},
                            "email": {"type": "keyword"}
                        }
                    }
                }
            }
            """;
        JsonNode mappings = MAPPER.readTree(json);
        FieldMappingContext context = new FieldMappingContext(mappings);

        assertEquals(2, context.getFieldNames().size());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("user.name").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("user.email").type());
    }

    @Test
    void testDocValuesDisabled() throws Exception {
        String json = """
            {
                "properties": {
                    "no_dv": {"type": "keyword", "doc_values": false},
                    "with_dv": {"type": "keyword"}
                }
            }
            """;
        JsonNode mappings = MAPPER.readTree(json);
        FieldMappingContext context = new FieldMappingContext(mappings);

        assertFalse(context.getFieldInfo("no_dv").docValues());
        assertTrue(context.getFieldInfo("with_dv").docValues());
    }

    @Test
    void testEmptyArrayMappings() throws Exception {
        String json = "[]";
        JsonNode mappings = MAPPER.readTree(json);
        FieldMappingContext context = new FieldMappingContext(mappings);

        assertTrue(context.getFieldNames().isEmpty());
    }

    @Test
    void testEmptyObjectMappings() throws Exception {
        String json = "{}";
        JsonNode mappings = MAPPER.readTree(json);
        FieldMappingContext context = new FieldMappingContext(mappings);

        assertTrue(context.getFieldNames().isEmpty());
    }
}
