package org.opensearch.migrations.replay;

public class Accumulation {
    RequestResponsePacketPair rrPair = new RequestResponsePacketPair();
    State state = State.NOTHING_SENT;

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
