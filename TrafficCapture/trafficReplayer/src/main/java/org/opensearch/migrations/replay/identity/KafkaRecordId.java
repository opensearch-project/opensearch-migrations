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
 * Identifies one observation of a Kafka offset during one local partition-ownership generation.
 *
 * <p>The identity names a read event rather than merely a position in the Kafka log. If an offset is read
 * again after ownership changes, the new {@link PartitionGenerationId} produces a different record id, so
 * both observations receive their own terminal disposition and accounting.</p>
 *
 * <p>Within a generation, offsets are accepted only in strictly increasing order. That invariant makes
 * the generation-and-offset pair an exact key for record ownership, commit eligibility, and metrics even
 * when rebalances cause the same log position to be revisited later.</p>
 */
public record KafkaRecordId(@NonNull PartitionGenerationId generation, long offset) {
    public KafkaRecordId {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
    }

    @Override
    public String toString() {
        return generation + "@" + offset;
    }
}
