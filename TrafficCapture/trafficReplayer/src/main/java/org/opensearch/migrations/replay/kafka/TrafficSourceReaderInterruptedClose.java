package org.opensearch.migrations.replay.kafka;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Synthetic record injected by KafkaTrafficCaptureSource when a partition is truly lost
 * (revoked and not reassigned back to this consumer). Signals the accumulator to close
 * the connection with ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED rather than a source-side close.
 * Does not carry a real Kafka offset and must not trigger a Kafka commit.
 */
@RequiredArgsConstructor
@Getter
public class TrafficSourceReaderInterruptedClose implements ITrafficStreamWithKey {
    private final ITrafficStreamKey key;

    @Override
    public TrafficStream getStream() {
        // Stub — accumulator detects this type before accessing the stream
        return TrafficStream.getDefaultInstance();
    }
}
