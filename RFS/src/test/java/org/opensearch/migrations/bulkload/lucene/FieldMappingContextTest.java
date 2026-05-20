package org.opensearch.migrations.bulkload.lucene;

import java.util.List;
import java.util.Set;

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

    // ---------------------------------------------------------------------------------------
    // extractMappingContainer edge cases: non-object/non-array nodes, malformed arrays
    // ---------------------------------------------------------------------------------------

    @Test
    void testScalarMappingsNodeReturnsEmpty() throws Exception {
        // A bare string/number/boolean is neither array nor object -> null container -> empty context
        FieldMappingContext fromString = new FieldMappingContext(MAPPER.readTree("\"hello\""));
        assertTrue(fromString.getFieldNames().isEmpty());

        FieldMappingContext fromNumber = new FieldMappingContext(MAPPER.readTree("42"));
        assertTrue(fromNumber.getFieldNames().isEmpty());

        FieldMappingContext fromBool = new FieldMappingContext(MAPPER.readTree("true"));
        assertTrue(fromBool.getFieldNames().isEmpty());
    }

    @Test
    void testArrayWithNonObjectFirstElement() throws Exception {
        // Array whose first element is a string, not an object -> null container
        String json = "[\"not_an_object\"]";
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));
        assertTrue(context.getFieldNames().isEmpty());
    }

    @Test
    void testArrayWithEmptyObjectFirstElement() throws Exception {
        // Array whose first element is an empty object -> no fields -> null container
        String json = "[{}]";
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));
        assertTrue(context.getFieldNames().isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // Object fields with no "type" key (pure object containers) are not registered as fields
    // but their nested children are. Also tests deeply nested (3+ levels) dot-notation paths.
    // ---------------------------------------------------------------------------------------

    @Test
    void testObjectContainerFieldNotRegistered() throws Exception {
        // "address" has no "type", only nested properties -> should not appear in fieldMappings
        String json = """
            {
                "properties": {
                    "address": {
                        "properties": {
                            "city": {"type": "keyword"},
                            "zip":  {"type": "keyword"}
                        }
                    }
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertNull(context.getFieldInfo("address"), "pure object container should not be a registered field");
        assertEquals(EsFieldType.STRING, context.getFieldInfo("address.city").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("address.zip").type());
        assertEquals(2, context.getFieldNames().size());
    }

    @Test
    void testDeeplyNestedDotNotation() throws Exception {
        // 3+ levels of nesting: company.address.geo.lat
        String json = """
            {
                "properties": {
                    "company": {
                        "properties": {
                            "address": {
                                "properties": {
                                    "geo": {
                                        "properties": {
                                            "lat": {"type": "float"},
                                            "lon": {"type": "float"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("company.address.geo.lat").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("company.address.geo.lon").type());
        assertNull(context.getFieldInfo("company"), "intermediate container not registered");
        assertNull(context.getFieldInfo("company.address"), "intermediate container not registered");
        assertNull(context.getFieldInfo("company.address.geo"), "intermediate container not registered");
    }

    // ---------------------------------------------------------------------------------------
    // Mixed field: has both "type" AND nested "properties" (e.g., "object" type with sub-fields)
    // ---------------------------------------------------------------------------------------

    @Test
    void testFieldWithTypeAndNestedProperties() throws Exception {
        // A field that declares a type AND has nested properties (ES "nested" type)
        String json = """
            {
                "properties": {
                    "comments": {
                        "type": "nested",
                        "properties": {
                            "author": {"type": "keyword"},
                            "text":   {"type": "text"}
                        }
                    }
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        // "comments" itself is registered (type=nested -> UNSUPPORTED)
        assertNotNull(context.getFieldInfo("comments"));
        assertEquals(EsFieldType.UNSUPPORTED, context.getFieldInfo("comments").type());
        // Children are also registered
        assertEquals(EsFieldType.STRING, context.getFieldInfo("comments.author").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("comments.text").type());
    }

    // ---------------------------------------------------------------------------------------
    // copy_to edge cases: null, empty array, array with non-textual elements, copy_to on
    // object containers (no "type" key)
    // ---------------------------------------------------------------------------------------

    @Test
    void testCopyToNullValueIgnored() throws Exception {
        String json = """
            {
                "properties": {
                    "title": {"type": "text", "copy_to": null}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertFalse(context.isCopyToTarget("title"));
        assertTrue(context.getCopyToSourceFields().isEmpty());
    }

    @Test
    void testCopyToEmptyArrayIgnored() throws Exception {
        String json = """
            {
                "properties": {
                    "title": {"type": "text", "copy_to": []}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.getCopyToSourceFields().isEmpty());
        assertTrue(context.getCopyToTargets("title").isEmpty());
    }

    @Test
    void testCopyToArrayWithNonTextualElementsFiltered() throws Exception {
        // Only textual elements in the copy_to array should be collected
        String json = """
            {
                "properties": {
                    "title":    {"type": "text", "copy_to": ["all_text", 42, true, null]},
                    "all_text": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isCopyToTarget("all_text"));
        List<String> targets = context.getCopyToTargets("title");
        assertEquals(1, targets.size());
        assertEquals("all_text", targets.get(0));
    }

    // ---------------------------------------------------------------------------------------
    // getCopyToSources / getCopyToSourceFields / getCopyToTargets: comprehensive coverage
    // ---------------------------------------------------------------------------------------

    @Test
    void testGetCopyToSourcesReturnsSources() throws Exception {
        String json = """
            {
                "properties": {
                    "title":    {"type": "text", "copy_to": "all_text"},
                    "body":     {"type": "text", "copy_to": "all_text"},
                    "all_text": {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        List<String> sources = context.getCopyToSources("all_text");
        assertEquals(2, sources.size());
        assertTrue(sources.contains("title"));
        assertTrue(sources.contains("body"));
    }

    @Test
    void testGetCopyToSourcesReturnsEmptyForNonTarget() throws Exception {
        String json = """
            {
                "properties": {
                    "title": {"type": "text", "copy_to": "all_text"},
                    "all_text": {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.getCopyToSources("title").isEmpty(), "non-target field returns empty");
        assertTrue(context.getCopyToSources("nonexistent").isEmpty(), "unknown field returns empty");
    }

    @Test
    void testGetCopyToSourceFieldsReturnsOnlySourcesWithEdges() throws Exception {
        String json = """
            {
                "properties": {
                    "title":    {"type": "text", "copy_to": "all_text"},
                    "body":     {"type": "text"},
                    "all_text": {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        Set<String> sourceFields = context.getCopyToSourceFields();
        assertEquals(1, sourceFields.size());
        assertTrue(sourceFields.contains("title"));
        assertFalse(sourceFields.contains("body"), "body has no copy_to");
        assertFalse(sourceFields.contains("all_text"), "all_text is a target, not a source");
    }

    @Test
    void testGetCopyToTargetsReturnsEmptyForUnknownField() throws Exception {
        String json = """
            {
                "properties": {
                    "title": {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.getCopyToTargets("title").isEmpty());
        assertTrue(context.getCopyToTargets("nonexistent").isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // lossinessRank / getCopyToTargets sorting: exercise every tier in the switch statement
    // ---------------------------------------------------------------------------------------

    @Test
    void testCopyToTargetsSortedByLossiness() throws Exception {
        // Source field copies to targets of every lossiness tier. Verify sort order:
        //   constant_keyword (tier 0) < keyword (tier 1) < match_only_text (tier 2) < text (tier 3)
        String json = """
            {
                "properties": {
                    "source": {
                        "type": "text",
                        "copy_to": ["t_text", "t_match_only", "t_keyword", "t_constant"]
                    },
                    "t_text":       {"type": "text"},
                    "t_match_only": {"type": "match_only_text"},
                    "t_keyword":    {"type": "keyword"},
                    "t_constant":   {"type": "constant_keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        List<String> sorted = context.getCopyToTargets("source");
        assertEquals(4, sorted.size());
        assertEquals("t_constant", sorted.get(0), "constant_keyword = tier 0, first");
        assertEquals("t_keyword", sorted.get(1), "keyword = tier 1, second");
        assertEquals("t_match_only", sorted.get(2), "match_only_text = tier 2, third");
        assertEquals("t_text", sorted.get(3), "text = tier 3, last");
    }

    @Test
    void testCopyToTargetsSortDocValuesTiebreak() throws Exception {
        // Two keyword targets: one with doc_values (default=true), one with doc_values=false.
        // Both are tier 1, but the one with doc_values=true should sort first (lower penalty).
        String json = """
            {
                "properties": {
                    "source": {
                        "type": "text",
                        "copy_to": ["kw_no_dv", "kw_with_dv"]
                    },
                    "kw_no_dv":   {"type": "keyword", "doc_values": false},
                    "kw_with_dv": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        List<String> sorted = context.getCopyToTargets("source");
        assertEquals(2, sorted.size());
        assertEquals("kw_with_dv", sorted.get(0), "doc_values=true sorts before doc_values=false");
        assertEquals("kw_no_dv", sorted.get(1));
    }

    @Test
    void testCopyToTargetsUnknownTargetSortsLast() throws Exception {
        // Target "unknown_target" is NOT in the mapping -> lossinessRank returns 100 (last resort).
        // Known keyword target sorts before it.
        String json = """
            {
                "properties": {
                    "source": {
                        "type": "text",
                        "copy_to": ["unknown_target", "kw_target"]
                    },
                    "kw_target": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        List<String> sorted = context.getCopyToTargets("source");
        assertEquals(2, sorted.size());
        assertEquals("kw_target", sorted.get(0), "known keyword target sorts first");
        assertEquals("unknown_target", sorted.get(1), "unmapped target sorts last");
    }

    @Test
    void testCopyToTargetsDefaultTierForNumericTypes() throws Exception {
        // Numeric types (integer, long, float, etc.) should fall into the default tier (tier 1)
        // alongside keyword. Verify they sort before text (tier 3).
        String json = """
            {
                "properties": {
                    "source": {
                        "type": "text",
                        "copy_to": ["t_text", "t_long", "t_ip", "t_bool", "t_date"]
                    },
                    "t_text": {"type": "text"},
                    "t_long": {"type": "long"},
                    "t_ip":   {"type": "ip"},
                    "t_bool": {"type": "boolean"},
                    "t_date": {"type": "date"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        List<String> sorted = context.getCopyToTargets("source");
        assertEquals(5, sorted.size());
        // All tier 1 fields come before the tier 3 "text" field
        assertEquals("t_text", sorted.get(sorted.size() - 1), "text field is last (tier 3)");
        // First 4 are all tier 1 (order among them is stable from declaration order)
        assertNotEquals("t_text", sorted.get(0));
    }

    // ---------------------------------------------------------------------------------------
    // FieldMappingInfo record fields: format, scalingFactor, constantValue
    // ---------------------------------------------------------------------------------------

    @Test
    void testDateFieldWithFormat() throws Exception {
        String json = """
            {
                "properties": {
                    "created_at": {"type": "date", "format": "yyyy-MM-dd"},
                    "updated_at": {"type": "date_nanos", "format": "strict_date_optional_time_nanos"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        FieldMappingInfo created = context.getFieldInfo("created_at");
        assertEquals(EsFieldType.DATE, created.type());
        assertEquals("date", created.mappingType());
        assertEquals("yyyy-MM-dd", created.format());

        FieldMappingInfo updated = context.getFieldInfo("updated_at");
        assertEquals(EsFieldType.DATE_NANOS, updated.type());
        assertEquals("strict_date_optional_time_nanos", updated.format());
    }

    @Test
    void testScaledFloatWithScalingFactor() throws Exception {
        String json = """
            {
                "properties": {
                    "price": {"type": "scaled_float", "scaling_factor": 100}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        FieldMappingInfo price = context.getFieldInfo("price");
        assertEquals(EsFieldType.SCALED_FLOAT, price.type());
        assertEquals("scaled_float", price.mappingType());
        assertEquals(100.0, price.scalingFactor());
    }

    @Test
    void testConstantKeywordWithValue() throws Exception {
        String json = """
            {
                "properties": {
                    "env": {"type": "constant_keyword", "value": "production"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        FieldMappingInfo env = context.getFieldInfo("env");
        assertEquals(EsFieldType.STRING, env.type());
        assertEquals("constant_keyword", env.mappingType());
        assertEquals("production", env.constantValue());
        assertTrue(env.docValues());
    }

    @Test
    void testConstantKeywordWithoutValue() throws Exception {
        // constant_keyword declared but no "value" key -> constantValue should be null
        String json = """
            {
                "properties": {
                    "env": {"type": "constant_keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        FieldMappingInfo env = context.getFieldInfo("env");
        assertEquals("constant_keyword", env.mappingType());
        assertNull(env.constantValue());
    }

    // ---------------------------------------------------------------------------------------
    // Various field types -> EsFieldType mapping coverage
    // ---------------------------------------------------------------------------------------

    @Test
    void testAllFieldTypeMappings() throws Exception {
        String json = """
            {
                "properties": {
                    "f_byte":          {"type": "byte"},
                    "f_short":         {"type": "short"},
                    "f_integer":       {"type": "integer"},
                    "f_long":          {"type": "long"},
                    "f_float":         {"type": "float"},
                    "f_double":        {"type": "double"},
                    "f_half_float":    {"type": "half_float"},
                    "f_token_count":   {"type": "token_count"},
                    "f_unsigned_long": {"type": "unsigned_long"},
                    "f_scaled_float":  {"type": "scaled_float", "scaling_factor": 10},
                    "f_boolean":       {"type": "boolean"},
                    "f_date":          {"type": "date"},
                    "f_date_nanos":    {"type": "date_nanos"},
                    "f_ip":            {"type": "ip"},
                    "f_geo_point":     {"type": "geo_point"},
                    "f_keyword":       {"type": "keyword"},
                    "f_text":          {"type": "text"},
                    "f_string":        {"type": "string"},
                    "f_const_kw":      {"type": "constant_keyword"},
                    "f_version":       {"type": "version"},
                    "f_wildcard":      {"type": "wildcard"},
                    "f_match_only":    {"type": "match_only_text"},
                    "f_flat_object":   {"type": "flat_object"},
                    "f_binary":        {"type": "binary"},
                    "f_int_range":     {"type": "integer_range"},
                    "f_geo_shape":     {"type": "geo_shape"},
                    "f_unknown":       {"type": "some_future_type"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("f_byte").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("f_short").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("f_integer").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("f_long").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("f_float").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("f_double").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("f_half_float").type());
        assertEquals(EsFieldType.NUMERIC, context.getFieldInfo("f_token_count").type());
        assertEquals(EsFieldType.UNSIGNED_LONG, context.getFieldInfo("f_unsigned_long").type());
        assertEquals(EsFieldType.SCALED_FLOAT, context.getFieldInfo("f_scaled_float").type());
        assertEquals(EsFieldType.BOOLEAN, context.getFieldInfo("f_boolean").type());
        assertEquals(EsFieldType.DATE, context.getFieldInfo("f_date").type());
        assertEquals(EsFieldType.DATE_NANOS, context.getFieldInfo("f_date_nanos").type());
        assertEquals(EsFieldType.IP, context.getFieldInfo("f_ip").type());
        assertEquals(EsFieldType.GEO_POINT, context.getFieldInfo("f_geo_point").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("f_keyword").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("f_text").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("f_string").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("f_const_kw").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("f_version").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("f_wildcard").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("f_match_only").type());
        assertEquals(EsFieldType.STRING, context.getFieldInfo("f_flat_object").type());
        assertEquals(EsFieldType.BINARY, context.getFieldInfo("f_binary").type());
        assertEquals(EsFieldType.UNSUPPORTED, context.getFieldInfo("f_int_range").type());
        assertEquals(EsFieldType.UNSUPPORTED, context.getFieldInfo("f_geo_shape").type());
        assertEquals(EsFieldType.UNSUPPORTED, context.getFieldInfo("f_unknown").type());
    }

    // ---------------------------------------------------------------------------------------
    // parseSourceFilter edge cases: non-object _source node, scalar string includes/excludes
    // ---------------------------------------------------------------------------------------

    @Test
    void testSourceNodeNonObjectIgnored() throws Exception {
        // _source: false is valid ES syntax (disables _source storage) but for our purposes
        // we just ignore it since it's not an object with includes/excludes.
        String json = """
            {
                "_source": false,
                "properties": {
                    "title": {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertFalse(context.isSourceExcluded("title"), "_source:false does not set up filters");
    }

    @Test
    void testSourceScalarStringExclude() throws Exception {
        // _source.excludes can be a single string instead of an array
        String json = """
            {
                "_source": {"excludes": "secret"},
                "properties": {
                    "title":  {"type": "text"},
                    "secret": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("secret"));
        assertFalse(context.isSourceExcluded("title"));
    }

    // ---------------------------------------------------------------------------------------
    // compileGlob: literal glob matching children via dot-prefix
    // ---------------------------------------------------------------------------------------

    @Test
    void testLiteralIncludeMatchesChildren() throws Exception {
        // Include "user" (literal, no wildcard) should match "user" exactly AND
        // children "user.name", "user.email" via the dotPrefix logic.
        String json = """
            {
                "_source": {"includes": ["user"]},
                "properties": {
                    "user": {
                        "properties": {
                            "name":  {"type": "keyword"},
                            "email": {"type": "keyword"}
                        }
                    },
                    "other": {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertFalse(context.isSourceExcluded("user"), "literal match");
        assertFalse(context.isSourceExcluded("user.name"), "dotPrefix child match");
        assertFalse(context.isSourceExcluded("user.email"), "dotPrefix child match");
        assertTrue(context.isSourceExcluded("other"), "not matched by includes");
    }

    // ---------------------------------------------------------------------------------------
    // compileGlob: question mark pattern falls through to literal match
    // ---------------------------------------------------------------------------------------

    @Test
    void testQuestionMarkPatternLiteralFallback() throws Exception {
        // `?` patterns are not supported as wildcards -> literal match only
        String json = """
            {
                "_source": {"excludes": ["fie?d"]},
                "properties": {
                    "field":  {"type": "keyword"},
                    "fie?d":  {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("fie?d"), "literal match of the pattern itself");
        assertFalse(context.isSourceExcluded("field"), "? is NOT treated as a wildcard");
    }

    // ---------------------------------------------------------------------------------------
    // Container with properties present but not an object (edge case)
    // ---------------------------------------------------------------------------------------

    @Test
    void testPropertiesNotAnObject() throws Exception {
        // "properties" key exists but is not an object (e.g., an array) -> no fields parsed
        String json = """
            {
                "properties": ["not", "an", "object"]
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.getFieldNames().isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // isCopyToTarget: direct test for false case when copy_to edges exist but field is not a target
    // ---------------------------------------------------------------------------------------

    @Test
    void testIsCopyToTargetDirectly() throws Exception {
        String json = """
            {
                "properties": {
                    "title":    {"type": "text", "copy_to": "all_text"},
                    "all_text": {"type": "text"},
                    "body":     {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isCopyToTarget("all_text"));
        assertFalse(context.isCopyToTarget("title"), "source field is not a target");
        assertFalse(context.isCopyToTarget("body"), "unrelated field is not a target");
        assertFalse(context.isCopyToTarget("nonexistent"), "nonexistent field is not a target");
    }

    // ---------------------------------------------------------------------------------------
    // isSourceExcluded: when only copyToBySource has entries but no includes/excludes.
    // Tests the partial branch where sourcesByTarget is non-empty but compiled lists are empty.
    // ---------------------------------------------------------------------------------------

    @Test
    void testIsSourceExcludedWithOnlyCopyToNoFilters() throws Exception {
        // Only copy_to edges, no _source filter -> sourcesByTarget non-empty,
        // compiledIncludes/Excludes empty. Non-target fields should NOT be excluded.
        String json = """
            {
                "properties": {
                    "title":    {"type": "text", "copy_to": "all_text"},
                    "all_text": {"type": "text"},
                    "body":     {"type": "text"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        // Fast path NOT taken (sourcesByTarget is non-empty).
        // copy_to target is excluded
        assertTrue(context.isSourceExcluded("all_text"));
        // Non-target: not excluded via copy_to, not excluded via compiled filters
        // -> falls through matchesAny(compiledExcludes) which is empty -> false
        // -> compiledIncludes is empty -> return false
        assertFalse(context.isSourceExcluded("body"));
        assertFalse(context.isSourceExcluded("title"));
    }

    // ---------------------------------------------------------------------------------------
    // Interaction: includes-only with no matching path, and excludes-only with no matching path
    // Tests the matchesAny returning false for both directions.
    // ---------------------------------------------------------------------------------------

    @Test
    void testIncludesOnlyNonMatchingPath() throws Exception {
        // _source.includes set, no excludes. Path that doesn't match any include glob -> excluded.
        String json = """
            {
                "_source": {"includes": ["name"]},
                "properties": {
                    "name":  {"type": "keyword"},
                    "email": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertFalse(context.isSourceExcluded("name"), "matches include");
        assertTrue(context.isSourceExcluded("email"), "does not match any include -> excluded");
    }

    @Test
    void testExcludesOnlyNonMatchingPath() throws Exception {
        // _source.excludes set, no includes. Path that doesn't match any exclude glob -> NOT excluded.
        String json = """
            {
                "_source": {"excludes": ["secret"]},
                "properties": {
                    "title":  {"type": "text"},
                    "secret": {"type": "keyword"}
                }
            }
            """;
        FieldMappingContext context = new FieldMappingContext(MAPPER.readTree(json));

        assertTrue(context.isSourceExcluded("secret"), "matches exclude");
        assertFalse(context.isSourceExcluded("title"), "does not match exclude -> not excluded");
    }
}
