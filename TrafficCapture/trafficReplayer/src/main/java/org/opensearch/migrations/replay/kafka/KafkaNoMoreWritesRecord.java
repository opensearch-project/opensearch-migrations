package org.opensearch.migrations.replay.kafka;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.ProxyNoMoreWrites;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Getter;
import lombok.NonNull;

@Getter
public final class KafkaNoMoreWritesRecord implements ITrafficStreamWithKey {
    private final TrafficStream stream;
    private final ITrafficStreamKey key;
    private final ProxyNoMoreWrites declaration;

    KafkaNoMoreWritesRecord(
        @NonNull TrafficStream stream,
        @NonNull ITrafficStreamKey key,
        @NonNull ProxyNoMoreWrites declaration
    ) {
        this.stream = stream;
        this.key = key;
        this.declaration = declaration;
    }
}
