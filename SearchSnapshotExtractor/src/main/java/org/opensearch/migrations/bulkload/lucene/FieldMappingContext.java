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
    /** source field -> declared copy_to targets, pre-sorted by ascending lossiness. */
    private final Map<String, List<String>> copyToBySource = new HashMap<>();
    /** target field -> set of sources that copy into it (inverse index, mutable during parse). */
    private final Map<String, Set<String>> sourcesByTarget = new HashMap<>();
    /** target field -> immutable list view of sources, computed once at construction. */
    private Map<String, List<String>> sourcesByTargetList = Collections.emptyMap();
    /** Pre-filtered (fieldName, mappingInfo) entries with non-null constantValue, computed once. */
    private final List<Map.Entry<String, FieldMappingInfo>> constantValueFields = new ArrayList<>();
    /** Cached dotted-path segments for every mapped field. Populated alongside fieldMappings. */
    private final Map<String, String[]> pathSegmentsCache = new HashMap<>();
    /** Compiled {@code _source.includes}/{@code _source.excludes} predicates — see {@link #compileGlob}. */
    private final List<Predicate<String>> sourceIncludes = new ArrayList<>();
    private final List<Predicate<String>> sourceExcludes = new ArrayList<>();

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

        // Sort copy_to targets once at construction (lossinessRank is mapping-only, doc-independent).
        copyToBySource.replaceAll((source, targets) -> {
            if (targets.size() <= 1) {
                return targets;
            }
            List<String> sorted = new ArrayList<>(targets);
            sorted.sort((a, b) -> Integer.compare(lossinessRank(a), lossinessRank(b)));
            return sorted;
        });
        // Pre-filter constant-value fields (constant_keyword) — pass 4 of source reconstruction
        // walks this list directly instead of every mapped field.
        for (Map.Entry<String, FieldMappingInfo> entry : fieldMappings.entrySet()) {
            if (entry.getValue().constantValue() != null) {
                constantValueFields.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        // Freeze sourcesByTarget as immutable List views so accessors don't allocate per call.
        if (!sourcesByTarget.isEmpty()) {
            Map<String, List<String>> frozen = new HashMap<>(sourcesByTarget.size() * 2);
            for (Map.Entry<String, Set<String>> entry : sourcesByTarget.entrySet()) {
                frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            sourcesByTargetList = Collections.unmodifiableMap(frozen);
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
        collectStrings(sourceNode.path("includes"), g -> sourceIncludes.add(compileGlob(g)));
        collectStrings(sourceNode.path("excludes"), g -> sourceExcludes.add(compileGlob(g)));
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
        if (fullPath.indexOf('.') >= 0) {
            pathSegmentsCache.put(fullPath, splitPath(fullPath));
        }
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

    public Set<String> getFieldNames() {
        return fieldMappings.keySet();
    }

    /** copy_to targets are indexed-only and never appear in ES/OS {@code _source}, so the
     *  reconstructor uses this to strip them from reconstructed output. */
    public boolean isCopyToTarget(String fieldName) {
        return sourcesByTarget.containsKey(fieldName);
    }

    /** Sources that copy into {@code targetFieldName}, immutable view computed at construction. */
    public List<String> getCopyToSources(String targetFieldName) {
        return sourcesByTargetList.getOrDefault(targetFieldName, Collections.emptyList());
    }

    /** Source fields with at least one {@code copy_to} edge — reverse-derivation iterates this
     *  rather than the full mapping. */
    public Set<String> getCopyToSourceFields() {
        return copyToBySource.keySet();
    }

    /** Fast pre-check for partial-source merge: returns false when no recovery tier could
     *  possibly contribute, so callers can skip the JSON round-trip. */
    public boolean couldContributeToMerge() {
        return !fieldMappings.isEmpty()
                || !copyToBySource.isEmpty()
                || !constantValueFields.isEmpty()
                || !sourceIncludes.isEmpty()
                || !sourceExcludes.isEmpty();
    }

    /** Pre-sorted copy_to targets for {@code sourceFieldName} (ascending lossiness, doc_values
     *  preferred within a tier, declaration order preserved within ties). O(1) per call. */
    public List<String> getCopyToTargets(String sourceFieldName) {
        List<String> sorted = copyToBySource.get(sourceFieldName);
        return sorted != null ? sorted : Collections.emptyList();
    }

    /** Pre-filtered entries for fields declaring a {@code constant_value}, computed once. */
    public List<Map.Entry<String, FieldMappingInfo>> getConstantValueFields() {
        return constantValueFields;
    }

    /**
     * Lower = less lossy (prefer this target for reverse-derivation). Result is
     * {@code tier * 10 + (docValues ? 0 : 1)}, so within a tier doc-valued targets rank ahead.
     *
     *   0 = constant_keyword          (mapping-level value, zero IO)
     *   1 = keyword-class             (keyword, wildcard, version, ip, boolean, numerics, dates — exact)
     *   2 = match_only_text           (text-ish but preserves terms for positional recovery)
     *   3 = text                      (analyzed; original value lost, only tokenized recovery possible)
     *  10 = unknown / no mapping info (last resort)
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
                case EsFieldType.MappingTypes.CONSTANT_KEYWORD:
                    tier = 0; break;
                case EsFieldType.MappingTypes.TEXT:
                    tier = 3; break;
                case EsFieldType.MappingTypes.MATCH_ONLY_TEXT:
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
     * Single decision point combining copy_to-target stripping and {@code _source.includes/excludes}
     * filtering. Excludes take precedence over includes (ES semantics). Cheap no-op when the
     * mapping declared none of these.
     */
    public boolean isSourceExcluded(String fieldPath) {
        if (sourcesByTarget.isEmpty() && sourceIncludes.isEmpty() && sourceExcludes.isEmpty()) {
            return false;
        }
        if (isCopyToTarget(fieldPath)) {
            return true;
        }
        for (Predicate<String> excl : sourceExcludes) {
            if (excl.test(fieldPath)) {
                return true;
            }
        }
        if (!sourceIncludes.isEmpty()) {
            for (Predicate<String> incl : sourceIncludes) {
                if (incl.test(fieldPath)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Cached at construction for mapped fields; manual split otherwise (no {@code Pattern}). */
    public String[] getPathSegments(String fieldName) {
        String[] cached = pathSegmentsCache.get(fieldName);
        return cached != null ? cached : splitPath(fieldName);
    }

    /** Equivalent to {@code fieldName.split("\\.")} but without the {@code Pattern} overhead. */
    public static String[] splitPath(String fieldName) {
        int len = fieldName.length();
        int count = 1;
        for (int i = 0; i < len; i++) {
            if (fieldName.charAt(i) == '.') count++;
        }
        if (count == 1) {
            return new String[] { fieldName };
        }
        String[] parts = new String[count];
        int p = 0;
        int start = 0;
        for (int i = 0; i < len; i++) {
            if (fieldName.charAt(i) == '.') {
                parts[p++] = fieldName.substring(start, i);
                start = i + 1;
            }
        }
        parts[p] = fieldName.substring(start);
        return parts;
    }

    /**
     * ES-compatible subset: {@code *}, {@code prefix.**} / {@code prefix.*} (path-prefix),
     * bare-{@code *} patterns ({@code prefix*}, {@code *suffix}, {@code prefix*suffix}).
     * Multi-{@code *} or {@code ?} patterns fall back to literal match with a debug log.
     *
     * <p>Literal patterns match the path itself AND any descendant path. This mirrors ES's
     * recursive-descent filtering: {@code _source.excludes:["meta"]} drops the {@code meta}
     * object as a whole, which transitively drops {@code meta.created_at},
     * {@code meta.updated_by}, etc. Treating the literal as equals-only would leak descendants.
     */
    private static Predicate<String> compileGlob(String glob) {
        if ("*".equals(glob)) {
            return path -> true;
        }
        if (glob.endsWith(".**")) {
            String pathPrefix = glob.substring(0, glob.length() - 2);
            return path -> path.startsWith(pathPrefix);
        }
        if (glob.endsWith(".*")) {
            String pathPrefix = glob.substring(0, glob.length() - 1);
            return path -> path.startsWith(pathPrefix);
        }
        int firstStar = glob.indexOf('*');
        if (firstStar >= 0 && glob.indexOf('*', firstStar + 1) < 0 && glob.indexOf('?') < 0) {
            String prefix = glob.substring(0, firstStar);
            String suffix = glob.substring(firstStar + 1);
            int minLen = prefix.length() + suffix.length();
            return path -> path.length() >= minLen && path.startsWith(prefix) && path.endsWith(suffix);
        }
        if (glob.indexOf('*') >= 0 || glob.indexOf('?') >= 0) {
            log.debug("Unsupported source-filter glob '{}', treating as literal", glob);
        }
        String descendantPrefix = glob + ".";
        return path -> path.equals(glob) || path.startsWith(descendantPrefix);
    }
}
