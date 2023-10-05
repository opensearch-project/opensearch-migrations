package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;

public class UniqueRequestKey {
    public final TrafficStreamKey trafficStreamKey;
    public final int requestIndex;

    public UniqueRequestKey(TrafficStreamKey streamKey, int requestIndex) {
        this.trafficStreamKey = streamKey;
        this.requestIndex = requestIndex;
    }

    @Override
    public String toString() {
        return trafficStreamKey + "." + requestIndex;
    }
}
