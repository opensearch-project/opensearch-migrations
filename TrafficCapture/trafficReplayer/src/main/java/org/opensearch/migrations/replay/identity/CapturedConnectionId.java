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
 * Identifies one connection in the captured-traffic protocol as
 * {@code (writerNodeId, connectionId)}.
 *
 * <p>A connection id is minted independently by each capture writer and is therefore unique only within
 * that writer. Keeping the writer id in the key prevents records from different proxies that happened to
 * choose the same connection id from being assembled into one connection.</p>
 *
 * <p>This identity describes what was captured, not one local replay lifetime. If broker-time expiration
 * closes local processing and a later record reopens the same captured connection, each lifetime receives
 * a separate {@link ConnectionProcessingId} while retaining this protocol identity.</p>
 */
public record CapturedConnectionId(@NonNull String writerNodeId, @NonNull String connectionId) {
    public CapturedConnectionId {
        if (writerNodeId.isEmpty()) {
            throw new IllegalArgumentException("writerNodeId must not be empty");
        }
        if (connectionId.isEmpty()) {
            throw new IllegalArgumentException("connectionId must not be empty");
        }
    }

    @Override
    public String toString() {
        return writerNodeId + "/" + connectionId;
    }
}
