/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.Objects;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * One record as Kafka returned it, before any process-local identity is attached.
 *
 * <p>This exists so that {@link KafkaSourcePort} never needs to know a partition generation.
 * {@code kafkaLLD §5} makes {@code KafkaSourceOwner} the sole authority on generations, and the adapter
 * previously took a {@code Map<TopicPartition, PartitionGenerationId>} in order to stamp records itself — a
 * second mutable copy of state the owner already holds and which nothing populated. The owner stamps instead,
 * turning each of these into an {@link ApplicationKafkaRecord} under the generation it knows is current.
 *
 * <p>Carries the broker timestamp and serialized size because those come from Kafka metadata and cannot be
 * recovered later; everything else intake needs is inside the still-encoded envelope.
 */
public record PolledKafkaRecord(
    long offset,
    long logAppendTimeMillis,
    int serializedSizeBytes,
    ByteString encodedEnvelope
) {
    public PolledKafkaRecord(
        long offset,
        long logAppendTimeMillis,
        int serializedSizeBytes,
        CaptureRecord envelope
    ) {
        this(
            offset,
            logAppendTimeMillis,
            serializedSizeBytes,
            Objects.requireNonNull(envelope, "envelope").toByteString()
        );
    }

    public PolledKafkaRecord {
        Objects.requireNonNull(encodedEnvelope, "encodedEnvelope");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        if (serializedSizeBytes < 0) {
            throw new IllegalArgumentException("serializedSizeBytes must not be negative: " + serializedSizeBytes);
        }
    }

    public CaptureRecord decodeEnvelope() throws InvalidProtocolBufferException {
        return CaptureRecord.parseFrom(encodedEnvelope);
    }

    public CaptureRecord envelope() {
        try {
            return decodeEnvelope();
        } catch (InvalidProtocolBufferException notAnEnvelope) {
            throw new IllegalStateException("Kafka value is not a CaptureRecord envelope", notAnEnvelope);
        }
    }
}
