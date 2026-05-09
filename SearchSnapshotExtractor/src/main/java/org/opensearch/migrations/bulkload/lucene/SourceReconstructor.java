package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;

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

    /**
     * Skip internal fields (e.g., _id) and multi-field sub-fields (e.g., title.keyword).
     * <p>
     * Dotted names are ambiguous in Lucene: {@code title.keyword} is a multi-field
     * (indexed-only, recoverable via the parent), while {@code address.city} is an
     * object subfield that IS a legitimate mapped field and must be emitted nested
     * in the reconstructed {@code _source}. The mapping context distinguishes them —
     * object subfields appear under {@code properties} (tracked by FieldMappingContext),
     * multi-field sub-fields appear under {@code fields} (not tracked). So we keep any
     * dotted name the mapping knows about, and skip the rest.
     */
    private static boolean shouldSkipField(String fieldName, FieldMappingContext mappingContext) {
        if (fieldName.startsWith("_")) {
            return true;
        }
        if (!fieldName.contains(".")) {
            return false;
        }
        // Dotted field: keep it only if the mapping recognizes it as an object subfield.
        return mappingContext == null || mappingContext.getFieldInfo(fieldName) == null;
    }

    /**
     * Insert a value at a possibly-dotted path, creating intermediate maps for object subfields.
     * A flat name like {@code "title"} becomes {@code target["title"] = value}; a dotted name
     * like {@code "address.city"} becomes {@code target["address"]["city"] = value}. If an
     * intermediate path collides with a non-map, non-list-of-maps value already present, the
     * existing value wins and the recovered value is dropped with a warn log (best-effort —
     * stored/doc_values loops prefer earlier writes).
     * <p>
     * <b>Object-array distribution.</b> When an intermediate path already holds a non-empty
     * {@code List<Map>} (an object-array seed from a partial {@code _source}, e.g.
     * {@code {"files":[{cksum:h1},{cksum:h2}]}}) and the remaining path is a single leaf
     * segment, the recovered value is distributed across the list elements:
     * <ul>
     *   <li>If {@code value} is a List of the same size, positionally:
     *       {@code list[i][leaf] = value[i]}.</li>
     *   <li>If {@code value} is a scalar, broadcast: {@code list[i][leaf] = value} for each i.</li>
     *   <li>If {@code value} is a List of different size, warn and drop.</li>
     * </ul>
     * See {@link #distributeSubfieldAcrossList} for the ordering caveat on doc_values-sourced
     * subfields (doc_values traversal order, not original insertion order).
     * <p>
     * Returns {@code true} iff the target map was modified (a new entry was written at any
     * level, including into a list element). Callers use this to decide whether a
     * partial-source re-serialization is needed after {@link #populateFromSegment} runs — raw
     * {@code target.size()} is not a sound dirty proxy because nested inserts do not change
     * the top-level map size.
     */
    @SuppressWarnings("unchecked")
    private static boolean putNested(Map<String, Object> target, String fieldName, Object value) {
        if (!fieldName.contains(".")) {
            if (target.containsKey(fieldName)) {
                return false;
            }
            target.put(fieldName, value);
            return true;
        }
        // Honour a literal dotted key already present in the map (some ingest pipelines preserve
        // dotted names verbatim in _source rather than nesting them). Treat it as present so we
        // don't emit both {"address.city":...} and {"address":{"city":...}}.
        if (target.containsKey(fieldName)) {
            return false;
        }
        String[] parts = fieldName.split("\\.");
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
                // Array-of-objects at this path (e.g. seeded _source has `files`: [{cksum:h1}, {cksum:h2}]).
                // The remaining suffix names the subfield to distribute across the list elements.
                // Only the LAST path segment is handled here; deeper nesting under a List<Map> parent
                // (e.g. `files.meta.size` where `files[i].meta` would itself be an object) falls through
                // to the warn branch below since per-element object construction from doc_values is
                // outside the guarantees doc_values can provide.
                if (i != parts.length - 2) {
                    log.atWarn()
                        .setMessage("Cannot write deeply-nested field '{}' under List<Map> at '{}'; dropping recovered value")
                        .addArgument(fieldName)
                        .addArgument(parts[i])
                        .log();
                    return false;
                }
                String leafForList = parts[parts.length - 1];
                return distributeSubfieldAcrossList((java.util.List<Object>) list, leafForList, value, fieldName);
            } else {
                // Non-map already sits at this path — cannot nest under a scalar. Warn once
                // per JVM so operators notice the class of problem without log floods.
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

    /** True iff {@code list} is non-empty and every element is a Map. A non-empty List<Map>
     *  is how a partial _source represents an object array (e.g. ES {@code type: object} or
     *  {@code type: nested} after flattening). Empty lists are ambiguous and are NOT treated
     *  as object arrays — falling through to the scalar-collision branch preserves the existing
     *  drop-and-warn contract for those. */
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
     * Distributes a recovered subfield value across the elements of an object-array seed.
     * <p>
     * Positional distribution ({@code list[i][leaf] = value[i]}) is used when {@code value} is a
     * List whose size matches the object array; scalar broadcast ({@code list[i][leaf] = value})
     * is used when {@code value} is not a List. Any element that already has {@code leaf} set is
     * preserved (first-write-wins, matching the rest of the reconstructor).
     * <p>
     * <b>Ordering caveat.</b> When {@code value} comes from doc_values, List order is the
     * doc_values traversal order (SortedNumeric: ascending numeric; SortedSet: ascending term),
     * <em>not</em> the original array insertion order. Element-to-element binding across
     * subfields recovered from doc_values is therefore approximate — useful for presence,
     * search, and aggregation, but not for display-accurate per-element tuples. Subfields
     * sourced from stored-fields (which preserve insertion order per document) are distributed
     * in their stored order.
     * <p>
     * Returns {@code true} iff at least one element was written.
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
    private static boolean hasNested(Map<String, Object> target, String fieldName) {
        if (target.containsKey(fieldName)) {
            return true;
        }
        if (!fieldName.contains(".")) {
            return false;
        }
        String[] parts = fieldName.split("\\.");
        Map<String, Object> cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (next instanceof Map<?, ?>) {
                cursor = (Map<String, Object>) next;
                continue;
            }
            // Array-of-objects ancestor (e.g. `files` is List<Map>): the leaf is considered present
            // iff the suffix is the immediate leaf AND every element already carries it. This keeps
            // mergeWithDocValues idempotent — a second pass over the same doc won't re-distribute,
            // and it correctly reports "not present" when only some elements have the subfield so
            // the distribute path can fill the gaps.
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
     * Uses mapping context for type-aware conversion when available.
     *
     * @param termIndex per-segment term position cache, scoped to the current
     *                  segment's Flux; may be null if caller does not need
     *                  analyzed-text fallback (treated as empty).
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
     * Merges reconstructed fields into existing source JSON. Used when the snapshot still
     * holds a (possibly partial) _source — e.g. _source.includes/_source.excludes indices.
     * Applies the same stored → doc_values → points/terms recovery chain as
     * {@link #reconstructSource}, but only for fields missing from the existing source,
     * so existing values are preserved verbatim.
     */
    public static String mergeWithDocValues(String existingSource, LuceneLeafReader reader, int docId,
            LuceneDocument document, FieldMappingContext mappingContext) {
        return mergeWithDocValues(existingSource, reader, docId, document, mappingContext, null);
    }

    @SuppressWarnings("unchecked")
    public static String mergeWithDocValues(String existingSource, LuceneLeafReader reader, int docId,
            LuceneDocument document, FieldMappingContext mappingContext, SegmentTermIndex termIndex) {
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
     * Populates {@code target} with fields recovered from a Lucene segment, skipping any
     * field already present. Shared by {@link #reconstructSource} (empty seed) and
     * {@link #mergeWithDocValues} (existing _source as seed) so both paths exercise the
     * same stored → doc_values → points/terms recovery chain. Returns {@code true} iff any
     * new value was written into {@code target} (including into a nested child map, which
     * would not change the top-level {@code target.size()}); callers use this to decide
     * whether a re-serialization is needed.
     */
    private static boolean populateFromSegment(Map<String, Object> target, LuceneLeafReader reader, int docId,
            LuceneDocument document, FieldMappingContext mappingContext, SegmentTermIndex termIndex) throws IOException {
        boolean modified = false;
        // 1. Stored fields (exact values when present)
        for (var field : document.getFields()) {
            String fieldName = field.name();
            if (shouldSkipField(fieldName, mappingContext) || hasNested(target, fieldName)) {
                continue;
            }
            FieldMappingInfo mappingInfo = mappingContext != null ? mappingContext.getFieldInfo(fieldName) : null;
            Object value = getStoredFieldValue(field, mappingInfo);
            if (value != null) {
                modified |= putNested(target, fieldName, value);
            }
        }

        // 2. Doc values (fast, lossless for typed fields)
        for (DocValueFieldInfo fieldInfo : reader.getDocValueFields()) {
            String fieldName = fieldInfo.name();
            if (shouldSkipField(fieldName, mappingContext) || hasNested(target, fieldName)) {
                continue;
            }
            FieldMappingInfo mappingInfo = mappingContext != null ? mappingContext.getFieldInfo(fieldName) : null;
            // Skip if mapping says doc_values is disabled (ES 2.x may still have them internally)
            if (mappingInfo != null && !mappingInfo.docValues()) {
                continue;
            }
            Object value = reader.getDocValue(docId, fieldInfo);
            if (value != null) {
                Object converted = convertDocValue(value, fieldInfo, mappingInfo);
                if (converted != null) {
                    modified |= putNested(target, fieldName, converted);
                }
            }
        }

        // 3. Points / indexed terms fallback (recovers indexed-only numeric/boolean/keyword)
        if (mappingContext != null) {
            for (String fieldName : mappingContext.getFieldNames()) {
                if (shouldSkipField(fieldName, mappingContext) || hasNested(target, fieldName)) {
                    continue;
                }
                FieldMappingInfo mappingInfo = mappingContext.getFieldInfo(fieldName);
                if (mappingInfo != null) {
                    var fallbackValue = reader.getValueFromPointsOrTerms(docId, fieldName, mappingInfo.type(), termIndex);
                    if (fallbackValue.isPresent()) {
                        Object converted = convertFallbackValue(fallbackValue.get(), mappingInfo);
                        if (converted != null) {
                            modified |= putNested(target, fieldName, converted);
                        }
                    }
                }
            }
        }

        // 4. Mapping-level constant values (constant_keyword stores its value in the mapping, not the segment)
        if (mappingContext != null) {
            for (String fieldName : mappingContext.getFieldNames()) {
                if (shouldSkipField(fieldName, mappingContext) || hasNested(target, fieldName)) {
                    continue;
                }
                FieldMappingInfo mappingInfo = mappingContext.getFieldInfo(fieldName);
                if (mappingInfo != null && mappingInfo.constantValue() != null) {
                    modified |= putNested(target, fieldName, mappingInfo.constantValue());
                }
            }
        }
        return modified;
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
                if ("half_float".equals(mappingType)) {
                    if (value instanceof Long longVal) {
                        // half_float uses 16-bit encoding - convert sortable short back to half float
                        yield sortableShortToHalfFloat(longVal.shortValue());
                    }
                }
                if ("float".equals(mappingType)) {
                    if (value instanceof Long longVal) {
                        yield Float.intBitsToFloat(longVal.intValue());
                    }
                }
                if ("double".equals(mappingType)) {
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
                if ("wildcard".equals(mappingType) && value instanceof String strVal) {
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

    /** Convert fallback value from Points (List&lt;byte[]&gt;), Terms (String), or NumericTerms (Long) */
    @SuppressWarnings("unchecked")
    private static Object convertFallbackValue(Object value, FieldMappingInfo mappingInfo) {
        // Terms fallback returns String (for boolean - stored as "T"/"F" in Lucene)
        if (value instanceof String termValue) {
            if (mappingInfo.type() == EsFieldType.BOOLEAN) {
                return "T".equals(termValue);
            }
            return termValue;
        }
        // Points fallback returns List<byte[]>
        if (value instanceof java.util.List<?> pointValues) {
            return decodePointValue((java.util.List<byte[]>) pointValues, mappingInfo);
        }
        // Numeric terms fallback returns a raw Long decoded from shift==0 trie term
        // (Lucene 4-5 / ES 1.x-2.x indexes numerics/ip/date as prefix-coded longs or ints).
        if (value instanceof Long numericVal) {
            return decodeNumericTerm(numericVal, mappingInfo);
        }
        return null;
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
                if ("double".equals(mappingType)) {
                    long bits = raw ^ ((raw >> 63) & 0x7FFFFFFFFFFFFFFFL);
                    yield Double.longBitsToDouble(bits);
                }
                if ("float".equals(mappingType) || "half_float".equals(mappingType)) {
                    int iraw = (int) raw;
                    int bits = iraw ^ ((iraw >> 31) & 0x7FFFFFFF);
                    yield Float.intBitsToFloat(bits);
                }
                if ("integer".equals(mappingType) || "short".equals(mappingType) || "byte".equals(mappingType)) {
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
                if ("half_float".equals(mappingType) && packed.length == 2) {
                    // half_float Points: read big-endian short, flip sign bit, then decode
                    short s = (short) (((packed[0] & 0xFF) << 8) | (packed[1] & 0xFF));
                    s ^= 0x8000; // flip sign bit (sortableBytesToShort)
                    yield sortableShortToHalfFloat(s);
                }
                if ("float".equals(mappingType) && packed.length == 4) {
                    int sortable = decodeIntPoint(packed);
                    // Undo Lucene's sortableInt→float transform for negative values
                    int bits = sortable ^ ((sortable >> 31) & 0x7FFFFFFF);
                    yield Float.intBitsToFloat(bits);
                }
                if ("double".equals(mappingType) && packed.length == 8) {
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
