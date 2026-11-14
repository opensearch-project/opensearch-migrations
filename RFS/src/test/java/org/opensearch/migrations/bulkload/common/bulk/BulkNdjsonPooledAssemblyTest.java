package org.opensearch.migrations.bulkload.common.bulk;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BulkNdjsonPooledAssemblyTest {
    @Test
    void pooledAssemblyReturnsAnExactViewAndRejectsUseAfterRelease() throws Exception {
        var first = new BulkNdjson.EncodedOperation(
            "{\"index\":{\"_id\":\"1\"}}".getBytes(StandardCharsets.UTF_8),
            "{\"value\":1}".getBytes(StandardCharsets.UTF_8),
            true
        );
        var second = new BulkNdjson.EncodedOperation(
            "{\"delete\":{\"_id\":\"2\"}}".getBytes(StandardCharsets.UTF_8),
            null,
            false
        );
        var expected = BulkNdjson.assemble(List.of(first, second));

        // Reflection lets this behavioral test run against the pre-change code, where the API is absent.
        var assemblePooled = BulkNdjson.class.getMethod("assemblePooled", List.class);
        var pooled = assemblePooled.invoke(null, List.of(first, second));
        assertInstanceOf(AutoCloseable.class, pooled);
        var buffersMethod = pooled.getClass().getMethod("buffers");
        var buffers = assertInstanceOf(ByteBuffer[].class, buffersMethod.invoke(pooled));
        var actual = new byte[expected.bytes().length];
        int offset = 0;
        for (var bytes : buffers) {
            int length = bytes.remaining();
            bytes.get(actual, offset, length);
            offset += length;
        }
        assertEquals(ByteBuffer.wrap(expected.bytes()), ByteBuffer.wrap(actual));

        ((AutoCloseable) pooled).close();
        var released = assertThrows(InvocationTargetException.class, () -> buffersMethod.invoke(pooled));
        assertInstanceOf(IllegalStateException.class, released.getCause());
    }
}
