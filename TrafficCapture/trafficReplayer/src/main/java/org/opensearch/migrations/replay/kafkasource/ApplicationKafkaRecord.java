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
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

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
 * <p>The encoded envelope is the protocol value, decoded and exhaustively switched on by intake per
 * {@code kafkaLLD §7.1}: {@code TrafficStream}, {@code WriterPartitionHeartbeat},
 * {@code CaptureCapabilityProbe}, and {@code PAYLOAD_NOT_SET} as a protocol violation. It is deliberately the
 * still-encoded Kafka value rather than a pre-interpreted union, so both protobuf decoding and the payload
 * switch happen once, at intake, where the design places them.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 5.5.</p>
 */
public record ApplicationKafkaRecord(
    KafkaRecordId recordId,
    long logAppendTimeMillis,
    int serializedSizeBytes,
    ByteString encodedEnvelope,
    IReplayContexts.IKafkaRecordContext replayContext
) {
    public ApplicationKafkaRecord(
        KafkaRecordId recordId,
        long logAppendTimeMillis,
        int serializedSizeBytes,
        CaptureRecord envelope
    ) {
        this(
            recordId,
            logAppendTimeMillis,
            serializedSizeBytes,
            Objects.requireNonNull(envelope, "envelope").toByteString(),
            null
        );
    }

    public ApplicationKafkaRecord(
        KafkaRecordId recordId,
        long logAppendTimeMillis,
        int serializedSizeBytes,
        CaptureRecord envelope,
        IReplayContexts.IKafkaRecordContext replayContext
    ) {
        this(
            recordId,
            logAppendTimeMillis,
            serializedSizeBytes,
            Objects.requireNonNull(envelope, "envelope").toByteString(),
            replayContext
        );
    }

    public ApplicationKafkaRecord {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(encodedEnvelope, "encodedEnvelope");
        if (serializedSizeBytes < 0) {
            throw new IllegalArgumentException("serializedSizeBytes must not be negative: " + serializedSizeBytes);
        }
        // No bound on logAppendTimeMillis. A broker timestamp is whatever the broker recorded; validating it
        // here would duplicate -- and could silently pre-empt -- the backward-skew check that kafkaLLD 10.1
        // makes process-fatal, which must be evaluated against the partition's greatest observed value rather
        // than against any constant.
    }

    public CaptureRecord decodeEnvelope() throws InvalidProtocolBufferException {
        return CaptureRecord.parseFrom(encodedEnvelope);
    }

    /**
     * Convenience for code that runs only after replay intake has validated the encoded value.
     */
    public CaptureRecord envelope() {
        try {
            return decodeEnvelope();
        } catch (InvalidProtocolBufferException notAnEnvelope) {
            throw new IllegalStateException(recordId + " is not a CaptureRecord envelope", notAnEnvelope);
        }
    }

    @Override
    public String toString() {
        try {
            return recordId + "{" + decodeEnvelope().getPayloadCase()
                + " logAppendTime=" + logAppendTimeMillis + "}";
        } catch (InvalidProtocolBufferException notAnEnvelope) {
            return recordId + "{UNPARSEABLE logAppendTime=" + logAppendTimeMillis + "}";
        }
    }
}
