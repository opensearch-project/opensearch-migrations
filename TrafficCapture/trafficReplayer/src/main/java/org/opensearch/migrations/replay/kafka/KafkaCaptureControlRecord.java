/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Getter;
import lombok.NonNull;

/**
 * Transitional source item for envelope payloads that have no HTTP traffic observations.
 *
 * <p>The existing accumulator's ignored-record callback settles the Kafka record. S3 replaces this
 * callback bridge with replay-intake ownership, and S12 installs heartbeat broker-time state.
 */
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
