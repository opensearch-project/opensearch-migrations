package org.opensearch.migrations.bulkload.pipeline;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchSizePredicateTest {
    @Test
    void preservesExactFitsAndEnforcesCountLimitsForZeroByteItems() {
        assertEquals(List.of(List.of(5L, 5L), List.of(0L, 0L), List.of(0L)),
            batches(List.of(5L, 5L, 0L, 0L, 0L), 2, 10));
    }

    @Test
    void isolatesOversizedItemsEvenWhenTheFollowingItemHasNoBytes() {
        assertEquals(List.of(List.of(11L), List.of(0L, 1L)),
            batches(List.of(11L, 0L, 1L), 10, 10));
    }

    @Test
    void doesNotOverflowAtLongMaxValue() {
        assertEquals(List.of(List.of(Long.MAX_VALUE - 1, 1L), List.of(1L)),
            batches(List.of(Long.MAX_VALUE - 1, 1L, 1L), 10, Long.MAX_VALUE));
        assertEquals(List.of(List.of(Long.MAX_VALUE), List.of(1L)),
            batches(List.of(Long.MAX_VALUE, 1L), 10, Long.MAX_VALUE - 1));
    }

    @Test
    void neverEmitsAnEmptyBatch() {
        assertEquals(List.of(), batches(List.of(), 1, 1));
        assertEquals(List.of(List.of(100L)), batches(List.of(100L), 1, 1));
    }

    @Test
    void varyingSizesPreserveEveryItemAndRespectBothLimits() {
        var sizes = new Random(0).longs(1000, 0, 150).boxed().toList();
        var batches = batches(sizes, 7, 100);
        assertEquals(sizes, batches.stream().flatMap(List::stream).toList());
        for (var batch : batches) {
            assertTrue(batch.size() <= 7);
            assertTrue(batch.size() == 1 || batch.stream().mapToLong(Long::longValue).sum() <= 100);
        }
    }

    private static List<List<Long>> batches(List<Long> sizes, int maxCount, long maxBytes) {
        return Flux.fromIterable(sizes)
            .bufferUntil(new BatchSizePredicate<>(maxCount, maxBytes, Long::longValue), true)
            .collectList().block();
    }
}
