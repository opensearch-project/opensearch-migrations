package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;

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

    RequestResponsePacketPair rrPair;
    AtomicLong newestPacketTimestampInMillis;
    State state;
    AtomicInteger numberOfResets;

    public Accumulation(UniqueRequestKey nextRequestKey) {
        this(nextRequestKey, false);
    }

    public Accumulation(UniqueRequestKey nextRequestKey, boolean dropObservationsLeftoverFromPrevious) {
        numberOfResets = new AtomicInteger();
        this.rrPair = new RequestResponsePacketPair(nextRequestKey);
        this.newestPacketTimestampInMillis = new AtomicLong(0);
        this.state =
                dropObservationsLeftoverFromPrevious ? State.IGNORING_LAST_REQUEST : State.WAITING_FOR_NEXT_READ_CHUNK;
    }

    public UniqueRequestKey getRequestId() {
        return rrPair.requestKey;
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
        var key = new UniqueRequestKey(getRequestId().trafficStreamKeyAndOffset, getIndexOfCurrentRequest());
        this.rrPair = new RequestResponsePacketPair(key);
        this.newestPacketTimestampInMillis = new AtomicLong(0);
    }
}
