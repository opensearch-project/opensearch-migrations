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
 * One protocol {@code writerNodeId} paired with the Kafka partition containing its records.
 *
 * <p>This is the key for broker-time state. Heartbeat baselines and expiration evidence are per writer
 * <em>and</em> partition, not per writer alone, because one writer's records reach several partitions and
 * each partition carries its own {@code LogAppendTime} sequence.</p>
 *
 * <p>Note that it deliberately carries a {@link TopicPartition} rather than a {@link PartitionGenerationId}:
 * a writer's broker-time baseline is a property of the partition's log, which outlives any single local
 * ownership period, so it survives a revocation and reassignment that changes the generation.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2.</p>
 */
public record WriterPartitionId(String writerNodeId, TopicPartition topicPartition) {
    public WriterPartitionId {
        Objects.requireNonNull(writerNodeId, "writerNodeId");
        Objects.requireNonNull(topicPartition, "topicPartition");
        if (writerNodeId.isEmpty()) {
            throw new IllegalArgumentException("writerNodeId must not be empty");
        }
    }

    @Override
    public String toString() {
        return writerNodeId + "/" + topicPartition;
    }
}
