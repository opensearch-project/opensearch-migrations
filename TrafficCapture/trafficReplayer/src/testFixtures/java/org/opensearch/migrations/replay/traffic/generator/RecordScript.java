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

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.WriterPartitionId;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;
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
 *
 * <p>It emits {@link ApplicationKafkaRecord} — the production boundary type — so a scripted batch
 * drops straight into {@code ReplayIntakeInput.PartitionRecordBatch} with no adapter in between. A
 * fixture record type would let the fixture and production disagree about what crosses that
 * boundary, and the broker timestamp is exactly the field that disagreement would lose.
 *
 * <p>Association expectations and writer identity stay on the script rather than on the record,
 * because neither belongs on the production type: associations are test-supplied and
 * {@code PAYLOAD_NOT_SET} records have no envelope to read a writer from.
 *
 * <p>One script covers one partition generation, identified by {@code generationSequence}, across
 * however many partitions of {@code topic} the test scripts.
 */
public final class RecordScript {
    private final String topic;
    private final long generationSequence;
    private final List<ApplicationKafkaRecord> records = new ArrayList<>();
    private final Map<TopicPartition, Long> latestOffsetByPartition = new HashMap<>();
    private final Map<KafkaRecordId, Set<String>> associationsByRecord = new HashMap<>();
    private final Map<KafkaRecordId, WriterPartitionId> writerByRecord = new HashMap<>();
    private int cursor;

    public RecordScript(String topic) {
        this(topic, 0);
    }

    public RecordScript(String topic, long generationSequence) {
        if (Objects.requireNonNull(topic).isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (generationSequence < 0) {
            throw new IllegalArgumentException("generationSequence must not be negative");
        }
        this.topic = topic;
        this.generationSequence = generationSequence;
    }

    /** The generation every record scripted for {@code partition} belongs to. */
    public PartitionGenerationId generation(int partition) {
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative");
        }
        return new PartitionGenerationId(new TopicPartition(topic, partition), generationSequence);
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
        var generation = generation(partition);
        var topicPartition = generation.topicPartition();
        var previousOffset = latestOffsetByPartition.put(topicPartition, offset);
        if (previousOffset != null && offset <= previousOffset) {
            throw new IllegalArgumentException(
                "Offsets must increase within " + topicPartition + ": " + previousOffset + " then " + offset
            );
        }
        var id = new KafkaRecordId(generation, offset);
        if (associationsByRecord.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate scripted record " + id);
        }
        var associations = Set.copyOf(List.of(expectedAssociations));
        associationsByRecord.put(id, associations);
        writerByRecord.put(id, new WriterPartitionId(writerNodeId, topicPartition));
        records.add(new ApplicationKafkaRecord(
            id,
            logAppendTime.toEpochMilli(),
            envelope.getSerializedSize(),
            envelope
        ));
        return this;
    }

    public List<ApplicationKafkaRecord> records() {
        return List.copyOf(records);
    }

    public boolean hasNext() {
        return cursor < records.size();
    }

    public ApplicationKafkaRecord next() {
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

    /**
     * The writer the test scripted this record for. Read from the script rather than the envelope so
     * it is also available for a {@code PAYLOAD_NOT_SET} record, which has no writer field to read.
     */
    public WriterPartitionId writerOf(KafkaRecordId recordId) {
        var writer = writerByRecord.get(Objects.requireNonNull(recordId));
        if (writer == null) {
            throw new AssertionError("No scripted record " + recordId);
        }
        return writer;
    }

    /** The literal operation names supplied when this record was added to the script. */
    public Set<String> expectedAssociationsOf(KafkaRecordId recordId) {
        var expected = associationsByRecord.get(Objects.requireNonNull(recordId));
        if (expected == null) {
            throw new AssertionError("No association expectation for " + recordId);
        }
        return expected;
    }

    public void assertAssociations(KafkaRecordId recordId, Collection<String> actualAssociations) {
        var expected = expectedAssociationsOf(recordId);
        var actual = Set.copyOf(new LinkedHashSet<>(actualAssociations));
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "Association mismatch for " + recordId + ": expected=" + expected + ", actual=" + actual
            );
        }
    }
}
