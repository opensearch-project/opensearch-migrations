package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
public class ReplayEngine implements BiConsumer<String, TrafficObservation> {
    enum State {
        NOTHING_SENT,
        RESPONSE_SENT, REQUEST_SENT
    }
    public static class Accumulation {
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
    }
    private final Map<String, Accumulation> liveStreams;
    private final Consumer<HttpMessageAndTimestamp> requestHandler;
    private final Consumer<RequestResponsePacketPair> fullDataHandler;

    public ReplayEngine(Consumer<HttpMessageAndTimestamp> requestReceivedHandler,
                        Consumer<RequestResponsePacketPair> fullDataHandler) {
        liveStreams = new HashMap<>();
        this.requestHandler = requestReceivedHandler;
        this.fullDataHandler = fullDataHandler;
    }

    @Override
    public void accept(String id, TrafficObservation observation) {
        log.error("Stream: " + id + " Consuming observation: " + observation);
        var pbts = observation.getTs();
        var timestamp = Instant.ofEpochSecond(pbts.getSeconds(), pbts.getNanos());
        if (observation.hasEndOfMessageIndicator()) {
            var accum = liveStreams.get(id);
            if (accum == null) {
                log.warn("Received EOM w/out an accumulated value, assuming an empty server interaction and " +
                        "NOT reproducing this to the target cluster (TODO - do something better?)");
                return;
            }
            handleEndOfMessage(id, accum);
        } else if (observation.hasRead()) {
            var accum = getAccumulationForFirstRequestObservation(id);
            assert accum.state == State.NOTHING_SENT;
            var runningList = accum.rrPair;
            // TODO - eliminate the byte[] and use the underlying nio buffer
            runningList.addRequestData(timestamp, observation.getRead().getData().toByteArray());
        } else if (observation.hasReadSegment()) {
            var accum = getAccumulationForFirstRequestObservation(id);
            assert accum.state == State.NOTHING_SENT;
            throw new RuntimeException("Not implemented yet.");
        } else if (observation.hasWrite()) {
            var accum = liveStreams.get(id);
            assert accum != null && accum.state == State.REQUEST_SENT;
            var runningList = accum.rrPair;
            if (runningList == null) {
                throw new RuntimeException("Apparent out of order exception - " +
                        "found a purported write to a socket before a read!");
            }
            runningList.addResponseData(timestamp, observation.getWrite().getData().toByteArray());
        } else if (observation.hasWriteSegment()) {
            var accum = liveStreams.get(id);
            assert accum != null && accum.state == State.REQUEST_SENT;
            var runningList = accum.rrPair;
            throw new RuntimeException("Not implemented yet.");
        } else if (observation.hasConnectionException()) {
            var accum = liveStreams.remove(id);
            log.warn("Removing accumulated traffic pair for " + id);
            log.debug("Accumulated object: " + accum);
        } else {
            log.warn("unaccounted for observation type " + observation);
        }
    }

    private Accumulation getAccumulationForFirstRequestObservation(String id) {
        var accum = liveStreams.computeIfAbsent(id, k -> new Accumulation());
        if (accum.state == State.REQUEST_SENT) {
            handleEndOfMessage(id, accum);
            accum = new Accumulation();
            liveStreams.put(id, accum);
        }
        return accum;
    }

    /**
     * @param id
     * @param accumulation
     * @return true if there are still callbacks remaining to be called
     */
    private boolean handleEndOfMessage(String id, Accumulation accumulation) {
        switch (accumulation.state) {
            case NOTHING_SENT:
                requestHandler.accept(accumulation.rrPair.requestData);
                accumulation.state = State.REQUEST_SENT;
                return true;
            case REQUEST_SENT:
                fullDataHandler.accept(accumulation.rrPair);
                accumulation.state = State.RESPONSE_SENT;
                liveStreams.remove(id);
        }
        return false;
    }

    public void close() {
        liveStreams.values().forEach(accumulation-> {
            switch (accumulation.state) {
                case NOTHING_SENT:
                    requestHandler.accept(accumulation.rrPair.requestData);
                    accumulation.state = State.REQUEST_SENT;
                    // fall through
                case REQUEST_SENT:
                    fullDataHandler.accept(accumulation.rrPair);
                    accumulation.state = State.RESPONSE_SENT;
            }
        });
        liveStreams.clear();
    }
}
