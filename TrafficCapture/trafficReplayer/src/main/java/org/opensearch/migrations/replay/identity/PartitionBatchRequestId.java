/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.identity;

import java.util.Objects;

/**
 * One request by replay intake for the next available batch from one partition generation.
 *
 * <p>{@code localSequence} is allocated by replay intake and is never serialized.</p>
 *
 * <p>This identity is what makes "at most one outstanding batch request per partition generation" checkable
 * rather than assumed, and what lets a delivered batch be matched to exactly one request. An empty poll does
 * not resolve a request, so the same id stays outstanding across polls until records actually arrive.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2.</p>
 */
public record PartitionBatchRequestId(PartitionGenerationId generation, long localSequence) {
    public PartitionBatchRequestId {
        Objects.requireNonNull(generation, "generation");
        if (localSequence < 0) {
            throw new IllegalArgumentException("localSequence must not be negative: " + localSequence);
        }
    }

    @Override
    public String toString() {
        return generation + ".batch" + localSequence;
    }
}
