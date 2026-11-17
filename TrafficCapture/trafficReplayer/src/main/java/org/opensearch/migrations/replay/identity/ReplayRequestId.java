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
 * Identifies one reconstituted request within a local connection-processing lifetime.
 *
 * <p>The same value follows the request through assembly, target transmission, response handling, and tuple
 * output. A single identity lets those independently asynchronous stages correlate ownership and terminal
 * outcomes without translating among stage-specific request ids.</p>
 *
 * <p>The captured ordinal preserves the request's original position within its connection. Replay may
 * begin in the middle of a Kafka stream, so the first observed ordinal need not be zero and must not be
 * renumbered; preserving it is what keeps pipelined request and response order unambiguous.</p>
 */
public record ReplayRequestId(@NonNull ConnectionProcessingId connectionProcessingId, long capturedRequestOrdinal) {
    public ReplayRequestId {
        if (capturedRequestOrdinal < 0) {
            throw new IllegalArgumentException(
                "capturedRequestOrdinal must not be negative: " + capturedRequestOrdinal);
        }
    }

    @Override
    public String toString() {
        return connectionProcessingId + ".req" + capturedRequestOrdinal;
    }
}
