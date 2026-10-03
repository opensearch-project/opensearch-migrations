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
 * One reconstituted request within a {@link ConnectionProcessingId}.
 *
 * <p>This is <strong>the only</strong> request identity used across replay intake, target connection,
 * request replay, and tuple output. There is deliberately no second request identity for any one of those
 * stages: the prior implementation carried several, which made a request's identity depend on which
 * component was asked.</p>
 *
 * <p>{@code capturedRequestOrdinal} is the request's position within the captured connection, and it starts
 * wherever the capture says it does. A replay that begins from a nonzero Kafka cursor sees a nonzero first
 * ordinal, and that must preserve later request order rather than being renumbered from zero.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerLowLevelDesign.md} section 1 and
 * {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2.</p>
 */
public record ReplayRequestId(ConnectionProcessingId connectionProcessingId, long capturedRequestOrdinal) {
    public ReplayRequestId {
        Objects.requireNonNull(connectionProcessingId, "connectionProcessingId");
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
