package org.opensearch.migrations.bulkload.common.bulk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

/**
 * Utility class for converting bulk operations to NDJSON format.
 */
@UtilityClass
public final class BulkNdjson {
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final byte[] NEWLINE_BYTES = "\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Write a single operation to an output stream in NDJSON format.
     * @param op The operation to write
     * @param out The output stream to write to
     * @param mapper The ObjectMapper to use for serialization
     */
    @SneakyThrows
    public static void writeOperation(BulkOperationSpec op, OutputStream out, ObjectMapper mapper) {
        // action line: {"<op>": {...meta...}}
        Map<String, Object> meta = mapper.convertValue(op.getOperation(), new TypeReference<>() {});
        Map<String, Object> actionLine = Map.of(op.getOperationType().name().toLowerCase(), meta);

        out.write(mapper.writeValueAsBytes(actionLine));
        out.write(NEWLINE_BYTES);

        // optional source/payload
        if (op.isIncludeDocument() && op.getDocument() != null) {
            out.write(mapper.writeValueAsBytes(op.getDocument()));
            out.write(NEWLINE_BYTES);
        }
    }

    /**
     * Write a single operation to an output stream in NDJSON format.
     * @param ops The operation to write
     * @param out The output stream to write to
     * @param mapper The ObjectMapper to use for serialization
     */
    public static void writeAll(Collection<? extends BulkOperationSpec> ops, OutputStream out, ObjectMapper mapper) {
        for (BulkOperationSpec op : ops) writeOperation(op, out, mapper);
    }

    /**
     * Convert a list of bulk operations to NDJSON string.
     * @param ops The list of operations to convert
     * @param mapper The ObjectMapper to use for serialization
     * @return The NDJSON string representation
     */
    public static String toBulkNdjson(Collection<? extends BulkOperationSpec> ops, ObjectMapper mapper) {
        try (var baos = new ByteArrayOutputStream()) {
            writeAll(ops, baos, mapper);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Calculate the serialized length of a bulk operation in NDJSON format.
     * @return The length in bytes of the serialized operation
     */
    @SneakyThrows(IOException.class)
    public long getSerializedLength(BulkOperationSpec op) {
        try (var stream = new CountingOutputStream()) {
            BulkNdjson.writeOperation(op, stream, OBJECT_MAPPER);
            return stream.getCount();
        }
    }

    /**
     * Helper class for counting output stream bytes.
     */
    @Getter
    private static class CountingOutputStream extends OutputStream {
        private long count = 0;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            count += len;
        }
    }
}
