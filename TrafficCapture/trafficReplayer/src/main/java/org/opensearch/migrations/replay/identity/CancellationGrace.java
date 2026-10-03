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
 * Why a partition generation entered grace.
 *
 * <p>Revocation is bounded by Kafka's rebalance callback deadline. Orderly shutdown has no process-local
 * deadline and drains until completion or host termination.</p>
 */
public sealed interface CancellationGrace
    permits CancellationGrace.Revocation, CancellationGrace.Shutdown {

    record Revocation(@NonNull CancellationDeadline deadline) implements CancellationGrace {}

    enum Shutdown implements CancellationGrace {
        INSTANCE
    }
}
