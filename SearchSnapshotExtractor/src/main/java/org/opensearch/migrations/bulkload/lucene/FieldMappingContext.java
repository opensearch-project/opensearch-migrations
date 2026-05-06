package org.opensearch.migrations.bulkload.lucene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses index mappings once and provides field type info for doc_value conversion.
 *
 * Also captures the {@code copy_to} graph (source field -> list of target fields). Target fields
 * are index-time-only in OpenSearch: they NEVER appear in the document's
 * {@code _source}. For sourceless reconstruction this means two things:
 *
 *   (1) Target fields must be STRIPPED from the reconstructed _source even though they have
 *       doc_values / stored fields in the segment. See {@link #isCopyToTarget(String)}.
 *   (2) When a source field's own stored/doc_values/points chain returns nothing, the value can
 *       be reverse-derived from one of its copy_to targets (ranked by lossiness — keyword-class
 *       before text). See {@link #getCopyToTargets(String)}.
 */
@Slf4j
public class FieldMappingContext {
    private static final String PROPERTIES = "properties";
    private static final String COPY_TO = "copy_to";
    private final Map<String, FieldMappingInfo> fieldMappings = new HashMap<>();
    /** source field -> ordered list of declared copy_to targets (raw order from mapping). */
    private final Map<String, List<String>> copyToBySource = new HashMap<>();
    /** target field -> set of sources that copy into it (inverse index). */
    private final Map<String, Set<String>> sourcesByTarget = new HashMap<>();

    public FieldMappingContext(JsonNode mappingsNode) {
        if (mappingsNode == null) {
            log.debug("Mappings node is null, no field mappings available");
            return;
        }
        
        log.debug("Parsing mappings node of type: {}", mappingsNode.getNodeType());
        JsonNode properties = extractProperties(mappingsNode);
        
        if (properties != null && !properties.isMissingNode()) {
            log.debug("Found {} top-level properties", properties.size());
            parseProperties(properties, "");
        } else {
            log.debug("No properties found in mappings");
        }
        
        log.debug("Parsed {} field mappings total", fieldMappings.size());
    }

    private JsonNode extractProperties(JsonNode mappingsNode) {
        if (mappingsNode.isArray()) {
            return extractPropertiesFromArray(mappingsNode);
        }
        if (mappingsNode.isObject()) {
            return extractPropertiesFromObject(mappingsNode);
        }
        return null;
    }

    private JsonNode extractPropertiesFromArray(JsonNode mappingsNode) {
        // Multi-type mappings (ES 1.x-5.x) - take first type
        if (mappingsNode.size() == 0) {
            return null;
        }
        JsonNode firstType = mappingsNode.get(0);
        if (!firstType.isObject()) {
            return null;
        }
        var fields = firstType.fields();
        if (!fields.hasNext()) {
            return null;
        }
        var typeEntry = fields.next();
        log.debug("Using first mapping type: {}", typeEntry.getKey());
        return typeEntry.getValue().path(PROPERTIES);
    }

    private JsonNode extractPropertiesFromObject(JsonNode mappingsNode) {
        // Check for direct properties
        JsonNode properties = mappingsNode.path(PROPERTIES);
        if (!properties.isMissingNode()) {
            return properties;
        }
        // Check for typed mappings (ES 6.x style: {"_doc": {"properties": {...}}})
        var fields = mappingsNode.fields();
        if (!fields.hasNext()) {
            return null;
        }
        var typeEntry = fields.next();
        String typeName = typeEntry.getKey();
        if (PROPERTIES.equals(typeName)) {
            return null;
        }
        log.debug("Using mapping type: {}", typeName);
        return typeEntry.getValue().path(PROPERTIES);
    }

    private void parseProperties(JsonNode properties, String prefix) {
        properties.fieldNames().forEachRemaining(fieldName -> {
            JsonNode fieldDef = properties.get(fieldName);
            String fullPath = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;

            recordFieldType(fullPath, fieldDef);
            recordCopyToEdges(fullPath, fieldDef);

            // Handle nested properties
            JsonNode nestedProps = fieldDef.path(PROPERTIES);
            if (!nestedProps.isMissingNode()) {
                parseProperties(nestedProps, fullPath);
            }
        });
    }

    private void recordFieldType(String fullPath, JsonNode fieldDef) {
        if (!fieldDef.has("type")) {
            return;
        }
        String type = fieldDef.get("type").asText();
        FieldMappingInfo info = FieldMappingInfo.from(fieldDef);
        fieldMappings.put(fullPath, info);
        log.debug("Field '{}' -> type={}, esType={}", fullPath, type, info.type());
    }

    // Record copy_to edges (accepted on any field, independent of having a `type` key —
    // historically copy_to was allowed on objects too, though rare).
    private void recordCopyToEdges(String fullPath, JsonNode fieldDef) {
        JsonNode copyToNode = fieldDef.get(COPY_TO);
        if (copyToNode == null || copyToNode.isMissingNode() || copyToNode.isNull()) {
            return;
        }
        List<String> targets = extractCopyToTargets(copyToNode);
        if (targets.isEmpty()) {
            return;
        }
        copyToBySource.put(fullPath, targets);
        for (String t : targets) {
            sourcesByTarget.computeIfAbsent(t, k -> new HashSet<>()).add(fullPath);
        }
        log.debug("Field '{}' copy_to -> {}", fullPath, targets);
    }

    private static List<String> extractCopyToTargets(JsonNode copyToNode) {
        List<String> targets = new ArrayList<>();
        if (copyToNode.isTextual()) {
            targets.add(copyToNode.asText());
        } else if (copyToNode.isArray()) {
            copyToNode.forEach(t -> {
                if (t != null && t.isTextual()) {
                    targets.add(t.asText());
                }
            });
        }
        return targets;
    }

    public FieldMappingInfo getFieldInfo(String fieldName) {
        return fieldMappings.get(fieldName);
    }

    /**
     * Returns all field names in the mapping.
     */
    public java.util.Set<String> getFieldNames() {
        return fieldMappings.keySet();
    }

    /**
     * True iff {@code fieldName} is declared as a copy_to target by at least one source field.
     * Callers (the reconstructor) use this to STRIP target fields from reconstructed output since
     * copy_to targets are indexed-only and never appear in ES/OS {@code _source}.
     */
    public boolean isCopyToTarget(String fieldName) {
        return sourcesByTarget.containsKey(fieldName);
    }

    /**
     * Returns the source fields that copy into {@code targetFieldName}, or empty list if none.
     */
    public List<String> getCopyToSources(String targetFieldName) {
        Set<String> sources = sourcesByTarget.get(targetFieldName);
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(sources);
    }

    /**
     * Returns the copy_to targets for {@code sourceFieldName}, ordered by ascending lossiness —
     * less-lossy (keyword-class) targets first, text targets last. Used during reverse-derivation
     * when the source's own stored/doc_values/points chain returned nothing.
     *
     * Tie-break between equal-tier targets favors {@code doc_values:true} before false (faster
     * recovery, less IO). Input declaration order is preserved within equivalent cost buckets.
     */
    public List<String> getCopyToTargets(String sourceFieldName) {
        List<String> raw = copyToBySource.get(sourceFieldName);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        // Stable sort: Collections.sort is stable, so equal-rank targets keep declaration order.
        List<String> sorted = new ArrayList<>(raw);
        sorted.sort((a, b) -> Integer.compare(lossinessRank(a), lossinessRank(b)));
        return sorted;
    }

    /**
     * Lower = less lossy (prefer this target for reverse-derivation).
     *
     *   0 = constant_keyword          (mapping-level value, zero IO)
     *   1 = keyword-class             (keyword, wildcard, version, ip, boolean, numerics, dates — exact)
     *   2 = match_only_text           (text-ish but preserves terms for positional recovery)
     *   3 = text                      (analyzed; original value lost, only tokenized recovery possible)
     *  10 = unknown / no mapping info (last resort)
     *
     * Tie-break: doc_values=true subtracts a fractional rank so the comparator still ranks
     * doc-valued targets slightly ahead. We keep it as integer math by combining tier*10 + hasDV flag.
     */
    private int lossinessRank(String targetFieldName) {
        FieldMappingInfo info = fieldMappings.get(targetFieldName);
        if (info == null) {
            return 100; // unknown: last resort
        }
        String mappingType = info.mappingType();
        int tier;
        if (mappingType == null) {
            tier = 10;
        } else {
            switch (mappingType) {
                case "constant_keyword":
                    tier = 0; break;
                case "text":
                    tier = 3; break;
                case "match_only_text":
                    tier = 2; break;
                default:
                    // keyword, wildcard, version, ip, boolean, numeric types, date, date_nanos, etc.
                    tier = 1; break;
            }
        }
        int dvPenalty = info.docValues() ? 0 : 1;
        return tier * 10 + dvPenalty;
    }
}
