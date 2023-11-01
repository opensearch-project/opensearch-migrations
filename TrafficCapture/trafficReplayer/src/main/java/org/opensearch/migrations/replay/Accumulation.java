package org.opensearch.migrations.replay;

import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Accumulation {

    enum State {
        // Ignore all initial READs, the first EOM & the following WRITEs (if they or EOMs exist)
        IGNORING_LAST_REQUEST,
        // Finished scanning past initial READs.  The next request should be processed,
        // so be on the lookout for the next READ
        WAITING_FOR_NEXT_READ_CHUNK,
        ACCUMULATING_READS,
        ACCUMULATING_WRITES
    }

    public final ITrafficStreamKey trafficStreamKey;
    private RequestResponsePacketPair rrPair;
    AtomicLong newestPacketTimestampInMillis;
    State state;
    AtomicInteger numberOfResets;
    final int startingSourceRequestIndex;

    public Accumulation(@NonNull ITrafficStreamKey trafficStreamKey, int startingSourceRequestIndex) {
        this(trafficStreamKey, startingSourceRequestIndex, false);
    }

    public Accumulation(@NonNull ITrafficStreamKey trafficStreamKey,
                        int startingSourceRequestIndex, boolean dropObservationsLeftoverFromPrevious) {
        this.trafficStreamKey = trafficStreamKey;
        numberOfResets = new AtomicInteger();
        this.newestPacketTimestampInMillis = new AtomicLong(0);
        this.startingSourceRequestIndex = startingSourceRequestIndex;
        this.state =
                dropObservationsLeftoverFromPrevious ? State.IGNORING_LAST_REQUEST : State.WAITING_FOR_NEXT_READ_CHUNK;
    }

    public RequestResponsePacketPair getOrCreateTransactionPair() {
        if (rrPair != null) {
            return rrPair;
        }
        return rrPair = new RequestResponsePacketPair();
    }

    public UniqueReplayerRequestKey getRequestKey() {
        return new UniqueReplayerRequestKey(trafficStreamKey, startingSourceRequestIndex, getIndexOfCurrentRequest());
    }

    public boolean hasSignaledRequests() {
        return numberOfResets.get() > 0 || state == Accumulation.State.ACCUMULATING_WRITES;
    }

    public boolean hasRrPair() {
        return rrPair != null;
    }

    /**
     * It is illegal to call this when rrPair may be equal to null.  If the caller isn't sure,
     * hasRrPair() should be called to first check.
     * @return
     */
    public @NonNull RequestResponsePacketPair getRrPair() {
        assert rrPair != null;
        return rrPair;
    }

    public Instant getLastTimestamp() {
        return Instant.ofEpochMilli(newestPacketTimestampInMillis.get());
    }

    public AtomicLong getNewestPacketTimestampInMillisReference() {
        return newestPacketTimestampInMillis;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Accumulation{");
        sb.append("rrPair=").append(rrPair);
        sb.append(", state=").append(state);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Accumulations are reset for each new HttpRequest that is discovered.  This value indicates how
     * many times the object has been reset, indicating in a logical sequence of requests against this
     * Accumulation, what index would the current data be a part of?  Calling resetForNextRequest()
     * will increase this value by 1.
     * @return
     */
    public int getIndexOfCurrentRequest() {
        return numberOfResets.get();
    }

    public void resetForNextRequest() {
        numberOfResets.incrementAndGet();
        this.state = State.ACCUMULATING_READS;
        this.rrPair = null;
    }
}
