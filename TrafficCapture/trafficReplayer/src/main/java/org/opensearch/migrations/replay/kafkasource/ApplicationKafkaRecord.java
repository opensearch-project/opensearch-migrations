/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.Objects;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

/**
 * One Kafka application record as the Kafka source observed it, carried to replay intake.
 *
 * <p>{@code logAppendTimeMillis} is the broker's {@code LogAppendTime} for this record, and carrying it here
 * is the point of the type. In the pre-rebuild implementation
 * {@code ConsumerRecord.timestamp()} was read in exactly one place — a dump-mode {@code --end-time} filter —
 * and never reached intake at all, which is why broker-time expiration, the backward-skew fatal check, and
 * heartbeat baselines could not exist and expiration ran on a wall clock instead. Any record type crossing
 * this boundary without the broker timestamp reintroduces that defect.</p>
 *
 * <p>The envelope is the protocol value, exhaustively switched on by intake per
 * {@code kafkaLLD §7.1}: {@code TrafficStream}, {@code WriterPartitionHeartbeat},
 * {@code CaptureCapabilityProbe}, and {@code PAYLOAD_NOT_SET} as a protocol violation. It is deliberately the
 * decoded {@code CaptureRecord} rather than a pre-interpreted union, so the switch happens once, at intake,
 * where the design places it.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 5.5.</p>
 */
public record ApplicationKafkaRecord(
    KafkaRecordId recordId,
    long logAppendTimeMillis,
    int serializedSizeBytes,
    CaptureRecord envelope
) {
    public ApplicationKafkaRecord {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(envelope, "envelope");
        if (serializedSizeBytes < 0) {
            throw new IllegalArgumentException("serializedSizeBytes must not be negative: " + serializedSizeBytes);
        }
        // No bound on logAppendTimeMillis. A broker timestamp is whatever the broker recorded; validating it
        // here would duplicate -- and could silently pre-empt -- the backward-skew check that kafkaLLD 10.1
        // makes process-fatal, which must be evaluated against the partition's greatest observed value rather
        // than against any constant.
    }

    @Override
    public String toString() {
        return recordId + "{" + envelope.getPayloadCase() + " logAppendTime=" + logAppendTimeMillis + "}";
    }
}
