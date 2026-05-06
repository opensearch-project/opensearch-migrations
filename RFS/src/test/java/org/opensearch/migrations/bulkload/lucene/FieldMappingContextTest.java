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

    // ---------------------------------------------------------------------------------------
    // isSourceExcluded(): unified gate for copy_to targets + _source.includes/_source.excludes.
    // Cheap no-op when neither directive is declared; otherwise applies ES semantics (excludes
    // wins over includes; copy_to targets always suppressed).
    // ---------------------------------------------------------------------------------------

    @Test
    void testNoFiltersMeansNothingExcluded() throws Exception {
        String json = """
            {
                "properties": {
                    "title": {"type": "text"},
                    "body":  {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertFalse(context.isSourceExcluded("title"));
        assertFalse(context.isSourceExcluded("body"));
        assertFalse(context.isSourceExcluded("anything.else"));
    }

    @Test
    void testCopyToDestinationExcludedFromSource() throws Exception {
        // Scalar and array-form copy_to; both destinations must be treated as absent from _source.
        String json = """
            {
                "properties": {
                    "title":     {"type": "text", "copy_to": "all_text"},
                    "body":      {"type": "text", "copy_to": ["all_text", "summary"]},
                    "all_text":  {"type": "text"},
                    "summary":   {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("all_text"),  "copy_to destination must be suppressed");
        assertTrue(context.isSourceExcluded("summary"),   "array-form copy_to destination must be suppressed");
        assertFalse(context.isSourceExcluded("title"),    "copy_to SOURCE field stays in _source");
        assertFalse(context.isSourceExcluded("body"),     "copy_to SOURCE field stays in _source");
    }

    @Test
    void testSourceExcludesExactAndGlob() throws Exception {
        // Exact path and trailing .* glob. Mirrors ES source-filter semantics.
        String json = """
            {
                "_source": {"excludes": ["secret", "meta.*"]},
                "properties": {
                    "title":     {"type": "text"},
                    "secret":    {"type": "keyword"},
                    "meta": {
                        "properties": {
                            "created_at": {"type": "date"},
                            "updated_by": {"type": "keyword"}
                        }
                    }
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("secret"));
        assertTrue(context.isSourceExcluded("meta.created_at"));
        assertTrue(context.isSourceExcluded("meta.updated_by"));
        assertFalse(context.isSourceExcluded("title"));
    }

    @Test
    void testSourceExcludesDoubleStarGlob() throws Exception {
        // Trailing .** is accepted as equivalent to .* (ES treats both as "everything below").
        String json = """
            {
                "_source": {"excludes": ["private.**"]},
                "properties": {
                    "public":  {"type": "text"},
                    "private": {"properties": {"ssn": {"type": "keyword"}}}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("private.ssn"));
        assertFalse(context.isSourceExcluded("public"));
    }

    @Test
    void testSourceIncludesFiltersEverythingElse() throws Exception {
        // When includes is set, paths that match no include glob are excluded. Excludes still
        // apply on top and win for paths that match both.
        String json = """
            {
                "_source": {"includes": ["title", "body"]},
                "properties": {
                    "title":   {"type": "text"},
                    "body":    {"type": "text"},
                    "dropped": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertFalse(context.isSourceExcluded("title"));
        assertFalse(context.isSourceExcluded("body"));
        assertTrue(context.isSourceExcluded("dropped"));
    }

    @Test
    void testSourceExcludesWinsOverIncludes() throws Exception {
        // ES semantics: excludes is applied AFTER includes, so a path that matches both is dropped.
        String json = """
            {
                "_source": {
                    "includes": ["*"],
                    "excludes": ["secret"]
                },
                "properties": {
                    "title":  {"type": "text"},
                    "secret": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertFalse(context.isSourceExcluded("title"),  "wildcard include keeps title");
        assertTrue(context.isSourceExcluded("secret"),  "excludes beats includes even with '*' include");
    }

    @Test
    void testSourceFilterStarIncludesEverything() throws Exception {
        String json = """
            {
                "_source": {"includes": "*"},
                "properties": {
                    "a": {"type": "keyword"},
                    "b": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertFalse(context.isSourceExcluded("a"));
        assertFalse(context.isSourceExcluded("b"));
    }

    @Test
    void testUnsupportedMidStringGlobFallsBackToLiteral() throws Exception {
        // Mid-string '*' is not a supported shape in this pragmatic subset; we log and treat the
        // pattern as a literal rather than silently dropping or silently matching everything.
        String json = """
            {
                "_source": {"excludes": ["pre*fix"]},
                "properties": {
                    "pre*fix": {"type": "keyword"},
                    "prefix":  {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("pre*fix"), "literal match on the exact pattern");
        assertFalse(context.isSourceExcluded("prefix"), "NOT a wildcard expansion");
    }

    @Test
    void testCopyToCombinedWithSourceExcludes() throws Exception {
        // Same index declares BOTH a copy_to destination AND a _source.excludes entry.
        // Both paths must be excluded; ordinary fields are not.
        String json = """
            {
                "_source": {"excludes": ["internal_id"]},
                "properties": {
                    "title":       {"type": "text", "copy_to": "all_text"},
                    "body":        {"type": "text", "copy_to": "all_text"},
                    "all_text":    {"type": "text"},
                    "internal_id": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("all_text"));
        assertTrue(context.isSourceExcluded("internal_id"));
        assertFalse(context.isSourceExcluded("title"));
        assertFalse(context.isSourceExcluded("body"));
    }
}
