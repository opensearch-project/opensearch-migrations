/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.identity;

import lombok.NonNull;

/**
 * Correlates one demand for records with the next delivered batch from a partition generation.
 *
 * <p>Replay intake permits at most one unresolved demand per generation. The local sequence turns that
 * rule into an explicit correlation check: a non-empty batch must satisfy exactly the request that caused
 * the partition to be resumed, while stale or duplicate deliveries can be rejected.</p>
 *
 * <p>An empty Kafka poll does not satisfy the demand, so the same id remains outstanding across polls until
 * records arrive. The id is process-local coordination state and is never serialized into capture data.</p>
 */
public record PartitionBatchRequestId(@NonNull PartitionGenerationId generation, long localSequence) {
    public PartitionBatchRequestId {
        if (localSequence < 0) {
            throw new IllegalArgumentException("localSequence must not be negative: " + localSequence);
        }
    }

    @Override
    public String toString() {
        return generation + ".batch" + localSequence;
    }
}
