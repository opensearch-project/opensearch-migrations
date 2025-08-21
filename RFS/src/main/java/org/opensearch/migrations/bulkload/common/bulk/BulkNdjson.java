package org.opensearch.migrations.bulkload.common.bulk;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.enums.OperationType;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility class for converting bulk operations to NDJSON format.
 */
public final class BulkNdjson {
    private static final String NEWLINE = "\n";
    
    private BulkNdjson() {
        // Utility class, no instantiation
    }
    
    /**
     * Convert a list of bulk operations to NDJSON string.
     * @param ops The list of operations to convert
     * @param mapper The ObjectMapper to use for serialization
     * @return The NDJSON string representation
     */
    public static String toBulkNdjson(List<BulkOperationSpec> ops, ObjectMapper mapper) {
        StringBuilder sb = new StringBuilder(ops.size() * 128);
        for (BulkOperationSpec op : ops) {
            // 1) Action line: {"<op>": { ...meta... }}
            var meta = mapper.convertValue(op.getOperation(), new TypeReference<Map<String, Object>>() {});
            Map<String, Object> actionLine = Map.of(op.getOperationType().getValue(), meta);
            appendJsonLine(sb, actionLine, mapper);
            
            // 2) Optional source/payload line
            if (op.getOperationType() == OperationType.INDEX) {
                if (op.isIncludeDocument() && op.getDocument() != null) {
                    appendJsonLine(sb, op.getDocument(), mapper);
                }
            }
            // DELETE operations don't have a source line
        }
        return sb.toString();
    }
    
    /**
     * Write a single operation to an output stream in NDJSON format.
     * @param op The operation to write
     * @param out The output stream to write to
     * @param mapper The ObjectMapper to use for serialization
     * @throws IOException If an I/O error occurs
     */
    public static void writeOperation(BulkOperationSpec op, OutputStream out, ObjectMapper mapper) 
            throws IOException {
        // 1) Action line: {"<op>": { ...meta... }}
        var meta = mapper.convertValue(op.getOperation(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> actionLine = Map.of(op.getOperationType().getValue(), meta);
        
        byte[] actionBytes = mapper.writeValueAsBytes(actionLine);
        out.write(actionBytes);
        out.write(NEWLINE.getBytes());
        
        // 2) Optional source line for index operations
        if (op.getOperationType() == OperationType.INDEX) {
            if (op.isIncludeDocument() && op.getDocument() != null) {
                byte[] docBytes = mapper.writeValueAsBytes(op.getDocument());
                out.write(docBytes);
                out.write(NEWLINE.getBytes());
            }
        }
        // DELETE operations don't have a source line
    }
    
    /**
     * Append a JSON object as a line to the StringBuilder.
     * @param sb The StringBuilder to append to
     * @param value The value to serialize
     * @param mapper The ObjectMapper to use for serialization
     */
    private static void appendJsonLine(StringBuilder sb, Object value, ObjectMapper mapper) {
        try {
            sb.append(mapper.writeValueAsString(value)).append(NEWLINE);
        } catch (Exception e) {
            throw new RuntimeException("Serialization error", e);
        }
    }
}
