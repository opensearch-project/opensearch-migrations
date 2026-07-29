package org.opensearch.migrations;

import org.opensearch.migrations.bulkload.worker.WorkItemCursor;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Covers the emitted-document total that is stamped onto a successor work item so a shard's
 * live non-nested document count survives lease handoffs.
 *
 * <p>The invariant under test: successors restart AT the checkpoint position rather than
 * after it (see {@code RfsMigrateDocuments.getSuccessorWorkItemIds}, which deliberately
 * re-processes the last checkpoint to handle 1:many document splits). The checkpoint batch is
 * therefore re-emitted by the successor, and must be excluded from the carried total or the
 * shard count grows on every lease expiry.
 */
class DocsEmittedForSuccessorTest {

    @Test
    void checkpointBatchIsExcludedSoItIsNotCountedTwice() {
        // 100 docs emitted, the last batch of 10 produced the checkpoint.
        var cursor = new WorkItemCursor(4_242L, 100L, 10L);
        Assertions.assertEquals(90L, RfsMigrateDocuments.docsEmittedForSuccessor(cursor),
            "the successor re-emits the checkpoint batch, so carry only the docs before it");
    }

    @Test
    void countIsExactAcrossThreeLeaseGenerations() {
        // Simulate three generations over a shard of 30 documents in batches of 10, where the
        // lease expires after each batch. Each generation replays its inherited checkpoint
        // batch, so a naive sum would report 30 + replays instead of 30.
        long carried = 0;

        // Generation 1: emits docs 1-10. cumulative = 10, checkpoint batch = 10.
        carried = RfsMigrateDocuments.docsEmittedForSuccessor(new WorkItemCursor(10L, carried + 10L, 10L));
        Assertions.assertEquals(0L, carried, "gen1's only batch is replayed by gen2");

        // Generation 2: replays 1-10 then emits 11-20. cumulative = carried + 20.
        carried = RfsMigrateDocuments.docsEmittedForSuccessor(new WorkItemCursor(20L, carried + 20L, 10L));
        Assertions.assertEquals(10L, carried);

        // Generation 3: replays 11-20 then emits 21-30, and completes the shard.
        long finalTotal = carried + 20L;
        Assertions.assertEquals(30L, finalTotal,
            "final shard total equals the true document count, not the count plus replays");
    }

    @Test
    void positionOnlyCursorReportsNoCount() {
        // Callers that don't track totals use the single-arg constructor; 0 means "unknown",
        // which is also what an absent coordinator-doc field reads as.
        var cursor = new WorkItemCursor(99L);
        Assertions.assertEquals(0L, cursor.getDocsEmitted());
        Assertions.assertEquals(0L, cursor.getDocsInCheckpointBatch());
        Assertions.assertEquals(0L, RfsMigrateDocuments.docsEmittedForSuccessor(cursor));
    }

    @ParameterizedTest
    @CsvSource({
        // docsEmitted, docsInCheckpointBatch, expectedCarried
        "0,    0,   0",
        "1,    1,   0",
        "10,   1,   9",
        "10,   10,  0",
        // Defensive: a batch larger than the running total must not produce a negative
        // carried value, which would corrupt the successor's arithmetic.
        "5,    10,  0",
    })
    void carriedTotalIsNeverNegative(long emitted, long batch, long expected) {
        Assertions.assertEquals(expected,
            RfsMigrateDocuments.docsEmittedForSuccessor(new WorkItemCursor(0L, emitted, batch)));
    }

    @Test
    void cursorRetainsSeekPositionSeparatelyFromCounts() {
        // The whole point of the split: the seek position is sparse (Lucene doc ids skip over
        // nested children) while the counts are dense.
        var cursor = new WorkItemCursor(9_999L, 42L, 7L);
        Assertions.assertEquals(9_999L, cursor.getProgressCheckpointNum(),
            "seek position is independent of the document counts");
        Assertions.assertEquals(42L, cursor.getDocsEmitted());
        Assertions.assertEquals(7L, cursor.getDocsInCheckpointBatch());
    }
}
