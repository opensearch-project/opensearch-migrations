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
    void testSourceExcludesBareNameSuppressesDescendants() throws Exception {
        // ES semantics: {"excludes": ["meta"]} drops the whole meta object — every meta.<child>
        // path must be suppressed. Equals-only matching would leak descendants.
        String json = """
            {
                "_source": {"excludes": ["meta"]},
                "properties": {
                    "title": {"type": "text"},
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

        assertTrue(context.isSourceExcluded("meta"),             "bare-name literal must drop the path itself");
        assertTrue(context.isSourceExcluded("meta.created_at"),  "bare-name literal must drop direct child");
        assertTrue(context.isSourceExcluded("meta.updated_by"),  "bare-name literal must drop direct child");
        assertFalse(context.isSourceExcluded("title"),           "unrelated path stays");
        assertFalse(context.isSourceExcluded("metadata"),        "prefix-only collision must NOT match (no trailing dot)");
    }

    @Test
    void testSourceExcludesDottedLiteralSuppressesDescendants() throws Exception {
        // {"excludes": ["meta.audit"]} drops meta.audit AND meta.audit.user, meta.audit.ts, etc.
        String json = """
            {
                "_source": {"excludes": ["meta.audit"]},
                "properties": {
                    "meta": {
                        "properties": {
                            "audit": {
                                "properties": {
                                    "user": {"type": "keyword"},
                                    "ts":   {"type": "date"}
                                }
                            },
                            "title": {"type": "keyword"}
                        }
                    }
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("meta.audit"),       "literal exact match");
        assertTrue(context.isSourceExcluded("meta.audit.user"),  "literal must drop nested descendant");
        assertTrue(context.isSourceExcluded("meta.audit.ts"),    "literal must drop nested descendant");
        assertFalse(context.isSourceExcluded("meta.title"),      "sibling path is unaffected");
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
    void testBareStarPrefixSuffixGlobs() throws Exception {
        // ES _source.includes/excludes treats a bare `*` (no dot delimiter) as a wildcard:
        // `prefix*`, `*suffix`, and `prefix*suffix` all match anything that begins/ends with
        // the literal segments. We honour those three single-`*` shapes; multi-`*` and `?`
        // patterns still fall through to literal match (logged as unsupported).
        String json = """
            {
                "_source": {"excludes": ["pre*fix", "foo*", "*bar", "a*b*c"]},
                "properties": {
                    "prefix":      {"type": "keyword"},
                    "preXYZfix":   {"type": "keyword"},
                    "foo":         {"type": "keyword"},
                    "foobar":      {"type": "keyword"},
                    "bazbar":      {"type": "keyword"},
                    "unrelated":   {"type": "keyword"},
                    "a*b*c":       {"type": "keyword"},
                    "axxbyyc":     {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        // `pre*fix` matches `prefix` (empty middle) and `preXYZfix` (non-empty middle).
        assertTrue(context.isSourceExcluded("prefix"), "pre*fix matches empty middle");
        assertTrue(context.isSourceExcluded("preXYZfix"), "pre*fix matches non-empty middle");
        // `foo*` matches anything starting with `foo`.
        assertTrue(context.isSourceExcluded("foo"), "foo* matches exact prefix (empty tail)");
        assertTrue(context.isSourceExcluded("foobar"), "foo* matches longer prefix");
        // `*bar` matches anything ending in `bar`.
        assertTrue(context.isSourceExcluded("bazbar"), "*bar matches suffix");
        assertTrue(context.isSourceExcluded("foobar"), "*bar matches suffix even when also caught by foo*");
        // No glob matches `unrelated`.
        assertFalse(context.isSourceExcluded("unrelated"), "no glob matches unrelated");
        // Multi-`*` patterns are NOT supported as globs — they fall through to literal match.
        assertTrue(context.isSourceExcluded("a*b*c"), "multi-star pattern matches itself literally");
        assertFalse(context.isSourceExcluded("axxbyyc"), "multi-star pattern does NOT wildcard-expand");
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
