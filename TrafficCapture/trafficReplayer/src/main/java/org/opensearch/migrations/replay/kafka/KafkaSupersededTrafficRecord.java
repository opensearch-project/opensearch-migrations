package org.opensearch.migrations.replay.kafka;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Getter;
import lombok.NonNull;

/**
 * A traffic record rejected by an earlier peer declaration. It still traverses source disposition
 * so its Kafka offset can be acknowledged without entering connection reconstruction.
 */
@Getter
public final class KafkaSupersededTrafficRecord implements ITrafficStreamWithKey {
    private final TrafficStream stream;
    private final ITrafficStreamKey key;
    private final long declarationOffset;

    KafkaSupersededTrafficRecord(
        @NonNull TrafficStream stream,
        @NonNull ITrafficStreamKey key,
        long declarationOffset
    ) {
        this.stream = stream;
        this.key = key;
        this.declarationOffset = declarationOffset;
    }
}
