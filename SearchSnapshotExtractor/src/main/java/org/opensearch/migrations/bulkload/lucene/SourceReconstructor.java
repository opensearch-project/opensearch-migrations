package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.lucene.EsFieldType.MappingTypes;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Reconstructs document _source from doc_values and stored fields when _source is disabled or has excluded fields.
 * Implements fallback chain: _source blob -> doc_values -> stored fields
 */
@Slf4j
public class SourceReconstructor {
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final BigInteger UNSIGNED_LONG_MASK = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private SourceReconstructor() {}

    private static final AtomicBoolean NEST_COLLISION_WARNED = new AtomicBoolean();
    private static final AtomicBoolean DEEP_NEST_UNDER_LIST_WARNED = new AtomicBoolean();

    /**
     * Dotted-name disambiguation: {@code title.keyword} is a multi-field (indexed-only,
     * recoverable via the parent) while {@code address.city} is a legitimate object
     * subfield that must appear nested in {@code _source}. Object subfields appear under
     * {@code properties} (tracked) and multi-fields appear under {@code fields} (not).
     */
    private static boolean shouldSkipField(String fieldName, FieldMappingContext mappingContext) {
        if (fieldName.startsWith("_")) {
            return true;
        }
        if (mappingContext != null && mappingContext.isSourceExcluded(fieldName)) {
            return true;
        }
        if (!fieldName.contains(".")) {
            return false;
        }
        return mappingContext == null || mappingContext.getFieldInfo(fieldName) == null;
    }

    /** Unified skip-gate: already populated, or mapping says skip and not filling an object-array seed. */
    private static boolean shouldSkipForReconstruction(Map<String, Object> target, String fieldName,
            FieldMappingContext mappingContext) {
        if (hasNested(target, fieldName, mappingContext)) {
            return true;
        }
        return shouldSkipField(fieldName, mappingContext)
                && !descendsIntoExistingObjectArray(target, fieldName, mappingContext);
    }

