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
 * The protocol identity of a captured connection: {@code (writerNodeId, connectionId)}.
 *
 * <p>Both components are required. A {@code connectionId} is only unique within the writer that minted it,
 * so a bare connection id is not an identity — two proxies can independently produce the same one, and
 * treating them as equal merges two unrelated captured connections.</p>
 *
 * <p>This is a <em>protocol</em> identity, carried in the capture stream. It is not a process-local
 * lifetime: one {@code CapturedConnectionId} may be replayed as several
 * {@link ConnectionProcessingId} lifetimes if broker-time expiration ends one while a later observation
 * starts another.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerLowLevelDesign.md} section 1 and
 * {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2.</p>
 */
public record CapturedConnectionId(String writerNodeId, String connectionId) {
    public CapturedConnectionId {
        Objects.requireNonNull(writerNodeId, "writerNodeId");
        Objects.requireNonNull(connectionId, "connectionId");
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
