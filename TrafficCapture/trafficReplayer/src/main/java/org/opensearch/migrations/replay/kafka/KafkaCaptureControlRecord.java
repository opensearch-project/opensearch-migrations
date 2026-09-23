/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.

// REBUILD-LIMBO-START(G11)
/*

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Getter;
import lombok.NonNull;

*/
// REBUILD-LIMBO-END(G11)
/**
 * Transitional source item for envelope payloads that have no HTTP traffic observations.
 *
 * <p>The existing accumulator's ignored-record callback settles the Kafka record. S3 replaces this
 * callback bridge with replay-intake ownership, and S12 installs heartbeat broker-time state.
 */
// REBUILD-LIMBO-START(G11)
/*
@Getter
public final class KafkaCaptureControlRecord implements ITrafficStreamWithKey {
    @NonNull
    private final CaptureRecord captureRecord;
    @NonNull
    private final TrafficStream stream;
    @NonNull
    private final ITrafficStreamKey key;

    KafkaCaptureControlRecord(
        @NonNull CaptureRecord captureRecord,
        @NonNull TrafficStream stream,
        @NonNull ITrafficStreamKey key
    ) {
        if (captureRecord.getPayloadCase() != CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT
            && captureRecord.getPayloadCase() != CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE) {
            throw new IllegalArgumentException("Expected a heartbeat or capability probe");
        }
        this.captureRecord = captureRecord;
        this.stream = stream;
        this.key = key;
    }
}

*/
// REBUILD-LIMBO-END(G11)