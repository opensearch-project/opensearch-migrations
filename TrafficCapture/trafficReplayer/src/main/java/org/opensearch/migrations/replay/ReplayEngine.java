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
        REQUEST_SENT
    }
    public static class Accumulation {
        RequestResponsePacketPair rrPair = new RequestResponsePacketPair();
        State state = State.NOTHING_SENT;
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
        var pbts = observation.getTs();
        var timestamp = Instant.ofEpochSecond(pbts.getSeconds(), pbts.getNanos());
        if (observation.hasEndOfMessageIndicator()) {
            handleEndOfMessage(id, getAccumulationForFirstRequestObservation(id));
        } else if (observation.hasRead()) {
            var runningList = getAccumulationForFirstRequestObservation(id).rrPair;
            // TODO - eliminate the byte[] and use the underlying nio buffer
            runningList.addRequestData(timestamp, observation.getRead().getData().toByteArray());
        } else if (observation.hasReadSegment()) {
            throw new RuntimeException("Not implemented yet.");
        } else if (observation.hasWrite()) {
            var runningList = liveStreams.get(id).rrPair;
            if (runningList == null) {
                throw new RuntimeException("Apparent out of order exception - " +
                        "found a purported write to a socket before a read!");
            }
            runningList.addResponseData(timestamp, observation.getWrite().toByteArray());
        } else if (observation.hasWriteSegment()) {
            throw new RuntimeException("Not implemented yet.");
        } else if (observation.hasRequestReleasedDownstream()) {

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
            }
        });
        liveStreams.clear();
    }
}
