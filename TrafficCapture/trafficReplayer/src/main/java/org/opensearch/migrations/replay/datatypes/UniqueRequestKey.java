package org.opensearch.migrations.replay.datatypes;

public class UniqueRequestKey {
    public final ITrafficStreamKey trafficStreamKey;
    public final int requestIndex;

    public UniqueRequestKey(ITrafficStreamKey streamKey, int requestIndex) {
        this.trafficStreamKey = streamKey;
        this.requestIndex = requestIndex;
    }

    @Override
    public String toString() {
        return trafficStreamKey + "." + requestIndex;
    }
}
