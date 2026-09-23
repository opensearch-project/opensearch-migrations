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
 * One Kafka offset within one {@link PartitionGenerationId}.
 *
 * <p>The generation is a component rather than a flattened {@code (topic, partition, offset)} triple, and
 * that matters for more than tidiness: it makes this identity name a <em>read event</em> rather than a
 * position in the log. An offset reread under a later generation is a different {@code KafkaRecordId}, owes
 * its own terminal disposition, and contributes separately to record accounting. Flattening the generation
 * into loose fields, as the prior implementation did, lets two reads of one offset compare equal.</p>
 *
 * <p>This identity is only well-formed as a read event because nothing is reread <em>within</em> one
 * generation. The observed-record queue enforces that by refusing an offset at or below the greatest it has
 * already seen for the generation.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2.</p>
 */
public record KafkaRecordId(PartitionGenerationId generation, long offset) {
    public KafkaRecordId {
        Objects.requireNonNull(generation, "generation");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
    }

    @Override
    public String toString() {
        return generation + "@" + offset;
    }
}
