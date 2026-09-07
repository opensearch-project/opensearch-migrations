package org.opensearch.migrations.replay.kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Owns the hard record and byte limits for Kafka records accepted by this consumer generation.
 */
final class KafkaRecordOwnershipBudget {
    @Value
    @Accessors(fluent = true)
    static class ReservationKey {
        int partition;
        int generation;
        long offset;
    }

    @Value
    @Accessors(fluent = true)
    static class OwnershipSnapshot {
        int records;
        long bytes;
        int maximumRecords;
        long maximumBytes;
        boolean saturated;
    }

    private final int maximumRecords;
    private final long maximumBytes;
    private final TrackingKafkaConsumer.Metrics metrics;
    private final Map<ReservationKey, Long> reservations = new HashMap<>();
    private long ownedBytes;
    private boolean saturated;
    private Runnable capacityAvailableListener = () -> {};

    KafkaRecordOwnershipBudget(
        int maximumRecords,
        long maximumBytes,
        @NonNull TrackingKafkaConsumer.Metrics metrics
    ) {
        if (maximumRecords <= 0) {
            throw new IllegalArgumentException("maximumRecords must be positive");
        }
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumRecords = maximumRecords;
        this.maximumBytes = maximumBytes;
        this.metrics = metrics;
    }

    synchronized boolean tryReserve(ReservationKey key, long bytes) {
        Objects.requireNonNull(key);
        if (bytes < 0) {
            throw new IllegalArgumentException("record bytes must not be negative");
        }
        if (bytes > maximumBytes) {
            throw new IllegalStateException(
                "Kafka record "
                    + key
                    + " requires "
                    + bytes
                    + " bytes, exceeding the configured ownership limit of "
                    + maximumBytes
            );
        }
        if (reservations.containsKey(key)) {
            throw new IllegalStateException("Kafka record ownership was already reserved for " + key);
        }
        if (reservations.size() >= maximumRecords || bytes > maximumBytes - ownedBytes) {
            markSaturated();
            return false;
        }
        reservations.put(key, bytes);
        ownedBytes += bytes;
        metrics.ownedRecordCapacityChanged(1, bytes);
        if (reservations.size() >= maximumRecords || ownedBytes >= maximumBytes) {
            markSaturated();
        }
        return true;
    }

    void release(ReservationKey key) {
        Runnable listener = null;
        synchronized (this) {
            var bytes = reservations.remove(key);
            if (bytes == null) {
                return;
            }
            ownedBytes -= bytes;
            metrics.ownedRecordCapacityChanged(-1, -bytes);
            listener = clearSaturationIfCapacityAvailable();
        }
        runListener(listener);
    }

    void releasePartition(int partition, int generation) {
        Runnable listener = null;
        synchronized (this) {
            int releasedRecords = 0;
            long releasedBytes = 0;
            var iterator = reservations.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().partition() == partition
                    && entry.getKey().generation() == generation) {
                    releasedRecords++;
                    releasedBytes += entry.getValue();
                    iterator.remove();
                }
            }
            if (releasedRecords == 0) {
                return;
            }
            ownedBytes -= releasedBytes;
            metrics.ownedRecordCapacityChanged(-releasedRecords, -releasedBytes);
            listener = clearSaturationIfCapacityAvailable();
        }
        runListener(listener);
    }

    void clear() {
        Runnable listener = null;
        synchronized (this) {
            if (!reservations.isEmpty()) {
                metrics.ownedRecordCapacityChanged(-reservations.size(), -ownedBytes);
                reservations.clear();
                ownedBytes = 0;
            }
            listener = clearSaturationIfCapacityAvailable();
        }
        runListener(listener);
    }

    synchronized boolean isCapacityAvailable() {
        return !saturated
            && reservations.size() < maximumRecords
            && ownedBytes < maximumBytes;
    }

    synchronized OwnershipSnapshot snapshot() {
        return new OwnershipSnapshot(
            reservations.size(),
            ownedBytes,
            maximumRecords,
            maximumBytes,
            saturated
        );
    }

    synchronized void setCapacityAvailableListener(@NonNull Runnable listener) {
        capacityAvailableListener = listener;
    }

    private void markSaturated() {
        if (!saturated) {
            saturated = true;
            metrics.ownedRecordBudgetSaturated();
        }
    }

    private Runnable clearSaturationIfCapacityAvailable() {
        if (!saturated
            || reservations.size() >= maximumRecords
            || ownedBytes >= maximumBytes) {
            return null;
        }
        saturated = false;
        return capacityAvailableListener;
    }

    private static void runListener(Runnable listener) {
        if (listener != null) {
            listener.run();
        }
    }
}
