package org.opensearch.migrations.replay.kafka;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Getter;
import lombok.NonNull;

@Getter
public final class KafkaLivenessSnapshotRecord implements ITrafficStreamWithKey {
    private final TrafficStream stream;
    private final ITrafficStreamKey key;
    private final ProxyLivenessSnapshotChunk snapshotChunk;

    KafkaLivenessSnapshotRecord(
        @NonNull TrafficStream stream,
        @NonNull ITrafficStreamKey key,
        @NonNull ProxyLivenessSnapshotChunk snapshotChunk
    ) {
        this.stream = stream;
        this.key = key;
        this.snapshotChunk = snapshotChunk;
    }
}
