package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** Skip internal fields (e.g., _id) and multi-field sub-fields (e.g., title.keyword) */
    private static boolean shouldSkipField(String fieldName) {
        return fieldName.startsWith("_") || fieldName.contains(".");
    }

    /**
     * Reconstructs _source JSON from stored fields and doc_values for a document.
     * Uses mapping context for type-aware conversion when available.
     */
    public static String reconstructSource(LuceneLeafReader reader, int docId, LuceneDocument document, FieldMappingContext mappingContext) {
        try {
            Map<String, Object> reconstructed = new LinkedHashMap<>();
            
            // First, read from stored fields (more performant)
            for (var field : document.getFields()) {
                String fieldName = field.name();
                if (shouldSkipField(fieldName)) {
                    continue;
                }
                FieldMappingInfo mappingInfo = mappingContext != null ? mappingContext.getFieldInfo(fieldName) : null;
                Object value = getStoredFieldValue(field, mappingInfo);
                if (value != null) {
                    reconstructed.put(fieldName, value);
                }
            }
            
            // Then, read from doc_values (for fields not already in stored fields)
            for (DocValueFieldInfo fieldInfo : reader.getDocValueFields()) {
                String fieldName = fieldInfo.name();
                log.debug("[DocValues] Found field {} with type {}", fieldName, fieldInfo.docValueType());
                if (shouldSkipField(fieldName) || reconstructed.containsKey(fieldName)) {
                    continue;
                }
                FieldMappingInfo mappingInfo = mappingContext != null ? mappingContext.getFieldInfo(fieldName) : null;
                // Skip if mapping says doc_values is disabled (ES 2.x may still have them internally)
                if (mappingInfo != null && !mappingInfo.docValues()) {
                    continue;
                }
                Object value = reader.getDocValue(docId, fieldInfo);
                log.debug("[DocValues] Field {} raw value: {}", fieldName, value);
                if (value != null) {
                    Object converted = convertDocValue(value, fieldInfo, mappingInfo);
                    if (converted != null) {
                        reconstructed.put(fieldName, converted);
                    }
                }
            }
            
            // Finally, try Points or Terms fallback for fields not yet recovered
            if (mappingContext != null) {
                for (String fieldName : mappingContext.getFieldNames()) {
                    if (shouldSkipField(fieldName) || reconstructed.containsKey(fieldName)) {
                        continue;
                    }
                    FieldMappingInfo mappingInfo = mappingContext.getFieldInfo(fieldName);
                    if (mappingInfo != null) {
                        var fallbackValue = reader.getValueFromPointsOrTerms(docId, fieldName, mappingInfo.type());
                        if (fallbackValue.isPresent()) {
                            Object converted = convertFallbackValue(fallbackValue.get(), mappingInfo);
                            if (converted != null) {
                                log.debug("[Fallback] Recovered field {} = {}", fieldName, converted);
                                reconstructed.put(fieldName, converted);
                            }
                        }
                    }
                }
            }
            
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

    /**
     * Merges reconstructed fields into existing source JSON.
     */
    @SuppressWarnings("unchecked")
    public static String mergeWithDocValues(String existingSource, LuceneLeafReader reader, int docId, LuceneDocument document, FieldMappingContext mappingContext) {
        try {
            Map<String, Object> existing = OBJECT_MAPPER.readValue(existingSource, Map.class);
            boolean modified = false;
            
            for (DocValueFieldInfo fieldInfo : reader.getDocValueFields()) {
                String fieldName = fieldInfo.name();
                if (shouldSkipField(fieldName) || existing.containsKey(fieldName)) {
                    continue;
                }
                Object value = reader.getDocValue(docId, fieldInfo);
                if (value != null) {
                    FieldMappingInfo mappingInfo = mappingContext != null ? mappingContext.getFieldInfo(fieldName) : null;
                    existing.put(fieldName, convertDocValue(value, fieldInfo, mappingInfo));
                    modified = true;
                }
            }
            
            for (var field : document.getFields()) {
                String fieldName = field.name();
                if (shouldSkipField(fieldName) || existing.containsKey(fieldName)) {
                    continue;
                }
                FieldMappingInfo mappingInfo = mappingContext != null ? mappingContext.getFieldInfo(fieldName) : null;
                Object value = getStoredFieldValue(field, mappingInfo);
                if (value != null) {
                    existing.put(fieldName, value);
                    modified = true;
                }
            }
            
            return modified ? OBJECT_MAPPER.writeValueAsString(existing) : existingSource;
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to merge fields for document {}").addArgument(docId).log();
            return existingSource;
        }
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
            if (mappingInfo != null && mappingInfo.type() == EsFieldType.STRING) {
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
        
        // Use mapping info to convert string-stored values back to proper types
        if (mappingInfo != null) {
            try {
                return switch (mappingInfo.type()) {
                    case NUMERIC -> {
                        if (value.contains(".")) {
                            yield Double.parseDouble(value);
                        }
                        yield Long.parseLong(value);
                    }
                    case BOOLEAN -> Boolean.parseBoolean(value);
                    case DATE -> {
                        // Date stored as epoch millis string - convert to ISO format
                        if (value.matches("\\d+")) {
                            yield formatDate(Long.parseLong(value), mappingInfo.format());
                        }
                        yield value; // Already in date format
                    }
                    case DATE_NANOS -> {
                        // Date nanos stored as epoch nanos string
                        if (value.matches("\\d+")) {
                            yield formatDateNanos(Long.parseLong(value));
                        }
                        yield value;
                    }
                    case SCALED_FLOAT -> {
                        if (mappingInfo.scalingFactor() != null && value.matches("\\d+")) {
                            yield Long.parseLong(value) / mappingInfo.scalingFactor();
                        }
                        yield Double.parseDouble(value);
                    }
                    case IP -> {
                        // ES 2.x stores IP as numeric string - convert to dotted decimal
                        if (value.matches("\\d+")) {
                            long ipLong = Long.parseLong(value);
                            yield String.format("%d.%d.%d.%d",
                                (ipLong >> 24) & 0xFF, (ipLong >> 16) & 0xFF,
                                (ipLong >> 8) & 0xFF, ipLong & 0xFF);
                        }
                        yield value; // Already in IP format
                    }
                    default -> value;
                };
            } catch (NumberFormatException e) {
                // Fall through to return as string
            }
        }
        return value;
    }

    /** Converts doc_value using mapping info when available, falling back to heuristics */
    private static Object convertDocValue(Object value, DocValueFieldInfo fieldInfo, FieldMappingInfo mappingInfo) {
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
                if ("float".equals(mappingType) || "half_float".equals(mappingType)) {
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
                yield value;
            }
            case BINARY -> {
                if (value instanceof byte[] bytes) {
                    yield Base64.getEncoder().encodeToString(bytes);
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

    private static Map<String, Double> decodeGeoPoint(long encoded) {
        int latBits = (int) (encoded >> 32);
        int lonBits = (int) encoded;
        double lat = decodeLatitude(latBits);
        double lon = decodeLongitude(lonBits);
        return Map.of("lat", lat, "lon", lon);
    }

    private static double decodeLatitude(int encoded) {
        return encoded / (double)(1 << 31) * 180.0;
    }

    private static double decodeLongitude(int encoded) {
        return encoded / (double)(1 << 31) * 360.0;
    }

    /** Convert fallback value from Points (List<byte[]>) or Terms (String) */
    @SuppressWarnings("unchecked")
    private static Object convertFallbackValue(Object value, FieldMappingInfo mappingInfo) {
        // Terms fallback returns String (for boolean)
        if (value instanceof String termValue) {
            if (mappingInfo.type() == EsFieldType.BOOLEAN) {
                return "T".equals(termValue) || "true".equals(termValue) || "1".equals(termValue);
            }
            return termValue;
        }
        // Points fallback returns List<byte[]>
        if (value instanceof java.util.List<?> pointValues) {
            return decodePointValue((java.util.List<byte[]>) pointValues, mappingInfo);
        }
        return null;
    }

    /** Decode point value from packed bytes */
    private static Object decodePointValue(java.util.List<byte[]> pointValues, FieldMappingInfo mappingInfo) {
        if (pointValues.isEmpty()) return null;
        byte[] packed = pointValues.get(0);
        
        return switch (mappingInfo.type()) {
            case IP -> packed.length == 16 ? convertIpBytes(packed) : null;
            case NUMERIC -> {
                String mappingType = mappingInfo.mappingType();
                if (("float".equals(mappingType) || "half_float".equals(mappingType)) && packed.length == 4) {
                    yield Float.intBitsToFloat(decodeIntPoint(packed));
                }
                if ("double".equals(mappingType) && packed.length == 8) {
                    yield Double.longBitsToDouble(decodeLongPoint(packed));
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
}
