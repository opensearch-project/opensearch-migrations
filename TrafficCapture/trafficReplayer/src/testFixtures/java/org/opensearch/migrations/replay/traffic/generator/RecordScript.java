/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.traffic.generator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.opensearch.migrations.trafficcapture.protos.CaptureCapabilityProbe;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriterPartitionHeartbeat;

import org.apache.kafka.common.TopicPartition;

/**
 * Deterministic Kafka application-record script for replay tests.
 *
 * <p>Expected work associations are supplied literally by the test. The fixture never derives them
 * from source-assembly transitions, so they remain an independent oracle for record accounting.
 */
public final class RecordScript extends TrafficStreamGenerator {
    public record RecordId(TopicPartition topicPartition, long offset) {
        public RecordId {
            Objects.requireNonNull(topicPartition);
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }
    }

    public record ScriptedRecord(
        RecordId id,
        long logAppendTimeMillis,
        String writerNodeId,
        CaptureRecord envelope,
        Set<String> expectedAssociations
    ) {
        public ScriptedRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(writerNodeId);
            Objects.requireNonNull(envelope);
            expectedAssociations = Set.copyOf(expectedAssociations);
        }

        public CaptureRecord.PayloadCase payloadCase() {
            return envelope.getPayloadCase();
        }

        public byte[] value() {
            return envelope.toByteArray();
        }
    }

    private final String topic;
    private final List<ScriptedRecord> records = new ArrayList<>();
    private final Map<TopicPartition, Long> latestOffsetByPartition = new HashMap<>();
    private final Map<RecordId, Set<String>> associationsByRecord = new HashMap<>();
    private int cursor;

    public RecordScript(String topic) {
        if (Objects.requireNonNull(topic).isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        this.topic = topic;
    }

    public RecordScript addTraffic(
        int partition,
        long offset,
        Instant logAppendTime,
        String writerNodeId,
        TrafficStream trafficStream,
        String... expectedAssociations
    ) {
        Objects.requireNonNull(trafficStream);
        var normalizedStream = trafficStream.toBuilder().setNodeId(writerNodeId).build();
        return add(
            partition,
            offset,
            logAppendTime,
            writerNodeId,
            CaptureRecord.newBuilder().setTrafficStream(normalizedStream).build(),
            expectedAssociations
        );
    }

    public RecordScript addHeartbeat(
        int partition,
        long offset,
        Instant logAppendTime,
        String writerNodeId,
        long heartbeatIntervalMillis,
        String... expectedAssociations
    ) {
        var heartbeat = WriterPartitionHeartbeat.newBuilder()
            .setWriterNodeId(writerNodeId)
            .setHeartbeatIntervalMillis(heartbeatIntervalMillis)
            .setEmittedAtMillis(logAppendTime.toEpochMilli())
            .build();
        return add(
            partition,
            offset,
            logAppendTime,
            writerNodeId,
            CaptureRecord.newBuilder().setWriterPartitionHeartbeat(heartbeat).build(),
            expectedAssociations
        );
    }

    public RecordScript addProbe(
        int partition,
        long offset,
        Instant logAppendTime,
        String writerNodeId,
        String probeId,
        String... expectedAssociations
    ) {
        var probe = CaptureCapabilityProbe.newBuilder()
            .setWriterNodeId(writerNodeId)
            .setProbeId(probeId)
            .build();
        return add(
            partition,
            offset,
            logAppendTime,
            writerNodeId,
            CaptureRecord.newBuilder().setCaptureCapabilityProbe(probe).build(),
            expectedAssociations
        );
    }

    public RecordScript addPayloadNotSet(
        int partition,
        long offset,
        Instant logAppendTime,
        String diagnosticWriterNodeId,
        String... expectedAssociations
    ) {
        return add(
            partition,
            offset,
            logAppendTime,
            diagnosticWriterNodeId,
            CaptureRecord.getDefaultInstance(),
            expectedAssociations
        );
    }

    private RecordScript add(
        int partition,
        long offset,
        Instant logAppendTime,
        String writerNodeId,
        CaptureRecord envelope,
        String... expectedAssociations
    ) {
        Objects.requireNonNull(logAppendTime);
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative");
        }
        if (Objects.requireNonNull(writerNodeId).isBlank()) {
            throw new IllegalArgumentException("writerNodeId must not be blank");
        }
        var topicPartition = new TopicPartition(topic, partition);
        var previousOffset = latestOffsetByPartition.put(topicPartition, offset);
        if (previousOffset != null && offset <= previousOffset) {
            throw new IllegalArgumentException(
                "Offsets must increase within " + topicPartition + ": " + previousOffset + " then " + offset
            );
        }
        var id = new RecordId(topicPartition, offset);
        if (associationsByRecord.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate scripted record " + id);
        }
        var associations = Set.copyOf(List.of(expectedAssociations));
        associationsByRecord.put(id, associations);
        records.add(new ScriptedRecord(
            id,
            logAppendTime.toEpochMilli(),
            writerNodeId,
            envelope,
            associations
        ));
        return this;
    }

    public List<ScriptedRecord> records() {
        return List.copyOf(records);
    }

    public boolean hasNext() {
        return cursor < records.size();
    }

    public ScriptedRecord next() {
        if (!hasNext()) {
            throw new AssertionError("RecordScript exhausted after " + cursor + " records");
        }
        return records.get(cursor++);
    }

    public void assertExhausted() {
        if (hasNext()) {
            throw new AssertionError(
                "RecordScript has " + (records.size() - cursor) + " unconsumed records"
            );
        }
    }

    public void assertAssociations(RecordId recordId, Collection<String> actualAssociations) {
        var expected = associationsByRecord.get(Objects.requireNonNull(recordId));
        if (expected == null) {
            throw new AssertionError("No association expectation for " + recordId);
        }
        var actual = Set.copyOf(new LinkedHashSet<>(actualAssociations));
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "Association mismatch for " + recordId + ": expected=" + expected + ", actual=" + actual
            );
        }
    }
}
