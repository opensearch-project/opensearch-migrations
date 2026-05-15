package org.opensearch.migrations.bulkload.lucene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses index mappings once and provides field type info for doc_value conversion.
 *
 * <p>Also captures the {@code copy_to} graph (source field -&gt; list of target fields). Target fields
 * are index-time-only in OpenSearch: they NEVER appear in the document's
 * {@code _source}. For sourceless reconstruction this means two things:
 *
 * <ol>
 *   <li>Target fields must be STRIPPED from the reconstructed _source even though they have
 *       doc_values / stored fields in the segment. See {@link #isCopyToTarget(String)}.</li>
 *   <li>When a source field's own stored/doc_values/points chain returns nothing, the value can
 *       be reverse-derived from one of its copy_to targets (ranked by lossiness — keyword-class
 *       before text). See {@link #getCopyToTargets(String)}.</li>
 * </ol>
 *
 * <p>Finally, the index-level {@code _source.includes} / {@code _source.excludes} filter is parsed
 * so callers can suppress paths the origin mapping never materialized into {@code _source}.
 * See {@link #isSourceExcluded(String)}, which combines the copy_to-target check with the glob
 * filter as a single decision point for the reconstructor.
 */
@Slf4j
public class FieldMappingContext {
    private static final String PROPERTIES = "properties";
    private static final String SOURCE = "_source";
    private static final String COPY_TO = "copy_to";
    private final Map<String, FieldMappingInfo> fieldMappings = new HashMap<>();
    /** source field -> ordered list of declared copy_to targets (raw order from mapping). */
    private final Map<String, List<String>> copyToBySource = new HashMap<>();
    /** target field -> set of sources that copy into it (inverse index). */
    private final Map<String, Set<String>> sourcesByTarget = new HashMap<>();
    // _source.includes / _source.excludes glob patterns. Pragmatic subset of ES source-filter
    // glob semantics sufficient for the common cases:
    //   "*"              -> matches every path
    //   "<prefix>.*"     -> matches any path starting with "<prefix>."
    //   "<prefix>.**"    -> same as "<prefix>.*" for our purposes
    //   "<literal.path>" -> exact match
    // Mid-string '*' / '?' fall back to literal match with a debug log so they do not silently
    // drop matching paths.
    private final List<String> sourceIncludes = new ArrayList<>();
    private final List<String> sourceExcludes = new ArrayList<>();
    private final List<Predicate<String>> compiledIncludes = new ArrayList<>();
    private final List<Predicate<String>> compiledExcludes = new ArrayList<>();

    public FieldMappingContext(JsonNode mappingsNode) {
        if (mappingsNode == null) {
            log.debug("Mappings node is null, no field mappings available");
            return;
        }

        log.debug("Parsing mappings node of type: {}", mappingsNode.getNodeType());
        JsonNode container = extractMappingContainer(mappingsNode);
        if (container == null || !container.isObject()) {
            log.debug("No mapping container found");
            return;
        }

        parseSourceFilter(container.path(SOURCE));

        JsonNode properties = container.path(PROPERTIES);
        if (!properties.isMissingNode() && properties.isObject()) {
            log.debug("Found {} top-level properties", properties.size());
            parseProperties(properties, "");
        } else {
            log.debug("No properties found in mappings");
        }

        log.debug("Parsed {} field mappings, {} copy_to edges, {} include/exclude rules",
            fieldMappings.size(), copyToBySource.size(),
            sourceIncludes.size() + sourceExcludes.size());
    }

    private JsonNode extractMappingContainer(JsonNode mappingsNode) {
        if (mappingsNode.isArray()) {
            return extractContainerFromArray(mappingsNode);
        }
        if (mappingsNode.isObject()) {
            return extractContainerFromObject(mappingsNode);
        }
        return null;
    }

    private JsonNode extractContainerFromArray(JsonNode mappingsNode) {
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
        return typeEntry.getValue();
    }

    private JsonNode extractContainerFromObject(JsonNode mappingsNode) {
        // Direct style (ES 7+): root has "properties" as a direct child.
        if (!mappingsNode.path(PROPERTIES).isMissingNode()) {
            return mappingsNode;
        }
        // Typed mappings (ES 6.x style: {"_doc": {"properties": {...}}})
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
        return typeEntry.getValue();
    }

    private void parseSourceFilter(JsonNode sourceNode) {
        if (sourceNode.isMissingNode() || !sourceNode.isObject()) {
            return;
        }
        collectStrings(sourceNode.path("includes"), sourceIncludes::add);
        collectStrings(sourceNode.path("excludes"), sourceExcludes::add);
        for (String glob : sourceIncludes) compiledIncludes.add(compileGlob(glob));
        for (String glob : sourceExcludes) compiledExcludes.add(compileGlob(glob));
    }

    private static Predicate<String> compileGlob(String glob) {
        if ("*".equals(glob)) return path -> true;
        if (glob.endsWith(".**")) {
            String prefix = glob.substring(0, glob.length() - 2);
            return path -> path.startsWith(prefix);
        }
        if (glob.endsWith(".*")) {
            String prefix = glob.substring(0, glob.length() - 1);
            return path -> path.startsWith(prefix);
        }
        if (glob.indexOf('*') < 0 && glob.indexOf('?') < 0) {
            String dotPrefix = glob + ".";
            return path -> path.equals(glob) || path.startsWith(dotPrefix);
        }
        int firstStar = glob.indexOf('*');
        if (firstStar >= 0 && glob.indexOf('*', firstStar + 1) < 0 && glob.indexOf('?') < 0) {
            String prefix = glob.substring(0, firstStar);
            String suffix = glob.substring(firstStar + 1);
            int minLen = prefix.length() + suffix.length();
            return path -> path.length() >= minLen && path.startsWith(prefix) && path.endsWith(suffix);
        }
        return path -> path.equals(glob);
    }

    private static void collectStrings(JsonNode node, Consumer<String> sink) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            sink.accept(node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> {
                if (n != null && n.isTextual()) {
                    sink.accept(n.asText());
                }
            });
        }
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
     * Returns just the source fields that have at least one {@code copy_to} edge declared.
     * Callers performing reverse-derivation should iterate this rather than the full mapping —
     * for indices with thousands of fields, that's an O(fields-with-copy_to) walk instead of
     * O(all-fields), and avoids running hasNested/shouldSkipField for every leaf.
     */
    public Set<String> getCopyToSourceFields() {
        return copyToBySource.keySet();
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

    /**
     * Returns {@code true} if {@code fieldPath} should be suppressed from the reconstructed
     * {@code _source}. The path is excluded when ANY of the following holds:
     *
     * <ul>
     *   <li>it is a {@code copy_to} target (ES never writes copy_to output back into
     *       {@code _source}); equivalent to {@link #isCopyToTarget(String)};</li>
     *   <li>it matches a {@code _source.excludes} glob;</li>
     *   <li>{@code _source.includes} is set and the path matches no include glob.</li>
     * </ul>
     *
     * <p>Excludes take precedence over includes (ES semantics). Cheap no-op when the mapping
     * declared neither {@code copy_to} nor a {@code _source} filter.
     *
     * <p>This is the single decision point the reconstructor's skip gate should call — it keeps
     * copy_to-target stripping and source-filter stripping behind one method so callers don't
     * need to know which rule fired.
     */
    public boolean isSourceExcluded(String fieldPath) {
        if (sourcesByTarget.isEmpty() && compiledIncludes.isEmpty() && compiledExcludes.isEmpty()) {
            return false;
        }
        // Copy_to targets never appear in original _source — always suppress from direct output.
        // Their value is recovered via reverse-derivation (pass 5) into the source field.
        if (isCopyToTarget(fieldPath)) {
            return true;
        }
        if (matchesAny(compiledExcludes, fieldPath)) {
            return true;
        }
        if (!compiledIncludes.isEmpty()) {
            return !matchesAny(compiledIncludes, fieldPath);
        }
        return false;
    }

    private static boolean matchesAny(List<Predicate<String>> predicates, String path) {
        for (Predicate<String> p : predicates) {
            if (p.test(path)) return true;
        }
        return false;
    }
}
