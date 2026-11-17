/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.identity;

import lombok.NonNull;
import org.apache.kafka.common.TopicPartition;

/**
 * Identifies one capture writer's stream within one Kafka partition.
 *
 * <p>Heartbeat baselines and expiration evidence are tracked per writer and partition. One writer can
 * publish to several partitions, and each partition has an independent broker
 * {@code LogAppendTime} sequence, so writer identity alone cannot define a safe time baseline.</p>
 *
 * <p>The key contains a {@link TopicPartition}, not a {@link PartitionGenerationId}, because the baseline
 * belongs to the durable log stream and survives local ownership changes. A newly acquired generation can
 * therefore continue evaluating the writer's liveness against broker time without resetting evidence at
 * every rebalance.</p>
 */
public record WriterPartitionId(@NonNull String writerNodeId, @NonNull TopicPartition topicPartition) {
    public WriterPartitionId {
        if (writerNodeId.isEmpty()) {
            throw new IllegalArgumentException("writerNodeId must not be empty");
        }
    }

    @Override
    public String toString() {
        return writerNodeId + "/" + topicPartition;
    }
}
