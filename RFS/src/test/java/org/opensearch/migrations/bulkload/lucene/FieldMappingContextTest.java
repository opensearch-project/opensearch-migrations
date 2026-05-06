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

    @Test
    void testNoFiltersMeansNothingExcluded() throws Exception {
        String json = """
            {
                "properties": {
                    "title": {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));
        assertFalse(context.isSourceExcluded("title"));
        assertFalse(context.isSourceExcluded("anything.else"));
    }

    @Test
    void testCopyToDestinationExcluded() throws Exception {
        String json = """
            {
                "properties": {
                    "title": {"type": "text", "copy_to": "all_text"},
                    "body":  {"type": "text", "copy_to": ["all_text", "combined.text"]},
                    "all_text": {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));
        assertTrue(context.isSourceExcluded("all_text"), "copy_to destination must be excluded");
        assertTrue(context.isSourceExcluded("combined.text"), "array-form copy_to entries must be excluded");
        assertFalse(context.isSourceExcluded("title"), "source fields must not be excluded");
        assertFalse(context.isSourceExcluded("body"), "source fields must not be excluded");
    }

    @Test
    void testSourceExcludes() throws Exception {
        String json = """
            {
                "_source": {"excludes": ["secret", "nested.*"]},
                "properties": {
                    "secret":   {"type": "keyword"},
                    "visible":  {"type": "keyword"},
                    "nested":   {"properties": {"a": {"type": "keyword"}, "b": {"type": "keyword"}}}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));
        assertTrue(context.isSourceExcluded("secret"));
        assertTrue(context.isSourceExcluded("nested.a"));
        assertTrue(context.isSourceExcluded("nested.b"));
        assertTrue(context.isSourceExcluded("nested.deep.path"));
        assertFalse(context.isSourceExcluded("visible"));
        assertFalse(context.isSourceExcluded("nested"), "'nested.*' must not match the bare parent");
    }

    @Test
    void testSourceIncludes() throws Exception {
        String json = """
            {
                "_source": {"includes": ["keep.*"]},
                "properties": {
                    "keep":  {"properties": {"a": {"type": "keyword"}}},
                    "drop":  {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));
        assertFalse(context.isSourceExcluded("keep.a"));
        assertFalse(context.isSourceExcluded("keep.deep.path"));
        assertTrue(context.isSourceExcluded("drop"), "outside includes -> excluded");
        assertTrue(context.isSourceExcluded("keep"), "'keep.*' does not match bare 'keep'");
    }

    @Test
    void testSourceExcludesWinsOverIncludes() throws Exception {
        // ES semantics: excludes is applied after includes, so overlapping entries are dropped.
        String json = """
            {
                "_source": {"includes": ["keep.*"], "excludes": ["keep.secret"]},
                "properties": {
                    "keep": {"properties": {
                        "visible": {"type": "keyword"},
                        "secret":  {"type": "keyword"}
                    }}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));
        assertFalse(context.isSourceExcluded("keep.visible"), "included and not excluded");
        assertTrue(context.isSourceExcluded("keep.secret"), "excludes must beat includes on overlap");
    }
}
