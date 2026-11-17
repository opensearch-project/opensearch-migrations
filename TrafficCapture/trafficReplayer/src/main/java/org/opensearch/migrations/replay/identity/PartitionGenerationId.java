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
 * Identifies one uninterrupted process-local ownership period for a Kafka topic partition.
 *
 * <p>A new local sequence is allocated each time the partition becomes owned. The value is neither Kafka's
 * consumer-group generation nor protocol data; it exists only to separate work created during different
 * local ownership periods.</p>
 *
 * <p>That separation is essential when a rebalance causes offsets to be read again. Records, connection
 * lifetimes, batch requests, cancellation, and cleanup remain attributable to the generation that created
 * them, so old draining work cannot collide with replacement work for the same partition.</p>
 */
public record PartitionGenerationId(@NonNull TopicPartition topicPartition, long localSequence) {
    public PartitionGenerationId {
        if (localSequence < 0) {
            throw new IllegalArgumentException("localSequence must not be negative: " + localSequence);
        }
    }

    @Override
    public String toString() {
        return topicPartition + "#" + localSequence;
    }
}
