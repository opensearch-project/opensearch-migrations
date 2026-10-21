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
 * Describes the time budget under which a partition generation may finish already-admitted work.
 *
 * <p>Losing Kafka ownership imposes a concrete deadline because the rebalance cannot wait indefinitely;
 * that case carries a monotonic {@link CancellationDeadline}. Orderly process shutdown has no equivalent
 * local deadline and may continue draining until work completes or the host terminates the process.</p>
 *
 * <p>Representing these cases as distinct values forces cancellation code to choose the applicable timing
 * rule instead of treating every drain as either unbounded or governed by an optional, easily ignored
 * timestamp.</p>
 */
public sealed interface CancellationGrace
    permits CancellationGrace.Revocation, CancellationGrace.Shutdown {

    record Revocation(@NonNull CancellationDeadline deadline) implements CancellationGrace {}

    enum Shutdown implements CancellationGrace {
        INSTANCE
    }
}
