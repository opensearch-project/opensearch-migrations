/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.identity;

import java.util.Objects;

import org.apache.kafka.common.TopicPartition;

/**
 * One process-local uninterrupted ownership period for one Kafka topic partition.
 *
 * <p>{@code localSequence} is allocated by this replayer each time Kafka assigns that partition locally.
 * It is <strong>not</strong> Kafka's group generation and is never serialized.</p>
 *
 * <p>This is the identity that makes a reread distinguishable from a first read. When ownership is lost and
 * a later generation rereads the same offsets, those are different {@link KafkaRecordId}s because the
 * generation differs, which is what lets record accounting stay exact across a rebalance.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2.</p>
 */
public record PartitionGenerationId(TopicPartition topicPartition, long localSequence) {
    public PartitionGenerationId {
        Objects.requireNonNull(topicPartition, "topicPartition");
        if (localSequence < 0) {
            throw new IllegalArgumentException("localSequence must not be negative: " + localSequence);
        }
    }

    @Override
    public String toString() {
        return topicPartition + "#" + localSequence;
    }
}
