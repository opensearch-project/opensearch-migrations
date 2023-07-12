package org.opensearch.migrations.replay;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Accumulation {

    enum State {
        NOTHING_SENT,
        REQUEST_SENT,
        RESPONSE_SENT
    }

    RequestResponsePacketPair rrPair;
    AtomicLong newestPacketTimestampInMillis;

    State state = State.NOTHING_SENT;
    AtomicInteger numberOfResets;

    public Accumulation(String connectionId) {
        numberOfResets = new AtomicInteger();
        this.resetForRequest(new UniqueRequestKey(connectionId, 0));
    }

    public UniqueRequestKey getRequestId() {
        return rrPair.connectionId;
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
        resetForRequest(new UniqueRequestKey(getRequestId().connectionId, getIndexOfCurrentRequest()));
    }

    private void resetForRequest(UniqueRequestKey key) {
        this.state = State.NOTHING_SENT;
        this.rrPair = new RequestResponsePacketPair(key);
        this.newestPacketTimestampInMillis = new AtomicLong(0);
    }
}
