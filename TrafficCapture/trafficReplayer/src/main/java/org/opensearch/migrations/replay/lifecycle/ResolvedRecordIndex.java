package org.opensearch.migrations.replay.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.TrafficStreamRecordId;

import lombok.NonNull;

/**
 * Exact duplicate index for successfully disposed records.
 *
 * <p>Ordered source identities are stored as merged ranges so long-lived generations do not retain
 * one object per record. Identities without a sequence number remain exact entries until their
 * source generation retires.
 */
final class ResolvedRecordIndex {
    private final Map<SourcePartitionKey, OffsetRanges> kafkaOffsets = new LinkedHashMap<>();
    private final Map<TrafficStreamIdentity, PartitionedRanges> trafficStreamIndexes =
        new LinkedHashMap<>();
    private final Map<RecordId, SourcePartitionKey> exactRecords = new LinkedHashMap<>();

    boolean contains(RecordId id) {
        if (id instanceof KafkaRecordId kafka) {
            var ranges = kafkaOffsets.get(sourcePartition(kafka));
            return ranges != null && ranges.contains(kafka.offset());
        }
        if (id instanceof TrafficStreamRecordId trafficStream) {
            var ranges = trafficStreamIndexes.get(TrafficStreamIdentity.from(trafficStream));
            return ranges != null && ranges.ranges().contains(trafficStream.trafficStreamIndex());
        }
        return exactRecords.containsKey(id);
    }

    void add(RecordId id, SourcePartitionKey partition) {
        if (id instanceof KafkaRecordId kafka) {
            kafkaOffsets.computeIfAbsent(partition, ignored -> new OffsetRanges()).add(kafka.offset());
        } else if (id instanceof TrafficStreamRecordId trafficStream) {
            addTrafficStream(trafficStream, partition);
        } else {
            exactRecords.put(id, partition);
        }
    }

    long retire(SourcePartitionKey partition) {
        var ranges = kafkaOffsets.remove(partition);
        long removed = ranges == null ? 0 : ranges.size();
        var trafficIterator = trafficStreamIndexes.entrySet().iterator();
        while (trafficIterator.hasNext()) {
            var entry = trafficIterator.next();
            if (entry.getValue().partition().equals(partition)) {
                removed += entry.getValue().ranges().size();
                trafficIterator.remove();
            }
        }
        var before = exactRecords.size();
        exactRecords.entrySet().removeIf(entry -> entry.getValue().equals(partition));
        return removed + before - exactRecords.size();
    }

    long size() {
        return kafkaOffsets.values().stream().mapToLong(OffsetRanges::size).sum()
            + trafficStreamIndexes.values().stream()
                .map(PartitionedRanges::ranges)
                .mapToLong(OffsetRanges::size)
                .sum()
            + exactRecords.size();
    }

    int indexEntries() {
        return kafkaOffsets.values().stream().mapToInt(OffsetRanges::rangeCount).sum()
            + trafficStreamIndexes.values().stream()
                .map(PartitionedRanges::ranges)
                .mapToInt(OffsetRanges::rangeCount)
                .sum()
            + exactRecords.size();
    }

    private void addTrafficStream(TrafficStreamRecordId record, SourcePartitionKey partition) {
        var ranges = trafficStreamIndexes.computeIfAbsent(
            TrafficStreamIdentity.from(record),
            ignored -> new PartitionedRanges(partition, new OffsetRanges())
        );
        if (!ranges.partition().equals(partition)) {
            throw new IllegalStateException(
                "traffic-stream identity moved between source partitions: record="
                    + record
                    + ", existing="
                    + ranges.partition()
                    + ", incoming="
                    + partition
            );
        }
        ranges.ranges().add(record.trafficStreamIndex());
    }

    private static SourcePartitionKey sourcePartition(KafkaRecordId record) {
        return new SourcePartitionKey(
            record.topic(),
            record.partition(),
            record.sourceGeneration()
        );
    }

    private record TrafficStreamIdentity(
        @NonNull SourceConnectionKey connection,
        int sourceGeneration
    ) {
        private static TrafficStreamIdentity from(TrafficStreamRecordId record) {
            return new TrafficStreamIdentity(record.connection(), record.sourceGeneration());
        }
    }

    private record PartitionedRanges(
        @NonNull SourcePartitionKey partition,
        @NonNull OffsetRanges ranges
    ) {}

    private static final class OffsetRanges {
        private final TreeMap<Long, Long> ranges = new TreeMap<>();
        private long size;

        private boolean contains(long offset) {
            var floor = ranges.floorEntry(offset);
            return floor != null && floor.getValue() >= offset;
        }

        private void add(long offset) {
            var lower = ranges.floorEntry(offset);
            if (lower != null && lower.getValue() >= offset) {
                return;
            }
            var higher = ranges.ceilingEntry(offset);
            var joinsLower = lower != null && lower.getValue() != Long.MAX_VALUE
                && lower.getValue() + 1 == offset;
            var joinsHigher = higher != null && offset != Long.MAX_VALUE
                && offset + 1 == higher.getKey();
            if (joinsLower && joinsHigher) {
                ranges.put(lower.getKey(), higher.getValue());
                ranges.remove(higher.getKey());
            } else if (joinsLower) {
                ranges.put(lower.getKey(), offset);
            } else if (joinsHigher) {
                ranges.remove(higher.getKey());
                ranges.put(offset, higher.getValue());
            } else {
                ranges.put(offset, offset);
            }
            ++size;
        }

        private long size() {
            return size;
        }

        private int rangeCount() {
            return ranges.size();
        }
    }
}
