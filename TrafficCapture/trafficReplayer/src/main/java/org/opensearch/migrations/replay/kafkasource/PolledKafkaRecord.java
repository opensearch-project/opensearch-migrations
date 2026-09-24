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

/**
 * One record as Kafka returned it, before any process-local identity is attached.
 *
 * <p>This exists so that {@link KafkaSourcePort} never needs to know a partition generation.
 * {@code kafkaLLD §5} makes {@code KafkaSourceOwner} the sole authority on generations, and the adapter
 * previously took a {@code Map<TopicPartition, PartitionGenerationId>} in order to stamp records itself — a
 * second mutable copy of state the owner already holds, which nothing populated and which
 * {@code AGENTS.md §6} forbids as a second correctness model. The owner stamps instead, turning each of these
 * into an {@link ApplicationKafkaRecord} under the generation it knows is current.
 *
 * <p>Carries the broker timestamp and serialized size because those come from Kafka metadata and cannot be
 * recovered later; everything else intake needs is inside the envelope.
 */
public record PolledKafkaRecord(
    long offset,
    long logAppendTimeMillis,
    int serializedSizeBytes,
    CaptureRecord envelope
) {
    public PolledKafkaRecord {
        Objects.requireNonNull(envelope, "envelope");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        if (serializedSizeBytes < 0) {
            throw new IllegalArgumentException("serializedSizeBytes must not be negative: " + serializedSizeBytes);
        }
    }
}
