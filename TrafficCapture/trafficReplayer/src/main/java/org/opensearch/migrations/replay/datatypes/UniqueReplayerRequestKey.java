package org.opensearch.migrations.replay.datatypes;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import lombok.EqualsAndHashCode;
import lombok.Getter;

@EqualsAndHashCode(callSuper = true)
public class UniqueReplayerRequestKey extends UniqueSourceRequestKey {
    public final ITrafficStreamKey trafficStreamKey;
    public final int sourceRequestIndexSessionIdentifier;
    @Getter
    public final int replayerRequestIndex;

    public UniqueReplayerRequestKey(
        ITrafficStreamKey streamKey,
        int sourceOffsetAtStartOfAccumulation,
        int replayerIndex
    ) {
        this.trafficStreamKey = streamKey;
        this.sourceRequestIndexSessionIdentifier = sourceOffsetAtStartOfAccumulation;
        this.replayerRequestIndex = replayerIndex;
    }

    @Override
    public ISourceTrafficChannelKey getTrafficStreamKey() {
        return trafficStreamKey;
    }

    @Override
    public int getSourceRequestIndex() {
        return replayerRequestIndex + sourceRequestIndexSessionIdentifier;
    }

    @Override
    public String toString() {
        // The offset that is shown is a mouthful to describe.
        // The thing that’s tricky is that these indices are stateful across different TrafficStreams.
        // One part is stateful from the capture and the other part is stateful from the replayer.
        // We’re piecing things back into sequence in the replayer, so each Observation (and request)
        // may be from different TrafficStreams and therefore might have had a different starting index.
        //
        // So the idea with showing that index is to just give a hint about how far back the history of
        // the current TrafficStreams go for this stretch of Observations. This offset value is the
        // value from the very first TrafficStream that was calculated when the very first Accumulation
        // was being initialized. The value itself is derived on that first observed TrafficStream as
        // `stream.getPriorRequestsReceived()+(stream.hasLastObservationWasUnterminatedRead()?1:0)`
        //
        // That code currently resides in CapturedTrafficToHttpTransactionAccumulator.
        return trafficStreamKey
            + "."
            + getSourceRequestIndex()
            + (sourceRequestIndexSessionIdentifier == 0 ? "" : "(offset: " + sourceRequestIndexSessionIdentifier + ")");
    }
}

*/
// REBUILD-LIMBO-END(G11)