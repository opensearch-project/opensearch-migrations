package org.opensearch.migrations.bulkload.lucene;

import java.net.InetAddress;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared utility for reading stored fields from Lucene documents and assembling
 * them into JSON. Used as the foundation for both ES sourceless reconstruction
 * and Solr document reading (which always reads from stored fields).
 */
public final class StoredFieldDocReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StoredFieldDocReader() {}

    /**
     * Result of reading a document's stored fields.
     */
    public record ReadResult(
        Map<String, Object> fields,
        String documentId
    ) {}

    /**
     * Read stored fields from a Lucene document, filtering out internal fields and
     * extracting the document ID.
     *
     * @param document the Lucene document to read
     * @param idFieldName the field name that contains the document ID ("_id" for ES, "id" for Solr)
     * @param internalFieldFilter predicate that returns true for fields that should be skipped
     * @param mappingContext optional mapping context for type-aware conversion (null for basic conversion)
     * @return ReadResult with extracted fields and document ID, or null if document has no usable fields
     */
    public static ReadResult readStoredFields(
        LuceneDocument document,
        String idFieldName,
        Predicate<String> internalFieldFilter,
        FieldMappingContext mappingContext
    ) {
        String documentId = null;
        var fields = new LinkedHashMap<String, Object>();

        for (var field : document.getFields()) {
            String fieldName = field.name();

            if (fieldName.equals(idFieldName)) {
                // Extract ID - use asUid() for _id (ES Lucene 7+), stringValue() otherwise
                documentId = "_id".equals(idFieldName) ? field.asUid() : field.stringValue();
                if (documentId == null) {
                    documentId = field.stringValue();
                }
                continue;
            }

            if (internalFieldFilter.test(fieldName)) {
                continue;
            }

            FieldMappingInfo mappingInfo = (mappingContext != null)
                ? mappingContext.getFieldInfo(fieldName)
                : null;
            Object value = extractFieldValue(field, mappingInfo);
            if (value != null) {
                addMultiValue(fields, fieldName, value);
            }
        }

        return new ReadResult(fields, documentId);
    }

    /**
     * Extract a field value from a LuceneField, with optional mapping-aware type conversion.
     *
     * <p>Without mappingInfo (or for unmapped fields):
     * <ul>
     *   <li>Numeric values returned as-is</li>
     *   <li>"T"/"F" converted to boolean true/false</li>
     *   <li>Other strings returned as-is</li>
     * </ul>
     *
     * <p>With mappingInfo:
     * <ul>
     *   <li>Boolean fields: numeric 0/1 or "T"/"F" converted to true/false</li>
     *   <li>Date fields: epoch millis converted to ISO string (respecting format)</li>
     *   <li>IP fields: 4-byte or 16-byte conversion to dotted-quad or colon notation</li>
     *   <li>Scaled float: long / scalingFactor</li>
     *   <li>Other types: delegates to mapping-aware conversion then falls through to basic</li>
     * </ul>
     */
    public static Object extractFieldValue(LuceneField field, FieldMappingInfo mappingInfo) {
        Number numValue = field.numericValue();
        if (numValue != null) {
            if (mappingInfo != null) {
                return convertNumericWithMapping(numValue, mappingInfo);
            }
            return numValue;
        }

        byte[] binaryData = field.binaryValue();
        if (binaryData != null && binaryData.length > 0 && mappingInfo != null) {
            Object converted = convertBinaryWithMapping(binaryData, mappingInfo);
            if (converted != null) {
                return converted;
            }
        }

        String value = field.stringValue();
        if (value == null) {
            value = field.utf8ToStringValue();
        }
        if (value == null) {
            return null;
        }

        if (mappingInfo != null) {
            Object converted = convertStringWithMapping(value, mappingInfo);
            if (converted != null) {
                return converted;
            }
        }

        // Basic conversion: "T"/"F" to boolean
        if ("T".equals(value)) {
            return true;
        }
        if ("F".equals(value)) {
            return false;
        }
        return value;
    }

    /**
     * Add a field value to the fields map, handling multi-valued fields by
     * converting scalars to lists when a second value arrives for the same key.
     */
    @SuppressWarnings("unchecked")
    public static void addMultiValue(Map<String, Object> fields, String name, Object value) {
        var existing = fields.get(name);
        if (existing == null) {
            fields.put(name, value);
        } else if (existing instanceof List) {
            ((List<Object>) existing).add(value);
        } else {
            var list = new ArrayList<>();
            list.add(existing);
            list.add(value);
            fields.put(name, list);
        }
    }

    /**
     * Serialize a fields map to JSON bytes.
     */
    public static byte[] toJsonBytes(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsBytes(fields);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize document fields to JSON", e);
        }
    }

    // ---- Mapping-aware conversion helpers ----

    private static Object convertNumericWithMapping(Number numValue, FieldMappingInfo mappingInfo) {
        switch (mappingInfo.type()) {
            case BOOLEAN:
                return numValue.longValue() != 0;
            case DATE:
                return formatDate(numValue.longValue(), mappingInfo.format());
            case DATE_NANOS:
                return formatDateNanos(numValue.longValue());
            case IP:
                // ES 2.x stores IPv4 as 32-bit integer
                long ipLong = numValue.longValue();
                return String.format("%d.%d.%d.%d",
                    (ipLong >> 24) & 0xFF, (ipLong >> 16) & 0xFF,
                    (ipLong >> 8) & 0xFF, ipLong & 0xFF);
            case SCALED_FLOAT:
                if (mappingInfo.scalingFactor() != null) {
                    return numValue.longValue() / mappingInfo.scalingFactor();
                }
                return numValue;
            default:
                return numValue;
        }
    }

    private static Object convertBinaryWithMapping(byte[] binaryData, FieldMappingInfo mappingInfo) {
        switch (mappingInfo.type()) {
            case IP:
                // ES 5+ stores IP as 16-byte IPv6 format
                return convertIpBytes(binaryData);
            case STRING:
                // STRING fields (keyword/text) in ES 5+ store UTF-8 bytes
                return new String(binaryData, java.nio.charset.StandardCharsets.UTF_8);
            default:
                return null;
        }
    }

    private static Object convertStringWithMapping(String value, FieldMappingInfo mappingInfo) {
        switch (mappingInfo.type()) {
            case BOOLEAN:
                if ("T".equals(value)) return true;
                if ("F".equals(value)) return false;
                return null; // fall through to basic conversion
            case DATE:
                try {
                    return formatDate(Long.parseLong(value), mappingInfo.format());
                } catch (NumberFormatException e) {
                    return value; // Already formatted or custom format
                }
            case SCALED_FLOAT:
                if (mappingInfo.scalingFactor() != null) {
                    try {
                        return Long.parseLong(value) / mappingInfo.scalingFactor();
                    } catch (NumberFormatException e) {
                        return value;
                    }
                }
                return null;
            case IP:
                try {
                    long ipLong = Long.parseLong(value);
                    return String.format("%d.%d.%d.%d",
                        (ipLong >> 24) & 0xFF, (ipLong >> 16) & 0xFF,
                        (ipLong >> 8) & 0xFF, ipLong & 0xFF);
                } catch (NumberFormatException e) {
                    return value; // Already an IP string
                }
            default:
                return null; // fall through to basic conversion
        }
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
                    if (bytes[i] != 0) {
                        isIpv4Mapped = false;
                        break;
                    }
                }
                if (isIpv4Mapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
                    return String.format("%d.%d.%d.%d",
                        bytes[12] & 0xFF, bytes[13] & 0xFF,
                        bytes[14] & 0xFF, bytes[15] & 0xFF);
                }
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception e) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
    }
}
