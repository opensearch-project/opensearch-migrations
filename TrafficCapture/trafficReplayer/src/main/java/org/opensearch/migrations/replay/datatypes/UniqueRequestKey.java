package org.opensearch.migrations.replay.datatypes;

public class UniqueRequestKey {
    public final TrafficStreamKeyWithRequestOffset trafficStreamKeyAndOffset;
    public final int sourceRequestIndex;

    public UniqueRequestKey(ITrafficStreamKey streamKey, int sourceOffset, int sourceRequestIndex) {
        this(new TrafficStreamKeyWithRequestOffset(streamKey, sourceOffset), sourceRequestIndex);
    }

    public UniqueRequestKey(TrafficStreamKeyWithRequestOffset keyWithRequestOffset, int sourceRequestIndex) {
        this.trafficStreamKeyAndOffset = keyWithRequestOffset;
        this.sourceRequestIndex = sourceRequestIndex;
    }

    @Override
    public String toString() {
        return trafficStreamKeyAndOffset.getTrafficStreamKey() + "." + sourceRequestIndex +
                "(-"+trafficStreamKeyAndOffset.getRequestIndexOffsetAtFirstObservation()+")";
    }

    public ITrafficStreamKey getTrafficStreamKey() {
        return trafficStreamKeyAndOffset.getTrafficStreamKey();
    }

    public int getReplayerIndex() {
        return sourceRequestIndex - trafficStreamKeyAndOffset.getRequestIndexOffsetAtFirstObservation();
    }
}
