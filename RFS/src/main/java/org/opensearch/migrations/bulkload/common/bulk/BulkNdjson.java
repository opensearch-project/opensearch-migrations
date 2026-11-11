package org.opensearch.migrations.bulkload.common.bulk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SourceFormat;
import org.opensearch.migrations.bulkload.common.SourceFormat.InvalidDocumentSourceException;
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
    private static final int POOLED_BUFFER_SIZE_CLASS_BYTES = 256 * 1024;
    private static final int MAX_POOLED_BUFFER_BYTES = 16 * 1024 * 1024;
    private static final long MAX_RETAINED_POOLED_BYTES = 128L * 1024 * 1024;
    private static final BulkBufferPool BULK_BUFFER_POOL = new BulkBufferPool();

    /**
     * The exact bytes of an operation, retained for batching, sending, and retries.
     * Single-line raw bodies reference the original source array; transformed bodies are serialized once.
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

    /**
     * Raw operation sized exactly from its action and source bytes, before validating or normalizing the source JSON.
     *
     * <p>JSON whitespace normalization preserves the body length, so this can make exact batching decisions on the
     * ordered source stream while deferring the expensive body scan to the concurrent request-preparation path.
     */
    public record PreparedRawOperation(byte[] action, byte[] rawBody, boolean upsert) {
        public long size() {
            return action.length + 1L + (rawBody == null ? 0 : rawBody.length + 1L);
        }
    }

    @SneakyThrows(IOException.class)
    public static EncodedOperation encode(BulkOperationSpec op, ObjectMapper mapper) {
        if (op.getRawDocument() != null) {
            return encodePreparedRaw(prepareRaw(op.getOperationType(), op.getOperation(),
                op.isIncludeDocument() ? op.getRawDocument() : null, mapper));
        }
        byte[] body = op.isIncludeDocument() && op.getDocument() != null
            ? mapper.writeValueAsBytes(op.getDocument()) : null;
        return new EncodedOperation(
            mapper.writeValueAsBytes(Map.of(op.getOperationType().getValue(), op.getOperation())),
            body, op.getOperationType() == OperationType.INDEX);
    }

    public static EncodedOperation encodeRaw(Document doc, String indexName, boolean stripIds, ObjectMapper mapper) {
        return encodePreparedRaw(prepareRaw(doc, indexName, stripIds, mapper));
    }

    public static PreparedRawOperation prepareRaw(
        Document doc, String indexName, boolean stripIds, ObjectMapper mapper
    ) {
        boolean isDelete = doc.operation() == Document.Operation.DELETE;
        var meta = isDelete
            ? DeleteOperationMeta.builder().id(stripIds ? null : doc.id()).index(indexName)
                .routing(doc.hints().get(Document.HINT_ROUTING)).build()
            : BulkOperationConverter.indexMetadata(doc, indexName, stripIds);
        // Delta deletions retain the old source for diagnostics, but bulk deletes have no payload.
        return prepareRaw(isDelete ? OperationType.DELETE : OperationType.INDEX,
            meta, isDelete ? null : doc.source(), mapper);
    }

    @SneakyThrows(IOException.class)
    private static PreparedRawOperation prepareRaw(
        OperationType operationType, BaseOperationMeta meta, byte[] rawSource, ObjectMapper mapper
    ) {
        byte[] body = rawSource != null && rawSource.length > 0 ? rawSource : null;
        return new PreparedRawOperation(mapper.writeValueAsBytes(Map.of(operationType.getValue(), meta)), body,
            operationType == OperationType.INDEX);
    }

    @SneakyThrows(IOException.class)
    public static EncodedOperation encodePreparedRaw(PreparedRawOperation prepared) {
        byte[] body = prepared.rawBody();
        if (body != null) {
            body = singleLineJson(SourceFormat.toJson(body));
        }
        return new EncodedOperation(prepared.action(), body, prepared.upsert());
    }

    /**
     * Validate before normalizing whitespace, preserving escapes and numeric spelling.
     *
     * @throws InvalidDocumentSourceException if the bytes are not valid JSON; only this document is affected
     */
    private static byte[] singleLineJson(byte[] bytes) {
        try (JsonParser parser = JSON_FACTORY.createParser(bytes)) {
            while (parser.nextToken() != null) {
                // Consume all tokens to verify structural validity without materializing the body.
            }
            if (parser.currentLocation().getLineNr() == 1) {
                return bytes;
            }
            // After validation, bytes below space can only be JSON whitespace (tab, CR, LF).
            // Copy them as spaces; unsigned conversion preserves multibyte UTF-8 unchanged.
            var singleLine = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                singleLine[i] = (byte) Math.max(' ', Byte.toUnsignedInt(bytes[i]));
            }
            return singleLine;
        } catch (IOException e) {
            throw new InvalidDocumentSourceException("Raw source bytes are not valid JSON", e);
        }
    }

    @SuppressWarnings({"java:S100", "java:S1186"})
    public record SerializedBulk(byte[] bytes, MigrationMetrics.Operations operations) {}

    /**
     * A bulk body assembled into a bounded reusable heap buffer. The returned view is valid until close.
     *
     * <p>This is used as compression input, so the large uncompressed buffer can return to the pool before the
     * asynchronous HTTP request starts.
     */
    public static final class PooledSerializedBulk implements AutoCloseable {
        private final byte[] buffer;
        private final int size;
        private final MigrationMetrics.Operations operations;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PooledSerializedBulk(byte[] buffer, int size, MigrationMetrics.Operations operations) {
            this.buffer = buffer;
            this.size = size;
            this.operations = operations;
        }

        public ByteBuffer[] buffers() {
            if (closed.get()) {
                throw new IllegalStateException("Pooled bulk buffer has been released");
            }
            return new ByteBuffer[] {
                ByteBuffer.wrap(buffer, 0, size).slice().asReadOnlyBuffer()
            };
        }

        public int size() {
            return size;
        }

        public MigrationMetrics.Operations operations() {
            return operations;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                BULK_BUFFER_POOL.release(buffer);
            }
        }
    }

    /** Bounded scratch-buffer pool; request payloads never outlive compression. */
    private static final class BulkBufferPool {
        private final Map<Integer, ConcurrentLinkedQueue<byte[]>> buffers = new ConcurrentHashMap<>();
        private final AtomicLong retainedBytes = new AtomicLong();

        byte[] acquire(int requestedBytes) {
            int capacity = capacityFor(requestedBytes);
            if (capacity == 0 || capacity > MAX_POOLED_BUFFER_BYTES) {
                return new byte[capacity];
            }
            var available = buffers.computeIfAbsent(capacity, ignored -> new ConcurrentLinkedQueue<>());
            var buffer = available.poll();
            if (buffer != null) {
                retainedBytes.addAndGet(-buffer.length);
                return buffer;
            }
            return new byte[capacity];
        }

        void release(byte[] buffer) {
            if (buffer.length == 0 || buffer.length > MAX_POOLED_BUFFER_BYTES) {
                return;
            }
            long retained;
            do {
                retained = retainedBytes.get();
                if (retained + buffer.length > MAX_RETAINED_POOLED_BYTES) {
                    return;
                }
            } while (!retainedBytes.compareAndSet(retained, retained + buffer.length));
            buffers.computeIfAbsent(buffer.length, ignored -> new ConcurrentLinkedQueue<>()).offer(buffer);
        }

        private static int capacityFor(int requestedBytes) {
            if (requestedBytes <= 0 || requestedBytes > MAX_POOLED_BUFFER_BYTES) {
                return requestedBytes;
            }
            return Math.multiplyExact(
                Math.ceilDiv(requestedBytes, POOLED_BUFFER_SIZE_CLASS_BYTES),
                POOLED_BUFFER_SIZE_CLASS_BYTES
            );
        }
    }

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

    /** Assemble into a reusable buffer, released by closing the returned value. */
    public static PooledSerializedBulk assemblePooled(List<EncodedOperation> operations) {
        return assemblePooled(operations, Function.identity());
    }

    /** Assemble prepared operations directly into a reusable buffer. */
    public static <T> PooledSerializedBulk assemblePooled(List<T> operations, Function<T, EncodedOperation> encoded) {
        long size = 0;
        for (var operation : operations) {
            size += encoded.apply(operation).size();
        }
        int requestedBytes = Math.toIntExact(size);
        var buffer = BULK_BUFFER_POOL.acquire(requestedBytes);
        long upserts = 0;
        long bodyBytes = 0;
        int offset = 0;
        try {
            for (var item : operations) {
                var operation = encoded.apply(item);
                System.arraycopy(operation.action(), 0, buffer, offset, operation.action().length);
                offset += operation.action().length;
                if (operation.body() != null) {
                    buffer[offset++] = '\n';
                    System.arraycopy(operation.body(), 0, buffer, offset, operation.body().length);
                    offset += operation.body().length;
                    bodyBytes += operation.body().length;
                }
                buffer[offset++] = '\n';
                if (operation.upsert()) {
                    upserts++;
                }
            }
            if (offset != requestedBytes) {
                throw new IllegalStateException(
                    "Assembled " + offset + " bytes after measuring " + requestedBytes
                );
            }
            return new PooledSerializedBulk(buffer, requestedBytes,
                new MigrationMetrics.Operations(upserts, operations.size() - upserts, bodyBytes));
        } catch (RuntimeException | Error e) {
            BULK_BUFFER_POOL.release(buffer);
            throw e;
        }
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
        encodePreparedRaw(prepareRaw(operationType, meta, rawSource, mapper)).writeWithoutFinalNewline(out);
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
