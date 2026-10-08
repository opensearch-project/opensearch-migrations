package org.opensearch.migrations.bulkload.common.bulk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.bulk.operations.BaseOperationMeta;
import org.opensearch.migrations.bulkload.common.bulk.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
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
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final byte[] NEWLINE_BYTES = "\n".getBytes(StandardCharsets.UTF_8);
    private static final int ESTIMATED_ACTION_BYTES = 256;
    private static final int MAX_INITIAL_BUFFER_BYTES = 16 * 1024 * 1024;

    /**
     * Write a single operation to an output stream in NDJSON format.
     * @param op The operation to write
     * @param out The output stream to write to
     * @param mapper The ObjectMapper to use for serialization
     */
    @SneakyThrows
    public static void writeOperation(BulkOperationSpec op, OutputStream out, ObjectMapper mapper) {
        if (op.getRawDocument() != null) {
            writeRawOperation(op.getOperationType().getValue(), op.getOperation(),
                op.isIncludeDocument() ? op.getRawDocument() : null, out, mapper);
            return;
        }
        // action line: {"<op>": {...meta...}}
        Map<String, Object> actionLine = Map.of(op.getOperationType().getValue(), op.getOperation());

        out.write(mapper.writeValueAsBytes(actionLine));

        // optional source/payload
        if (op.isIncludeDocument() && op.getDocument() != null) {
            out.write(NEWLINE_BYTES);
            out.write(mapper.writeValueAsBytes(op.getDocument()));
        }
    }

    /**
     * Write an action line + raw source bytes to an output stream in NDJSON format.
     * Skips Jackson deserialization/reserialization of the document body.
     */
    @SneakyThrows
    public static void writeRawOperation(String operationType, BaseOperationMeta meta,
                                         byte[] rawSource, OutputStream out, ObjectMapper mapper) {
        // Jackson already serializes the metadata's unwrapped fields. Avoid a
        // metadata -> Map -> JSON round trip on every document, including raw writes.
        Map<String, Object> actionLine = Map.of(operationType, meta);
        out.write(mapper.writeValueAsBytes(actionLine));

        if (rawSource != null && rawSource.length > 0) {
            validateJsonBytes(rawSource);
            out.write(NEWLINE_BYTES);
            out.write(rawSource);
        }
    }

    /**
     * Validate that raw bytes are structurally valid JSON.
     * Uses Jackson's streaming parser to verify without deserializing.
     * Prevents malformed JSON or embedded newlines from corrupting NDJSON output.
     */
    static void validateJsonBytes(byte[] bytes) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(bytes)) {
            while (parser.nextToken() != null) {
                // consume all tokens to verify structural validity
            }
        } catch (IOException e) {
            throw new IOException("Raw source bytes are not valid JSON", e);
        }
    }

    /**
     * Write a list of {@link Document} records as raw NDJSON bytes, skipping the
     * byte[]→Map→byte[] round-trip for document bodies.
     *
     * @param docs       the documents to write
     * @param indexName  the target index name
     * @param stripIds   whether to strip document IDs (for server-generated IDs)
     * @param mapper     the ObjectMapper to use for action-line serialization
     * @return the raw NDJSON bytes
     */
    public static byte[] toRawNdjsonBytes(
        List<? extends Document> docs,
        String indexName, boolean stripIds, ObjectMapper mapper
    ) {
        long sourceBytes = docs.stream().mapToLong(Document::sourceLength).sum();
        try (var baos = new ByteArrayOutputStream(initialBufferSize(sourceBytes, docs.size()))) {
            for (var doc : docs) {
                String opType = doc.operation() == Document.Operation.DELETE ? "delete" : "index";
                String docId = stripIds ? null : doc.id();
                String routing = doc.hints().get(Document.HINT_ROUTING);
                var meta = doc.operation() == Document.Operation.DELETE
                    ? DeleteOperationMeta.builder().id(docId).index(indexName).routing(routing).build()
                    : BulkOperationConverter.indexMetadata(doc, indexName, stripIds);
                writeRawOperation(opType, meta, doc.source(), baos, mapper);
                baos.write(NEWLINE_BYTES);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Write a single operation to an output stream in NDJSON format.
     * @param ops The operation to write
     * @param out The output stream to write to
     * @param mapper The ObjectMapper to use for serialization
     */
    public static void writeAll(Collection<? extends BulkOperationSpec> ops,
                                OutputStream out, ObjectMapper mapper) throws IOException {
        for (BulkOperationSpec op : ops) {
            writeOperation(op, out, mapper);
            out.write(NEWLINE_BYTES);
        }
    }

    /**
     * Convert a list of bulk operations to NDJSON bytes, using raw source bytes when available.
     * Avoids the byte[]→Map→byte[] round-trip for document bodies.
     */
    public static byte[] toBulkNdjsonBytes(Collection<? extends BulkOperationSpec> ops, ObjectMapper mapper) {
        long sourceBytes = ops.stream()
            .mapToLong(op -> op.isIncludeDocument() && op.getRawDocument() != null ? op.getRawDocument().length : 0)
            .sum();
        try (var baos = new ByteArrayOutputStream(initialBufferSize(sourceBytes, ops.size()))) {
            writeAll(ops, baos, mapper);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int initialBufferSize(long sourceBytes, int operationCount) {
        // Source lengths are already known on the raw path. Reserve room for them
        // and typical action lines to avoid repeatedly copying multi-megabyte bodies
        // as the buffer grows. This is an allocation estimate, not a request-size limit.
        return (int) Math.min(MAX_INITIAL_BUFFER_BYTES, sourceBytes + (long) ESTIMATED_ACTION_BYTES * operationCount);
    }

    /**
     * Convert a list of bulk operations to NDJSON string.
     * @param ops The list of operations to convert
     * @param mapper The ObjectMapper to use for serialization
     * @return The NDJSON string representation
     */
    public static String toBulkNdjson(Collection<? extends BulkOperationSpec> ops, ObjectMapper mapper) {
        return new String(toBulkNdjsonBytes(ops, mapper), StandardCharsets.UTF_8);
    }

    /**
     * Calculate the serialized length of a bulk operation in NDJSON format.
     * @return The length in bytes of the serialized operation
     */
    @SneakyThrows(IOException.class)
    public long getSerializedLength(BulkOperationSpec op) {
        try (var stream = new CountingOutputStream()) {
            BulkNdjson.writeOperation(op, stream, OBJECT_MAPPER);
            return stream.getBytesWritten();
        }
    }

    /**
     * Helper class for counting output stream bytes.
     */
    @Getter
    private static class CountingOutputStream extends OutputStream {
        private long bytesWritten = 0;

        @Override
        public void write(int b) {
            bytesWritten++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            bytesWritten += len;
        }
    }
}