    /**
     * Walks {@code root[parts[0]][parts[1]]...} up to and including {@code parts[targetIdx]},
     * traversing only Map ancestors. Returns {@code null} if any ancestor isn't a Map.
     */
    @SuppressWarnings("unchecked")
    private static Object walkMapAncestors(Map<String, Object> root, String[] parts, int targetIdx) {
        Map<String, Object> cursor = root;
        for (int i = 0; i < targetIdx; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                return null;
            }
            cursor = (Map<String, Object>) next;
        }
        return cursor.get(parts[targetIdx]);
    }

    /**
     * Carve-out for source-filter suppression: when an object-array (e.g. {@code files}) is in
     * scope, sibling subfields like {@code files.size} fill out the kept structural element
     * rather than resurrecting an excluded top-level field. Only single-leaf distributions are
     * recognised — {@link #distributeSubfieldAcrossList} does not nest deeper.
     */
    private static boolean descendsIntoExistingObjectArray(Map<String, Object> target, String fieldName,
            FieldMappingContext mappingContext) {
        if (fieldName.indexOf('.') < 0) {
            return false;
        }
        String[] parts = mappingContext != null ? mappingContext.getPathSegments(fieldName)
                                                : FieldMappingContext.splitPath(fieldName);
        Object parentValue = walkMapAncestors(target, parts, parts.length - 2);
        return parentValue instanceof java.util.List<?> list && isListOfMaps(list);
    }

    /**
     * Insert {@code value} at {@code fieldName}, creating intermediate maps for dotted paths.
     * First-write-wins: existing values at the destination are preserved.
     * <p>When an ancestor already holds a non-empty {@code List<Map>} (object-array seed from a
     * partial {@code _source}) and the suffix is a single leaf, distributes the value across
     * elements via {@link #distributeSubfieldAcrossList}. Deeper nesting under such a parent
     * cannot be reconstructed from columnar doc_values and is dropped with a one-shot warn.
     * <p>Returns {@code true} iff the map was modified at any depth — top-level
     * {@code target.size()} is not a sound dirty proxy because nested inserts don't change it.
     */
    @SuppressWarnings("unchecked")
    private static boolean putNested(Map<String, Object> target, String fieldName, Object value,
            FieldMappingContext mappingContext) {
        if (fieldName.indexOf('.') < 0) {
            if (target.containsKey(fieldName)) {
                return false;
            }
            target.put(fieldName, value);
            return true;
        }
        // Some ingest pipelines preserve dotted names verbatim — treat as already-present so
        // we don't emit both {"address.city":...} and {"address":{"city":...}}.
        if (target.containsKey(fieldName)) {
            return false;
        }
        String[] parts = mappingContext != null ? mappingContext.getPathSegments(fieldName)
                                                : FieldMappingContext.splitPath(fieldName);
        Map<String, Object> cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (next instanceof Map<?, ?> map) {
                cursor = (Map<String, Object>) map;
            } else if (next == null) {
                Map<String, Object> child = new LinkedHashMap<>();
                cursor.put(parts[i], child);
                cursor = child;
            } else if (next instanceof java.util.List<?> list && isListOfMaps(list)) {
                if (i != parts.length - 2) {
                    if (DEEP_NEST_UNDER_LIST_WARNED.compareAndSet(false, true)) {
                        log.atWarn()
                            .setMessage("Cannot distribute deeply-nested field '{}' under List<Map> at '{}'; "
                                + "only immediate-leaf subfields (parent.leaf) are supported. "
                                + "Dropping recovered value (further occurrences silenced)")
                            .addArgument(fieldName)
                            .addArgument(parts[i])
                            .log();
                    }
                    return false;
                }
                String leafForList = parts[parts.length - 1];
                return distributeSubfieldAcrossList((java.util.List<Object>) list, leafForList, value, fieldName);
            } else {
                if (NEST_COLLISION_WARNED.compareAndSet(false, true)) {
                    log.atWarn()
                        .setMessage("Cannot write nested field '{}' under scalar at '{}' (type {}); dropping recovered value (further occurrences silenced)")
                        .addArgument(fieldName)
                        .addArgument(parts[i])
                        .addArgument(next.getClass().getSimpleName())
                        .log();
                }
                return false;
            }
        }
        String leaf = parts[parts.length - 1];
        if (cursor.containsKey(leaf)) {
            return false;
        }
        cursor.put(leaf, value);
        return true;
    }

    /** Empty lists are ambiguous and are NOT treated as object arrays, preserving the
     *  drop-and-warn contract for scalar collisions. */
    private static boolean isListOfMaps(java.util.List<?> list) {
        if (list.isEmpty()) {
            return false;
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?>)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Distributes a recovered subfield across object-array elements. List values map positionally
     * (sizes must match); scalars broadcast. First-write-wins per element.
     * <p><b>Ordering caveat.</b> doc_values lists are in traversal order (SortedNumeric ascending
     * numeric, SortedSet ascending term), <em>not</em> original insertion order, so cross-subfield
     * tuples recovered this way are approximate. Stored-fields preserve insertion order.
     */
    @SuppressWarnings("unchecked")
    private static boolean distributeSubfieldAcrossList(java.util.List<Object> list, String leaf,
                                                        Object value, String fieldName) {
        if (value instanceof java.util.List<?> valueList) {
            if (valueList.size() != list.size()) {
                log.atWarn()
                    .setMessage("Cannot distribute '{}' across object array: got {} values for {} elements; dropping recovered value")
                    .addArgument(fieldName)
                    .addArgument(valueList.size())
                    .addArgument(list.size())
                    .log();
                return false;
            }
            log.atDebug()
                .setMessage("Distributing {} values for '{}' across {}-element object array")
                .addArgument(valueList.size())
                .addArgument(fieldName)
                .addArgument(list.size())
                .log();
            boolean modified = false;
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> element = (Map<String, Object>) list.get(i);
                if (element.containsKey(leaf)) {
                    continue;
                }
                element.put(leaf, valueList.get(i));
                modified = true;
            }
            return modified;
        }
        // Scalar broadcast: apply to every element that does not already have the subfield.
        boolean modified = false;
        for (Object elementObj : list) {
            Map<String, Object> element = (Map<String, Object>) elementObj;
            if (element.containsKey(leaf)) {
                continue;
            }
            element.put(leaf, value);
            modified = true;
        }
        return modified;
    }

    /**
     * True iff the (possibly-dotted) field path already has a value in {@code target}.
     * Walks intermediate maps for dotted names; non-dotted names fall back to {@code containsKey}.
     * Also returns {@code true} when the literal dotted key is present at the top level (some
     * ingest pipelines preserve dotted names verbatim).
     */
    @SuppressWarnings("unchecked")
    private static boolean hasNested(Map<String, Object> target, String fieldName,
            FieldMappingContext mappingContext) {
        if (target.containsKey(fieldName)) {
            return true;
        }
        if (fieldName.indexOf('.') < 0) {
            return false;
        }
        String[] parts = mappingContext != null ? mappingContext.getPathSegments(fieldName)
                                                : FieldMappingContext.splitPath(fieldName);
        Map<String, Object> cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (next instanceof Map<?, ?>) {
                cursor = (Map<String, Object>) next;
                continue;
            }
            // Array-of-objects ancestor (e.g. `files` is List<Map>): the leaf is considered present
            // iff the suffix is the immediate leaf AND every element already carries it. Keeps
            // mergeWithDocValues idempotent — a second pass over the same doc won't re-distribute.
            if (next instanceof java.util.List<?> list && i == parts.length - 2 && !list.isEmpty()) {
                String leafForList = parts[parts.length - 1];
                for (Object element : list) {
                    if (!(element instanceof Map<?, ?> m) || !m.containsKey(leafForList)) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
        return cursor.containsKey(parts[parts.length - 1]);
    }

    /**
     * Reconstructs _source JSON from stored fields and doc_values for a document.
     *
     * @param termIndex per-segment term position cache (Flux-scoped); may be null when the caller
     *                  doesn't need analyzed-text fallback (treated as empty).
     */
    public static String reconstructSource(LuceneLeafReader reader, int docId, LuceneDocument document,
            FieldMappingContext mappingContext, SegmentTermIndex termIndex) {
        try {
            Map<String, Object> reconstructed = new LinkedHashMap<>();
            populateFromSegment(reconstructed, reader, docId, document, mappingContext, termIndex);

            if (reconstructed.isEmpty()) {
                log.atWarn().setMessage("No stored fields or doc_values found for document {}").addArgument(docId).log();
                return null;
            }

            return OBJECT_MAPPER.writeValueAsString(reconstructed);
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to reconstruct source for document {}").addArgument(docId).log();
            return null;
        }
    }

    /** Backwards-compatible overload for callers that don't supply a term index (e.g. Solr path). */
    public static String reconstructSource(LuceneLeafReader reader, int docId, LuceneDocument document,
            FieldMappingContext mappingContext) {
        return reconstructSource(reader, docId, document, mappingContext, null);
    }

    /**
     * Merges reconstructed fields into an existing _source JSON, only filling fields the source
     * doesn't already have. Used for partial sources (e.g. {@code _source.includes/excludes}).
     */
    public static String mergeWithDocValues(String existingSource, LuceneLeafReader reader, int docId,
            LuceneDocument document, FieldMappingContext mappingContext) {
        return mergeWithDocValues(existingSource, reader, docId, document, mappingContext, null);
    }

    @SuppressWarnings("unchecked")
    public static String mergeWithDocValues(String existingSource, LuceneLeafReader reader, int docId,
            LuceneDocument document, FieldMappingContext mappingContext, SegmentTermIndex termIndex) {
        // Fast path: when the mapping itself declares no fields/copy_to/constants/source filter
        // AND the document has no stored fields, no recovery tier could contribute. Skip the
        // JSON round-trip entirely.
        if (mappingContext != null && !mappingContext.couldContributeToMerge()
                && (document == null || document.getFields().isEmpty())
                && reader.getDocValueFields().isEmpty()) {
            return existingSource;
        }
        try {
            Map<String, Object> existing = OBJECT_MAPPER.readValue(existingSource, Map.class);
            boolean modified = populateFromSegment(existing, reader, docId, document, mappingContext, termIndex);
            return modified ? OBJECT_MAPPER.writeValueAsString(existing) : existingSource;
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to merge fields for document {}").addArgument(docId).log();
            return existingSource;
        }
    }

    /**
     * Populates {@code target} (empty seed for fresh reconstruction, existing source for merge),
     * applying stored → doc_values → points/terms → constant → copy_to-reverse-derivation. Returns
     * {@code true} iff any value was written at any depth; callers use this to gate re-serialization.
     */
    private static boolean populateFromSegment(Map<String, Object> target, LuceneLeafReader reader, int docId,
            LuceneDocument document, FieldMappingContext mappingContext, SegmentTermIndex termIndex) throws IOException {
        var ctx = new RecoveryContext(target, reader, docId, document, mappingContext, termIndex);
        boolean modified = applyStoredFields(ctx);
        modified |= applyDocValues(ctx);
        modified |= applyPointsAndTerms(ctx);
        modified |= applyConstantValues(ctx);
        modified |= applyCopyToReverseDerivation(ctx);
        return modified;
    }

    /** Per-document recovery context: holds the target map plus name→field/info lookup tables built once per call. */
    private static final class RecoveryContext {
        final Map<String, Object> target;
        final LuceneLeafReader reader;
        final int docId;
        final LuceneDocument document;
        final FieldMappingContext mappingContext;
        final SegmentTermIndex termIndex;
        private Map<String, LuceneField> storedByName;
        private Map<String, DocValueFieldInfo> docValuesByName;

        RecoveryContext(Map<String, Object> target, LuceneLeafReader reader, int docId,
                LuceneDocument document, FieldMappingContext mappingContext, SegmentTermIndex termIndex) {
            this.target = target;
            this.reader = reader;
            this.docId = docId;
            this.document = document;
            this.mappingContext = mappingContext;
            this.termIndex = termIndex;
        }

        Map<String, LuceneField> storedByName() {
            if (storedByName == null) {
                Map<String, LuceneField> map = new LinkedHashMap<>();
                for (LuceneField f : document.getFields()) {
                    map.putIfAbsent(f.name(), f);
                }
                storedByName = map;
            }
            return storedByName;
        }

        Map<String, DocValueFieldInfo> docValuesByName() {
            if (docValuesByName == null) {
                List<DocValueFieldInfo> dvFields = reader.getDocValueFields();
                Map<String, DocValueFieldInfo> map = new LinkedHashMap<>(dvFields.size() * 2);
                for (DocValueFieldInfo fi : dvFields) {
                    map.putIfAbsent(fi.name(), fi);
                }
                docValuesByName = map;
            }
            return docValuesByName;
        }

        FieldMappingInfo mappingInfo(String fieldName) {
            return mappingContext != null ? mappingContext.getFieldInfo(fieldName) : null;
        }
    }

    private static boolean applyStoredFields(RecoveryContext ctx) {
        boolean modified = false;
        for (LuceneField field : ctx.document.getFields()) {
            String fieldName = field.name();
            if (shouldSkipForReconstruction(ctx.target, fieldName, ctx.mappingContext)) {
                continue;
            }
            Object value = getStoredFieldValue(field, ctx.mappingInfo(fieldName));
            if (value != null) {
                modified |= putNested(ctx.target, fieldName, value, ctx.mappingContext);
            }
        }
        return modified;
    }

    private static boolean applyDocValues(RecoveryContext ctx) throws IOException {
        boolean modified = false;
        for (DocValueFieldInfo fieldInfo : ctx.reader.getDocValueFields()) {
            String fieldName = fieldInfo.name();
            if (shouldSkipForReconstruction(ctx.target, fieldName, ctx.mappingContext)) {
                continue;
            }
            FieldMappingInfo mappingInfo = ctx.mappingInfo(fieldName);
            // Skip if mapping says doc_values is disabled (ES 2.x may still have them internally)
            if (mappingInfo != null && !mappingInfo.docValues()) {
                continue;
            }
            Object value = ctx.reader.getDocValue(ctx.docId, fieldInfo);
            if (value != null) {
                Object converted = convertDocValue(value, fieldInfo, mappingInfo);
                if (converted != null) {
                    modified |= putNested(ctx.target, fieldName, converted, ctx.mappingContext);
                }
            }
        }
        return modified;
    }

    private static boolean applyPointsAndTerms(RecoveryContext ctx) throws IOException {
        if (ctx.mappingContext == null) return false;
        boolean modified = false;
        for (String fieldName : ctx.mappingContext.getFieldNames()) {
            if (shouldSkipForReconstruction(ctx.target, fieldName, ctx.mappingContext)) {
                continue;
            }
            FieldMappingInfo mappingInfo = ctx.mappingContext.getFieldInfo(fieldName);
            if (mappingInfo == null) continue;
            var fallbackValue = ctx.reader.getValueFromPointsOrTerms(ctx.docId, fieldName,
                    mappingInfo.type(), ctx.termIndex);
            if (fallbackValue.isPresent()) {
                Object converted = convertFallbackValue(fallbackValue.get(), mappingInfo);
                if (converted != null) {
                    modified |= putNested(ctx.target, fieldName, converted, ctx.mappingContext);
                }
            }
        }
        return modified;
    }

    private static boolean applyConstantValues(RecoveryContext ctx) {
        if (ctx.mappingContext == null) return false;
        boolean modified = false;
        for (Map.Entry<String, FieldMappingInfo> entry : ctx.mappingContext.getConstantValueFields()) {
            String fieldName = entry.getKey();
            if (shouldSkipForReconstruction(ctx.target, fieldName, ctx.mappingContext)) {
                continue;
            }
            modified |= putNested(ctx.target, fieldName, entry.getValue().constantValue(), ctx.mappingContext);
        }
        return modified;
    }

    /**
     * Reverse-derive source fields from their copy_to targets. Targets are indexed-only
     * mirrors of the source value, so when the source's own recovery chain returned nothing
     * we can read any target. Targets are ranked by lossiness (less-lossy first).
     */
    private static boolean applyCopyToReverseDerivation(RecoveryContext ctx) throws IOException {
        if (ctx.mappingContext == null) return false;
        boolean modified = false;
        for (String sourceField : ctx.mappingContext.getCopyToSourceFields()) {
            if (hasNested(ctx.target, sourceField, ctx.mappingContext)
                    || shouldSkipField(sourceField, ctx.mappingContext)) {
                continue;
            }
            List<String> rankedTargets = ctx.mappingContext.getCopyToTargets(sourceField);
            if (rankedTargets.isEmpty()) continue;
            FieldMappingInfo sourceMapping = ctx.mappingContext.getFieldInfo(sourceField);
            for (String targetField : rankedTargets) {
                ProbeResult recovered = probeFieldValue(ctx, targetField);
                if (recovered == null) continue;
                // Raw values still need decoding through the SOURCE mapping so the JSON shape
                // matches the source's declared type; Final values were already target-shaped.
                Object converted = switch (recovered) {
                    case ProbeResult.Final f -> f.value();
                    case ProbeResult.Raw r -> sourceMapping != null
                            ? convertFallbackValue(r.raw(), sourceMapping)
                            : null;
                };
                if (converted != null) {
                    modified |= putNested(ctx.target, sourceField, converted, ctx.mappingContext);
                    break;
                }
            }
        }
        return modified;
    }

    /**
     * Replays stored → doc_values → points/terms → constant for a single field via O(1)
     * map lookups against {@link RecoveryContext}. Final values are already shaped through
     * the target's mapping; Raw values still need decoding through the source's mapping.
     */
    private static ProbeResult probeFieldValue(RecoveryContext ctx, String fieldName) throws IOException {
        FieldMappingInfo targetMapping = ctx.mappingInfo(fieldName);

        LuceneField stored = ctx.storedByName().get(fieldName);
        if (stored != null) {
            Object value = getStoredFieldValue(stored, targetMapping);
            if (value != null) {
                return new ProbeResult.Final(value);
            }
        }

        DocValueFieldInfo dvInfo = ctx.docValuesByName().get(fieldName);
        if (dvInfo != null && (targetMapping == null || targetMapping.docValues())) {
            Object value = ctx.reader.getDocValue(ctx.docId, dvInfo);
            if (value != null) {
                Object converted = convertDocValue(value, dvInfo, targetMapping);
                if (converted != null) {
                    return new ProbeResult.Final(converted);
                }
            }
        }

        // Use the target's type when known so STRING targets get the token-stream concatenation
        // path that delivers best-effort text recovery.
        if (targetMapping != null) {
            var fallback = ctx.reader.getValueFromPointsOrTerms(ctx.docId, fieldName,
                    targetMapping.type(), ctx.termIndex);
            if (fallback.isPresent()) {
                return new ProbeResult.Raw(fallback.get());
            }
        }

        if (targetMapping != null && targetMapping.constantValue() != null) {
            return new ProbeResult.Final(targetMapping.constantValue());
        }

        return null;
    }

    /** Extracts value from stored field, converting booleans stored as T/F and binary as base64 */
    private static Object getStoredFieldValue(LuceneField field, FieldMappingInfo mappingInfo) {
        Number num = field.numericValue();
        if (num != null) {
            if (mappingInfo != null) {
                // IP fields in ES 2.x store IPv4 as 32-bit integer
                if (mappingInfo.type() == EsFieldType.IP) {
                    long ipLong = num.longValue();
                    return String.format("%d.%d.%d.%d",
                        (ipLong >> 24) & 0xFF, (ipLong >> 16) & 0xFF,
                        (ipLong >> 8) & 0xFF, ipLong & 0xFF);
                }
                // DATE fields store epoch millis - convert to ISO format
                if (mappingInfo.type() == EsFieldType.DATE) {
                    return formatDate(num.longValue(), mappingInfo.format());
                }
                // DATE_NANOS fields store epoch nanos
                if (mappingInfo.type() == EsFieldType.DATE_NANOS) {
                    return formatDateNanos(num.longValue());
                }
                // SCALED_FLOAT needs to be divided by scaling factor
                if (mappingInfo.type() == EsFieldType.SCALED_FLOAT && mappingInfo.scalingFactor() != null) {
                    return num.longValue() / mappingInfo.scalingFactor();
                }
            }
            return num;
        }
        byte[] binaryData = field.binaryValue();
        if (binaryData != null && binaryData.length > 0) {
            // STRING fields (keyword/text) in ES 5+ store UTF-8 bytes - decode as string
            if (mappingInfo == null || mappingInfo.type() == EsFieldType.STRING) {
                return new String(binaryData, java.nio.charset.StandardCharsets.UTF_8);
            }
            // IP fields in ES 5+ store as 16-byte IPv6 format
            if (mappingInfo != null && mappingInfo.type() == EsFieldType.IP) {
                return convertIpBytes(binaryData);
            }
            // GEO_POINT stored as 8 bytes (2 x 4-byte floats for lat/lon) - skip, use doc_values
            if (mappingInfo != null && mappingInfo.type() == EsFieldType.GEO_POINT) {
                return null; // Let doc_values handle geo_point
            }
            // Actual binary fields should be base64 encoded
            return Base64.getEncoder().encodeToString(binaryData);
        }
        String value = field.stringValue();
        if (value == null) {
            value = field.utf8ToStringValue();
        }
        if (value == null) {
            return null;
        }
        // Lucene stores booleans as "T"/"F" in stored fields
        if ("T".equals(value)) return true;
        if ("F".equals(value)) return false;
        // DATE fields may store epoch millis as string
        if (mappingInfo != null && mappingInfo.type() == EsFieldType.DATE) {
            try {
                return formatDate(Long.parseLong(value), mappingInfo.format());
            } catch (NumberFormatException e) {
                return value; // Already formatted or custom format
            }
        }
        // SCALED_FLOAT stored as string needs scaling factor division
        if (mappingInfo != null && mappingInfo.type() == EsFieldType.SCALED_FLOAT && mappingInfo.scalingFactor() != null) {
            try {
                return Long.parseLong(value) / mappingInfo.scalingFactor();
            } catch (NumberFormatException e) {
                return value;
            }
        }
        // IP fields in ES 2.x may store IPv4 as numeric string
        if (mappingInfo != null && mappingInfo.type() == EsFieldType.IP) {
            try {
                long ipLong = Long.parseLong(value);
                return String.format("%d.%d.%d.%d",
                    (ipLong >> 24) & 0xFF, (ipLong >> 16) & 0xFF,
                    (ipLong >> 8) & 0xFF, ipLong & 0xFF);
            } catch (NumberFormatException e) {
                return value; // Already an IP string
            }
        }
        return value;
    }

    /** Converts doc_value using mapping info when available, falling back to heuristics.
     *  For multi-valued fields (SortedNumeric/SortedSet doc_values), the reader returns a List —
     *  convert each element through the same logic so per-type coercion (e.g. boolean Long→true/false)
     *  applies to array fields, not just scalars. */
    private static Object convertDocValue(Object value, DocValueFieldInfo fieldInfo, FieldMappingInfo mappingInfo) {
        if (value instanceof java.util.List<?> listVal) {
            java.util.List<Object> converted = new java.util.ArrayList<>(listVal.size());
            for (Object element : listVal) {
                converted.add(convertSingleDocValue(element, fieldInfo, mappingInfo));
            }
            return converted;
        }
        return convertSingleDocValue(value, fieldInfo, mappingInfo);
    }

    private static Object convertSingleDocValue(Object value, DocValueFieldInfo fieldInfo, FieldMappingInfo mappingInfo) {
        // Use mapping-based conversion if available
        if (mappingInfo != null && mappingInfo.type() != EsFieldType.UNSUPPORTED) {
            return convertWithMappingInfo(value, mappingInfo);
        }

        // Fall back to heuristic-based conversion
        if (fieldInfo.isBoolean() && value instanceof Long) {
            return ((Long) value) != 0;
        }
        return value;
    }

    private static Object convertWithMappingInfo(Object value, FieldMappingInfo mappingInfo) {
        return switch (mappingInfo.type()) {
            case BOOLEAN -> value instanceof Long ? ((Long) value) != 0 : value;
            case NUMERIC -> {
                // Float/double doc_values are stored as sortable int/long
                String mappingType = mappingInfo.mappingType();
                if (MappingTypes.HALF_FLOAT.equals(mappingType)) {
                    if (value instanceof Long longVal) {
                        // half_float uses 16-bit encoding - convert sortable short back to half float
                        yield sortableShortToHalfFloat(longVal.shortValue());
                    }
                }
                if (MappingTypes.FLOAT.equals(mappingType)) {
                    if (value instanceof Long longVal) {
                        yield Float.intBitsToFloat(longVal.intValue());
                    }
                }
                if (MappingTypes.DOUBLE.equals(mappingType)) {
                    if (value instanceof Long longVal) {
                        yield Double.longBitsToDouble(longVal);
                    }
                }
                yield value;
            }
            case SCALED_FLOAT -> {
                if (value instanceof Long && mappingInfo.scalingFactor() != null) {
                    yield ((Long) value) / mappingInfo.scalingFactor();
                }
                yield value;
            }
            case DATE -> {
                if (value instanceof Long) {
                    yield formatDate((Long) value, mappingInfo.format());
                }
                yield value;
            }
            case DATE_NANOS -> {
                if (value instanceof Long) {
                    yield formatDateNanos((Long) value);
                }
                yield value;
            }
            case UNSIGNED_LONG -> {
                if (value instanceof Long longVal && longVal < 0) {
                    yield BigInteger.valueOf(longVal).and(UNSIGNED_LONG_MASK);
                }
                yield value;
            }
            case IP -> {
                if (value instanceof byte[] bytes) {
                    yield convertIpBytes(bytes);
                }
                // ES 5+ doc_values may return IP as String (from SortedSetDocValues)
                if (value instanceof String strVal) {
                    // Try to decode as base64 (16-byte IPv6-mapped format = 24 chars base64)
                    if (strVal.length() <= 24) {
                        try {
                            byte[] bytes = Base64.getDecoder().decode(strVal);
                            if (bytes.length == 16) {
                                yield convertIpBytes(bytes);
                            }
                        } catch (IllegalArgumentException e) {
                            // Not valid base64, return as-is
                        }
                    }
                    yield strVal; // Already in IP format
                }
                // ES 2.x stores IPv4 as 32-bit integer in doc_values
                if (value instanceof Long ipLong) {
                    yield String.format("%d.%d.%d.%d",
                        (ipLong >> 24) & 0xFF,
                        (ipLong >> 16) & 0xFF,
                        (ipLong >> 8) & 0xFF,
                        ipLong & 0xFF);
                }
                yield value;
            }
            case GEO_POINT -> {
                if (value instanceof Long longVal) {
                    yield decodeGeoPoint(longVal);
                }
                // ES 1.x stores geo_point as binary doc_values (2 doubles: lat, lon)
                if (value instanceof String strVal) {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(strVal);
                        if (bytes.length == 16) {
                            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                            double lat = buf.getDouble();
                            double lon = buf.getDouble();
                            yield Map.of("lat", lat, "lon", lon);
                        }
                    } catch (IllegalArgumentException e) {
                        // Not valid base64
                    }
                }
                yield value;
            }
            case BINARY -> {
                if (value instanceof byte[] bytes) {
                    yield Base64.getEncoder().encodeToString(bytes);
                }
                yield value;
            }
            case STRING -> {
                // Wildcard type stores values as binary doc_values (base64 encoded)
                String mappingType = mappingInfo.mappingType();
                if (MappingTypes.WILDCARD.equals(mappingType) && value instanceof String strVal) {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(strVal);
                        yield new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        // Not valid base64, return as-is
                    }
                }
                yield value;
            }
            default -> value;
        };
    }

    private static Object formatDate(long epochMillis, String format) {
        if ("epoch_millis".equals(format)) {
            return epochMillis;
        }
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static Object formatDateNanos(long epochNanos) {
        long epochSecond = epochNanos / 1_000_000_000L;
        int nanoAdjustment = (int) (epochNanos % 1_000_000_000L);
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epochSecond, nanoAdjustment));
    }

    private static String convertIpBytes(byte[] bytes) {
        try {
            if (bytes.length == 16) {
                // Check for IPv4-mapped IPv6
                boolean isIpv4Mapped = true;
                for (int i = 0; i < 10; i++) {
                    if (bytes[i] != 0) { isIpv4Mapped = false; break; }
                }
                if (isIpv4Mapped && bytes[10] == (byte)0xff && bytes[11] == (byte)0xff) {
                    return String.format("%d.%d.%d.%d",
                        bytes[12] & 0xFF, bytes[13] & 0xFF,
                        bytes[14] & 0xFF, bytes[15] & 0xFF);
                }
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception e) {
            return Base64.getEncoder().encodeToString(bytes);
        }
    }

    // Constants for Morton decoding (ES 2.x geo_point format)
    private static final long[] MAGIC = {
        0x5555555555555555L, 0x3333333333333333L, 0x0F0F0F0F0F0F0F0FL,
        0x00FF00FF00FF00FFL, 0x0000FFFF0000FFFFL, 0x00000000FFFFFFFFL
    };
    private static final int[] SHIFT = { 1, 2, 4, 8, 16 };
    private static final double LAT_SCALE = (0x1L << 31) / 180.0D;
    private static final double LON_SCALE = (0x1L << 31) / 360.0D;

    private static Map<String, Double> decodeGeoPoint(long encoded) {
        // ES 2.x uses Morton encoding (geohash format) for geo_point doc_values
        double lon = decodeLongitude(encoded);
        double lat = decodeLatitude(encoded);
        return Map.of("lat", lat, "lon", lon);
    }

    private static double decodeLatitude(long hash) {
        return (deinterleave(hash >>> 1) / LAT_SCALE) - 90;
    }

    private static double decodeLongitude(long hash) {
        return (deinterleave(hash) / LON_SCALE) - 180;
    }

    private static long deinterleave(long b) {
        b &= MAGIC[0];
        b = (b ^ (b >>> SHIFT[0])) & MAGIC[1];
        b = (b ^ (b >>> SHIFT[1])) & MAGIC[2];
        b = (b ^ (b >>> SHIFT[2])) & MAGIC[3];
        b = (b ^ (b >>> SHIFT[3])) & MAGIC[4];
        b = (b ^ (b >>> SHIFT[4])) & MAGIC[5];
        return b;
    }

    /**
     * Routes a {@link RecoveredValue} from the points/terms recovery tier through the per-mapping
     * decoder. The sealed dispatch replaces an earlier {@code instanceof}-on-{@code Object} branch
     * whose unchecked {@code (List<byte[]>)} cast crashed when copy_to reverse-derivation handed
     * in a {@code List<String>} from SORTED_SET doc_values.
     */
    private static Object convertFallbackValue(RecoveredValue value, FieldMappingInfo mappingInfo) {
        return switch (value) {
            case RecoveredValue.TextTerm t -> mappingInfo.type() == EsFieldType.BOOLEAN
                    ? "T".equals(t.text())
                    : t.text();
            case RecoveredValue.PointBytes p -> decodePointValue(p.packed(), mappingInfo);
            case RecoveredValue.NumericTerm n -> decodeNumericTerm(n.encoded(), mappingInfo);
        };
    }

    /** Decode a raw Long harvested from a shift==0 numeric term into the mapped JSON type. */
    private static Object decodeNumericTerm(long raw, FieldMappingInfo mappingInfo) {
        return switch (mappingInfo.type()) {
            case BOOLEAN -> raw != 0;
            case IP -> String.format("%d.%d.%d.%d",
                (raw >> 24) & 0xFF,
                (raw >> 16) & 0xFF,
                (raw >> 8) & 0xFF,
                raw & 0xFF);
            case DATE -> formatDate(raw, mappingInfo.format());
            case DATE_NANOS -> formatDateNanos(raw);
            case SCALED_FLOAT -> mappingInfo.scalingFactor() != null
                ? raw / mappingInfo.scalingFactor()
                : raw;
            case UNSIGNED_LONG -> raw < 0 ? BigInteger.valueOf(raw).and(UNSIGNED_LONG_MASK) : raw;
            case NUMERIC -> {
                // Lucene 4/5 stores floats/doubles as sortable int/long bits in trie terms.
                // sortableLongToDouble(l) = Double.longBitsToDouble(l ^ ((l >> 63) & 0x7FFFFFFFFFFFFFFFL))
                // sortableIntToFloat(i)   = Float.intBitsToFloat(i ^ ((i >> 31) & 0x7FFFFFFF))
                String mappingType = mappingInfo.mappingType();
                if (MappingTypes.DOUBLE.equals(mappingType)) {
                    long bits = raw ^ ((raw >> 63) & 0x7FFFFFFFFFFFFFFFL);
                    yield Double.longBitsToDouble(bits);
                }
                if (MappingTypes.FLOAT.equals(mappingType) || MappingTypes.HALF_FLOAT.equals(mappingType)) {
                    int iraw = (int) raw;
                    int bits = iraw ^ ((iraw >> 31) & 0x7FFFFFFF);
                    yield Float.intBitsToFloat(bits);
                }
                if (MappingTypes.INTEGER.equals(mappingType)
                        || MappingTypes.SHORT.equals(mappingType)
                        || MappingTypes.BYTE.equals(mappingType)) {
                    yield (int) raw;
                }
                yield raw; // long, token_count, etc.
            }
            default -> raw;
        };
    }

    /** Decode point value from packed bytes */
    private static Object decodePointValue(java.util.List<byte[]> pointValues, FieldMappingInfo mappingInfo) {
        if (pointValues.isEmpty()) return null;
        byte[] packed = pointValues.get(0);
        
        return switch (mappingInfo.type()) {
            case BOOLEAN -> {
                // Booleans aren't typically stored as Points, but handle defensively.
                // Historical encodings: legacy ES (pre-6.0) stored boolean as a single byte
                // "T"=0x54 / "F"=0x46 (SortedDocValues BytesRef); modern ES stores as
                // SortedNumericDocValues 0/1. For Points-path completeness, accept either:
                if (packed.length == 1) {
                    yield packed[0] == (byte) 'T' || packed[0] == 1;
                }
                yield null;
            }
            case IP -> packed.length == 16 ? convertIpBytes(packed) : null;
            case NUMERIC -> {
                String mappingType = mappingInfo.mappingType();
                if (MappingTypes.HALF_FLOAT.equals(mappingType) && packed.length == 2) {
                    // half_float Points: read big-endian short, flip sign bit, then decode
                    short s = (short) (((packed[0] & 0xFF) << 8) | (packed[1] & 0xFF));
                    s ^= 0x8000; // flip sign bit (sortableBytesToShort)
                    yield sortableShortToHalfFloat(s);
                }
                if (MappingTypes.FLOAT.equals(mappingType) && packed.length == 4) {
                    int sortable = decodeIntPoint(packed);
                    // Undo Lucene's sortableInt→float transform for negative values
                    int bits = sortable ^ ((sortable >> 31) & 0x7FFFFFFF);
                    yield Float.intBitsToFloat(bits);
                }
                if (MappingTypes.DOUBLE.equals(mappingType) && packed.length == 8) {
                    long sortable = decodeLongPoint(packed);
                    // Undo Lucene's sortableLong→double transform for negative values
                    long bits = sortable ^ ((sortable >> 63) & 0x7FFFFFFFFFFFFFFFL);
                    yield Double.longBitsToDouble(bits);
                }
                if (packed.length == 8) yield decodeLongPoint(packed);
                if (packed.length == 4) yield decodeIntPoint(packed);
                yield null;
            }
            case DATE -> packed.length == 8 ? formatDate(decodeLongPoint(packed), mappingInfo.format()) : null;
            case DATE_NANOS -> packed.length == 8 ? formatDateNanos(decodeLongPoint(packed)) : null;
            default -> null;
        };
    }

    /** Decode 8-byte packed long point value (Lucene sortable format - sign bit flipped) */
    private static long decodeLongPoint(byte[] packed) {
        long raw = ((long)(packed[0] & 0xFF) << 56) |
               ((long)(packed[1] & 0xFF) << 48) |
               ((long)(packed[2] & 0xFF) << 40) |
               ((long)(packed[3] & 0xFF) << 32) |
               ((long)(packed[4] & 0xFF) << 24) |
               ((long)(packed[5] & 0xFF) << 16) |
               ((long)(packed[6] & 0xFF) << 8) |
               ((long)(packed[7] & 0xFF));
        // Flip sign bit back (Lucene sortable encoding)
        return raw ^ 0x8000000000000000L;
    }

    /** Decode 4-byte packed int point value (Lucene sortable format - sign bit flipped) */
    private static int decodeIntPoint(byte[] packed) {
        int raw = ((packed[0] & 0xFF) << 24) |
               ((packed[1] & 0xFF) << 16) |
               ((packed[2] & 0xFF) << 8) |
               (packed[3] & 0xFF);
        // Flip sign bit back (Lucene sortable encoding)
        return raw ^ 0x80000000;
    }

    /** Convert sortable short to half-precision float (IEEE 754 binary16) */
    private static float sortableShortToHalfFloat(short sortableShort) {
        // Reverse the sortable encoding: s ^ (s >> 15) & 0x7fff
        short bits = (short) (sortableShort ^ (sortableShort >> 15) & 0x7fff);
        // Convert half-float bits to float using shortBitsToHalfFloat logic
        int sign = bits >>> 15;
        int exp = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x3ff;
        
        if (exp == 0x1f) {
            // NaN or infinities
            exp = 0xff;
            mantissa <<= (23 - 10);
        } else if (mantissa == 0 && exp == 0) {
            // zero - just return with sign
        } else {
            if (exp == 0) {
                // denormal half float becomes a normal float
                int shift = Integer.numberOfLeadingZeros(mantissa) - (32 - 11);
                mantissa = (mantissa << shift) & 0x3ff;
                exp = exp - shift + 1;
            }
            exp = exp + 127 - 15;
            mantissa <<= (23 - 10);
        }
        return Float.intBitsToFloat((sign << 31) | (exp << 23) | mantissa);
    }
}
