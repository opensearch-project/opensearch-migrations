package org.opensearch.migrations.replay.kafka;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KafkaRecordOwnershipBudgetTest {
    @Test
    void recordLimitBlocksAndWakesOnlyAfterOwnershipIsReleased() {
        var metrics = new RecordingMetrics();
        var budget = new KafkaRecordOwnershipBudget(2, 100, metrics);
        var wakeups = new AtomicInteger();
        budget.setCapacityAvailableListener(wakeups::incrementAndGet);

        Assertions.assertTrue(budget.tryReserve(key(0, 1, 0), 10));
        Assertions.assertTrue(budget.tryReserve(key(0, 1, 1), 20));
        Assertions.assertFalse(budget.isCapacityAvailable());
        Assertions.assertFalse(budget.tryReserve(key(0, 1, 2), 30));
        Assertions.assertEquals(1, metrics.saturations);
        Assertions.assertEquals(
            new KafkaRecordOwnershipBudget.OwnershipSnapshot(2, 30, 2, 100, true),
            budget.snapshot()
        );

        budget.release(key(0, 1, 0));

        Assertions.assertTrue(budget.isCapacityAvailable());
        Assertions.assertEquals(1, wakeups.get());
        Assertions.assertEquals(1, metrics.records);
        Assertions.assertEquals(20, metrics.bytes);
    }

    @Test
    void byteLimitAndGenerationCleanupAreExact() {
        var metrics = new RecordingMetrics();
        var budget = new KafkaRecordOwnershipBudget(10, 20, metrics);

        Assertions.assertTrue(budget.tryReserve(key(0, 1, 0), 12));
        Assertions.assertFalse(budget.tryReserve(key(1, 1, 0), 9));
        budget.releasePartition(0, 2);
        Assertions.assertEquals(1, budget.snapshot().records());

        budget.releasePartition(0, 1);

        Assertions.assertEquals(
            new KafkaRecordOwnershipBudget.OwnershipSnapshot(0, 0, 10, 20, false),
            budget.snapshot()
        );
        Assertions.assertEquals(0, metrics.records);
        Assertions.assertEquals(0, metrics.bytes);
    }

    @Test
    void aSingleRecordLargerThanTheConfiguredMaximumFailsInsteadOfSpinning() {
        var budget = new KafkaRecordOwnershipBudget(10, 5, TrackingKafkaConsumer.Metrics.NO_OP);

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> budget.tryReserve(key(0, 1, 0), 6)
        );

        Assertions.assertTrue(failure.getMessage().contains("exceeding the configured ownership limit"));
        Assertions.assertEquals(0, budget.snapshot().records());
    }

    private static KafkaRecordOwnershipBudget.ReservationKey key(
        int partition,
        int generation,
        long offset
    ) {
        return new KafkaRecordOwnershipBudget.ReservationKey(partition, generation, offset);
    }

    private static final class RecordingMetrics implements TrackingKafkaConsumer.Metrics {
        private int records;
        private long bytes;
        private int saturations;

        @Override
        public void ownedRecordCapacityChanged(int recordDelta, long byteDelta) {
            records += recordDelta;
            bytes += byteDelta;
        }

        @Override
        public void ownedRecordBudgetSaturated() {
            saturations++;
        }
    }
}
