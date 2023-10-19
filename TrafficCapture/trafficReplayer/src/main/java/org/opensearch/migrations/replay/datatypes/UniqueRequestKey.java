package org.opensearch.migrations.replay.datatypes;

public class UniqueRequestKey {
    public final TrafficStreamKeyWithRequestOffset trafficStreamKeyAndOffset;
    public final int replayerRequestIndex;

    public UniqueRequestKey(ITrafficStreamKey streamKey, int sourceOffset, int replayerIndex) {
        this(new TrafficStreamKeyWithRequestOffset(streamKey, sourceOffset), replayerIndex);
    }

    public UniqueRequestKey(TrafficStreamKeyWithRequestOffset keyWithRequestOffset, int replayerIndex) {
        this.trafficStreamKeyAndOffset = keyWithRequestOffset;
        this.replayerRequestIndex = replayerIndex;
    }

    public int getSourceRequestIndex() {
        return replayerRequestIndex + trafficStreamKeyAndOffset.getRequestIndexOffsetAtFirstObservation();
    }

    public int getReplayerRequestIndex() {
        return replayerRequestIndex;
    }

    @Override
    public String toString() {
        return trafficStreamKeyAndOffset.getTrafficStreamKey() + "." + getSourceRequestIndex() +
                (trafficStreamKeyAndOffset.getRequestIndexOffsetAtFirstObservation() == 0 ? "" :
                        "(offset: "+trafficStreamKeyAndOffset.getRequestIndexOffsetAtFirstObservation()+")");
    }

    public ITrafficStreamKey getTrafficStreamKey() {
        return trafficStreamKeyAndOffset.getTrafficStreamKey();
    }
}
