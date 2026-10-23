package org.opensearch.migrations.bulkload.common.bulk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.operations.BaseOperationMeta;
import org.opensearch.migrations.bulkload.common.bulk.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

/** Encoding and assembly of bulk API requests. */
@UtilityClass
public final class BulkNdjson {
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /**
     * The exact bytes of an operation, retained for batching, sending, and retries.
     * Raw bodies reference the original source array; transformed bodies are serialized once.
     */
    public record EncodedOperation(byte[] action, byte[] body, boolean upsert) {
        /** Includes the final newline and, when present, the separator before the body. */
        public long size() {
            return action.length + 1L + (body == null ? 0 : body.length + 1L);
        }

        @SneakyThrows(IOException.class)
        private void writeWithoutFinalNewline(OutputStream out) {
            out.write(action);
            if (body != null) {
                out.write('\n');
                out.write(body);
            }
        }
    }

    @SneakyThrows(IOException.class)
    public static EncodedOperation encode(BulkOperationSpec op, ObjectMapper mapper) {
        if (op.getRawDocument() != null) {
            return encodeRaw(op.getOperationType(), op.getOperation(),
                op.isIncludeDocument() ? op.getRawDocument() : null, mapper);
        }
        byte[] body = op.isIncludeDocument() && op.getDocument() != null
            ? mapper.writeValueAsBytes(op.getDocument()) : null;
        return new EncodedOperation(
            mapper.writeValueAsBytes(Map.of(op.getOperationType().getValue(), op.getOperation())),
            body, op.getOperationType() == OperationType.INDEX);
    }

    public static EncodedOperation encodeRaw(Document doc, String indexName, boolean stripIds, ObjectMapper mapper) {
        boolean isDelete = doc.operation() == Document.Operation.DELETE;
        var meta = isDelete
            ? DeleteOperationMeta.builder().id(stripIds ? null : doc.id()).index(indexName)
                .routing(doc.hints().get(Document.HINT_ROUTING)).build()
            : BulkOperationConverter.indexMetadata(doc, indexName, stripIds);
        // Delta deletions retain the old source for diagnostics, but bulk deletes have no payload.
        return encodeRaw(isDelete ? OperationType.DELETE : OperationType.INDEX,
            meta, isDelete ? null : doc.source(), mapper);
    }

    @SneakyThrows(IOException.class)
    private static EncodedOperation encodeRaw(
        OperationType operationType, BaseOperationMeta meta, byte[] rawSource, ObjectMapper mapper
    ) {
        byte[] body = rawSource != null && rawSource.length > 0 ? rawSource : null;
        if (body != null) {
            validateJsonBytes(body);
        }
        return new EncodedOperation(mapper.writeValueAsBytes(Map.of(operationType.getValue(), meta)), body,
            operationType == OperationType.INDEX);
    }

    /** Validate raw JSON once, before retaining its bytes for sending and retries. */
    static void validateJsonBytes(byte[] bytes) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(bytes)) {
            while (parser.nextToken() != null) {
                // Consume all tokens to verify structural validity without materializing the body.
            }
        } catch (IOException e) {
            throw new IOException("Raw source bytes are not valid JSON", e);
        }
    }

    @SuppressWarnings({"java:S100", "java:S1186"})
    public record SerializedBulk(byte[] bytes, MigrationMetrics.Operations operations) {}

    /** Assemble exactly the bytes that were measured, with one allocation and no growing-buffer copy. */
    public static SerializedBulk assemble(List<EncodedOperation> operations) {
        return assemble(operations, Function.identity());
    }

    /** Assemble prepared operations directly, without copying their encoded references into another list. */
    public static <T> SerializedBulk assemble(List<T> operations, Function<T, EncodedOperation> encoded) {
        long size = 0;
        for (var operation : operations) {
            size += encoded.apply(operation).size();
        }
        var buffer = ByteBuffer.allocate(Math.toIntExact(size));
        long upserts = 0;
        long bodyBytes = 0;
        for (var item : operations) {
            var operation = encoded.apply(item);
            buffer.put(operation.action());
            if (operation.body() != null) {
                buffer.put((byte) '\n').put(operation.body());
                bodyBytes += operation.body().length;
            }
            buffer.put((byte) '\n');
            if (operation.upsert()) {
                upserts++;
            }
        }
        return new SerializedBulk(buffer.array(),
            new MigrationMetrics.Operations(upserts, operations.size() - upserts, bodyBytes));
    }

    public static SerializedBulk serialize(Collection<? extends BulkOperationSpec> ops, ObjectMapper mapper) {
        return assemble(ops.stream().map(op -> encode(op, mapper)).toList());
    }

    public static SerializedBulk serializeRaw(
        List<? extends Document> docs, String indexName, boolean stripIds, ObjectMapper mapper
    ) {
        return assemble(docs.stream().map(doc -> encodeRaw(doc, indexName, stripIds, mapper)).toList());
    }

    public static byte[] toBulkNdjsonBytes(Collection<? extends BulkOperationSpec> ops, ObjectMapper mapper) {
        return serialize(ops, mapper).bytes();
    }

    public static byte[] toRawNdjsonBytes(
        List<? extends Document> docs, String indexName, boolean stripIds, ObjectMapper mapper
    ) {
        return serializeRaw(docs, indexName, stripIds, mapper).bytes();
    }

    public static String toBulkNdjson(Collection<? extends BulkOperationSpec> ops, ObjectMapper mapper) {
        return new String(toBulkNdjsonBytes(ops, mapper), StandardCharsets.UTF_8);
    }

    /** Write an operation without its final newline, for callers assembling their own streams. */
    public static void writeOperation(BulkOperationSpec op, OutputStream out, ObjectMapper mapper) {
        encode(op, mapper).writeWithoutFinalNewline(out);
    }

    public static void writeRawOperation(
        OperationType operationType, BaseOperationMeta meta, byte[] rawSource, OutputStream out, ObjectMapper mapper
    ) {
        encodeRaw(operationType, meta, rawSource, mapper).writeWithoutFinalNewline(out);
    }

    public static void writeAll(Collection<? extends BulkOperationSpec> ops, OutputStream out, ObjectMapper mapper)
        throws IOException {
        for (var op : ops) {
            writeOperation(op, out, mapper);
            out.write('\n');
        }
    }

    /** Serialized length excluding the final newline. Prefer retaining {@link EncodedOperation} when sending. */
    public long getSerializedLength(BulkOperationSpec op) {
        return encode(op, OBJECT_MAPPER).size() - 1;
    }
}
