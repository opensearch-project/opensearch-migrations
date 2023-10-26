package org.opensearch.migrations.replay.datatypes;

public class UniqueRequestKey {
    public final ITrafficStreamKey trafficStreamKey;
    public final int sourceRequestIndexOffsetAtFirstObservation;
    public final int replayerRequestIndex;

    public UniqueRequestKey(ITrafficStreamKey streamKey, int sourceOffset, int replayerIndex) {
        this.trafficStreamKey = streamKey;
        this.sourceRequestIndexOffsetAtFirstObservation = sourceOffset;
        this.replayerRequestIndex = replayerIndex;
    }

    public int getSourceRequestIndex() {
        return replayerRequestIndex + sourceRequestIndexOffsetAtFirstObservation;
    }

    public int getReplayerRequestIndex() {
        return replayerRequestIndex;
    }

    @Override
    public String toString() {
        return trafficStreamKey + "." + getSourceRequestIndex() +
                (sourceRequestIndexOffsetAtFirstObservation == 0 ? "" :
                        "(offset: "+sourceRequestIndexOffsetAtFirstObservation+")");
    }

    public ITrafficStreamKey getTrafficStreamKey() {
        return trafficStreamKey;
    }
}
