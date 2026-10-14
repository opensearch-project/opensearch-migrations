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
 * Identifies one process-local assembly and target-connection lifetime for captured connection traffic.
 *
 * <p>Broker-time expiration can end local processing while target and tuple work is still settling. A
 * later record for the same {@link CapturedConnectionId} may then begin a new lifetime, and the old and new
 * work must not share assembly buffers, target state, request registries, or completion signals. The local
 * sequence makes those overlapping lifetimes distinct.</p>
 *
 * <p>The partition generation is also part of the identity because cleanup must affect only work created
 * during that ownership period. This lets a revoked generation drain or cancel its own connection
 * lifetimes without touching replacements created after ownership is reacquired.</p>
 */
public record ConnectionProcessingId(
    @NonNull PartitionGenerationId generation,
    @NonNull CapturedConnectionId capturedConnectionId,
    long localSequence
) {
    public ConnectionProcessingId {
        if (localSequence < 0) {
            throw new IllegalArgumentException("localSequence must not be negative: " + localSequence);
        }
    }

    @Override
    public String toString() {
        return capturedConnectionId + "[" + generation + " life " + localSequence + "]";
    }
}
