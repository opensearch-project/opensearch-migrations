package org.opensearch.migrations.bulkload.pipeline;


import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MigrationPipeline.BatchPredicate}.
 */
class BatchPredicateTest {

    @Test
    void batchesByDocCount() {
        var predicate = new MigrationPipeline.BatchPredicate(3, Long.MAX_VALUE);

        assertFalse(predicate.test(doc("d1", 10)));
        assertFalse(predicate.test(doc("d2", 10)));
        assertTrue(predicate.test(doc("d3", 10)), "3rd doc should trigger batch boundary");
        // After reset, next batch starts
        assertFalse(predicate.test(doc("d4", 10)));
    }

    @Test
    void batchesByByteSize() {
        var predicate = new MigrationPipeline.BatchPredicate(Integer.MAX_VALUE, 50);

        assertFalse(predicate.test(doc("d1", 20)));
        assertFalse(predicate.test(doc("d2", 20)));
        assertTrue(predicate.test(doc("d3", 20)), "60 bytes >= 50 limit, should trigger");
    }

    @Test
    void nullSourceCountsAsZeroBytes() {
        var predicate = new MigrationPipeline.BatchPredicate(Integer.MAX_VALUE, 100);

        var deleteDoc = new DocumentChange("d1", null, null, null, DocumentChange.ChangeType.DELETE);
        assertFalse(predicate.test(deleteDoc), "null source = 0 bytes");
    }

    @Test
    void resetsAfterBatchBoundary() {
        var predicate = new MigrationPipeline.BatchPredicate(2, Long.MAX_VALUE);

        assertFalse(predicate.test(doc("d1", 10)));
        assertTrue(predicate.test(doc("d2", 10)));
        // After boundary, counters reset
        assertFalse(predicate.test(doc("d3", 10)));
        assertTrue(predicate.test(doc("d4", 10)));
    }

    @Test
    void singleDocBatch() {
        var predicate = new MigrationPipeline.BatchPredicate(1, Long.MAX_VALUE);

        assertTrue(predicate.test(doc("d1", 10)), "Every doc is a batch boundary");
        assertTrue(predicate.test(doc("d2", 10)));
    }

    private static DocumentChange doc(String id, int sourceSize) {
        byte[] source = new byte[sourceSize];
        return new DocumentChange(id, null, source, null, DocumentChange.ChangeType.INDEX);
    }
}
