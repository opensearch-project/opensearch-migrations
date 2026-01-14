package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
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

    private SourceReconstructor() {}

    /** Skip internal fields (e.g., _id) and multi-field sub-fields (e.g., title.keyword) */
    private static boolean shouldSkipField(String fieldName) {
        return fieldName.startsWith("_") || fieldName.contains(".");
    }

    /**
     * Reconstructs _source JSON from stored fields and doc_values for a document.
     * Stored fields are checked first (more performant), then doc_values for remaining fields.
     * 
     * @param reader The leaf reader to access doc_values
     * @param docId The document ID within the segment
     * @param document The Lucene document containing stored fields
     * @return Reconstructed JSON string, or null if reconstruction fails
     */
    public static String reconstructSource(LuceneLeafReader reader, int docId, LuceneDocument document) {
        try {
            Map<String, Object> reconstructed = new LinkedHashMap<>();
            
            // First, read from stored fields (more performant)
            for (var field : document.getFields()) {
                String fieldName = field.name();
                if (shouldSkipField(fieldName)) {
                    log.atDebug().setMessage("Skipping stored field: {}").addArgument(fieldName).log();
                    continue;
                }
                Object value = getStoredFieldValue(field);
                if (value != null) {
                    log.atDebug().setMessage("Stored field {}: {}").addArgument(fieldName).addArgument(value).log();
                    reconstructed.put(fieldName, value);
                } else {
                    log.atDebug().setMessage("Stored field {} returned null value").addArgument(fieldName).log();
                }
            }
            
            // Then, read from doc_values (for fields not already in stored fields)
            for (DocValueFieldInfo fieldInfo : reader.getDocValueFields()) {
                String fieldName = fieldInfo.name();
                log.atDebug().setMessage("Processing doc_value field: {} type: {}").addArgument(fieldName).addArgument(fieldInfo.docValueType()).log();
                if (shouldSkipField(fieldName)) {
                    log.atDebug().setMessage("Skipping doc_value field: {}").addArgument(fieldName).log();
                    continue;
                }
                if (reconstructed.containsKey(fieldName)) {
                    log.atDebug().setMessage("Doc_value field {} already in reconstructed from stored").addArgument(fieldName).log();
                    continue;
                }
                Object value = reader.getDocValue(docId, fieldInfo);
                if (value != null) {
                    Object converted = convertDocValue(value, fieldInfo);
                    log.atDebug().setMessage("Doc_value field {}: {} -> {}").addArgument(fieldName).addArgument(value).addArgument(converted).log();
                    reconstructed.put(fieldName, converted);
                } else {
                    log.atDebug().setMessage("Doc_value field {} returned null for docId {}").addArgument(fieldName).addArgument(docId).log();
                }
            }
            
            if (reconstructed.isEmpty()) {
                log.atWarn().setMessage("No stored fields or doc_values found for document {}").addArgument(docId).log();
                return null;
            }
            
            log.atDebug().setMessage("Reconstructed source for doc {}: {}").addArgument(docId).addArgument(reconstructed).log();
            return OBJECT_MAPPER.writeValueAsString(reconstructed);
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to reconstruct source for document {}").addArgument(docId).log();
            return null;
        }
    }

    /**
     * Merges reconstructed fields into existing source JSON.
     * Uses doc_values first, then falls back to stored fields from the document.
     */
    @SuppressWarnings("unchecked")
    public static String mergeWithDocValues(String existingSource, LuceneLeafReader reader, int docId, LuceneDocument document) {
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
                    existing.put(fieldName, convertDocValue(value, fieldInfo));
                    modified = true;
                }
            }
            
            for (var field : document.getFields()) {
                String fieldName = field.name();
                if (shouldSkipField(fieldName) || existing.containsKey(fieldName)) {
                    continue;
                }
                Object value = getStoredFieldValue(field);
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
    private static Object getStoredFieldValue(LuceneField field) {
        // Check if it's a numeric field first
        Number num = field.numericValue();
        if (num != null) {
            return num;
        }
        // Check for binary data - must be encoded as base64 for ES binary fields
        byte[] binaryData = field.binaryValue();
        if (binaryData != null) {
            return Base64.getEncoder().encodeToString(binaryData);
        }
        // String field - check for boolean encoding
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
        return value;
    }

    /** Converts doc_value, handling boolean 0/1 to false/true conversion and IP numeric to string */
    private static Object convertDocValue(Object value, DocValueFieldInfo fieldInfo) {
        if (fieldInfo.isBoolean() && value instanceof Long) {
            return ((Long) value) != 0;
        }
        // IP fields store IPv4 as unsigned 32-bit integers
        // Detect by field name pattern (ip_*) - this is a heuristic
        if (value instanceof Long && fieldInfo.name().startsWith("ip_")) {
            long ipLong = (Long) value;
            // Convert numeric IP back to dotted-decimal notation
            return String.format("%d.%d.%d.%d",
                (ipLong >> 24) & 0xFF,
                (ipLong >> 16) & 0xFF,
                (ipLong >> 8) & 0xFF,
                ipLong & 0xFF);
        }
        return value;
    }
}
