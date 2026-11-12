package org.opensearch.migrations.bulkload.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RfsMemorySizingTest {

    @Test
    void calculatesIndependentSourceAndTransformPermitPools() {
        assertEquals(3, RfsMemorySizing.readerParallelism(10, 3));
        assertEquals(60, RfsMemorySizing.attainableBulkBytes(12, 5, 100));
        assertEquals(636, RfsMemorySizing.sourcePermitBytes(12, 5, 100, 10, 3));
        assertEquals(80, RfsMemorySizing.transformPermitBytes(20, 10, 4));
        assertEquals(
            RfsMemorySizing.BASE_HEAP_BYTES + 8L * (1_048 + 80),
            RfsMemorySizing.requiredHeapBytes(12, 20, 10, 100, 10, 4)
        );
    }

    @Test
    void retainsAFullRequestWindowInAdditionToActiveReads() {
        assertEquals(
            4L * 100_000 + 10L * 10_000_000,
            RfsMemorySizing.sourcePermitBytes(100_000, 100, 10_000_000, 10, 4)
        );
    }

    @Test
    void capsTransformParallelismByConnectionsAndProcessors() {
        assertEquals(3, RfsMemorySizing.transformParallelism(3, 8));
        assertEquals(4, RfsMemorySizing.transformParallelism(10, 4));
        assertEquals(1, RfsMemorySizing.transformParallelism(10, 0));
    }

    @Test
    void rejectsOverflowingSerializedByteDeclarations() {
        assertThrows(ArithmeticException.class,
            () -> RfsMemorySizing.sourcePermitBytes(Long.MAX_VALUE, 1, Long.MAX_VALUE, 10, 4));
    }
}
