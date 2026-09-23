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
 * One process-local source-assembly and target-connection lifetime for a {@link CapturedConnectionId}.
 *
 * <p>{@code localSequence} is allocated whenever replay intake begins fresh process-local source assembly
 * for a captured connection.</p>
 *
 * <p>This identity exists because broker-time expiration can end one process-local lifetime while target
 * and tuple work from that lifetime is still finishing, and a later observation for the same
 * {@code CapturedConnectionId} may start a separate lifetime. The two lifetimes must never share source
 * accumulators, a target channel, a request registry, or completion messages — so they must not share an
 * identity either, which is what this type provides and a bare {@code CapturedConnectionId} cannot.</p>
 *
 * <p>The generation is a component because a lifetime belongs to the ownership period that produced it:
 * cleanup for a revoked generation must be able to name exactly its own lifetimes.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerLowLevelDesign.md} section 1 and
 * {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2.</p>
 */
public record ConnectionProcessingId(
    PartitionGenerationId generation,
    CapturedConnectionId capturedConnectionId,
    long localSequence
) {
    public ConnectionProcessingId {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(capturedConnectionId, "capturedConnectionId");
        if (localSequence < 0) {
            throw new IllegalArgumentException("localSequence must not be negative: " + localSequence);
        }
    }

    @Override
    public String toString() {
        return capturedConnectionId + "[" + generation + " life " + localSequence + "]";
    }
}
