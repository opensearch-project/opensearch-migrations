package org.opensearch.migrations.bulkload.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RfsMemorySizingTest {

    @Test
    void calculatesIndependentSourceAndTransformPermitPools() {
        assertEquals(1_200, RfsMemorySizing.sourcePermitBytes(12));
        assertEquals(80, RfsMemorySizing.transformPermitBytes(20, 10, 4));
        assertEquals(
            RfsMemorySizing.BASE_HEAP_BYTES + 8L * (1_200 + 80),
            RfsMemorySizing.requiredHeapBytes(12, 20, 10, 4)
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
        assertThrows(ArithmeticException.class, () -> RfsMemorySizing.sourcePermitBytes(Long.MAX_VALUE));
    }
}
