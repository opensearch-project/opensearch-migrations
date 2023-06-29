package org.opensearch.migrations.replay;

import java.util.concurrent.atomic.AtomicLong;

public class Accumulation {
    RequestResponsePacketPair rrPair;
    AtomicLong newestPacketTimestampInMillis;

    State state = State.NOTHING_SENT;

    public Accumulation(String connectionId) {
        resetForNextRequest(this, connectionId);
    }

    public String getConnectionId() {
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

    public void resetForNextRequest() {
        resetForNextRequest(this, this.rrPair.connectionId);
    }

    public static void resetForNextRequest(Accumulation accumulation, String connectionId) {
        accumulation.state = State.NOTHING_SENT;
        accumulation.rrPair = new RequestResponsePacketPair(connectionId);
        accumulation.newestPacketTimestampInMillis = new AtomicLong(0);
    }

    enum State {
        NOTHING_SENT,
        RESPONSE_SENT,
        REQUEST_SENT
    }
}
