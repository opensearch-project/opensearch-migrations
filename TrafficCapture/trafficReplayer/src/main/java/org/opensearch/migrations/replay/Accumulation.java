package org.opensearch.migrations.replay;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Accumulation {
    RequestResponsePacketPair rrPair = new RequestResponsePacketPair();
    public final AtomicLong newestPacketTimestampInMillis;
    State state = State.NOTHING_SENT;

    public Accumulation() {
        newestPacketTimestampInMillis = new AtomicLong(0);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Accumulation{");
        sb.append("rrPair=").append(rrPair);
        sb.append(", state=").append(state);
        sb.append('}');
        return sb.toString();
    }

    enum State {
        NOTHING_SENT,
        RESPONSE_SENT,
        REQUEST_SENT
    }
}
