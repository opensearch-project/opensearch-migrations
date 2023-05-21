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
            handleEndOfMessage(id);
        } else if (observation.hasRead()) {
            var accum = liveStreams.computeIfAbsent(id, k -> new Accumulation());
            if (accum.state == State.REQUEST_SENT) {
                handleEndOfMessage(id);
                accum = new Accumulation();
                liveStreams.put(id, accum);
            }
            var runningList = accum.rrPair;
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

    private void handleEndOfMessage(String id) {
        var priorBuffers = liveStreams.get(id);
        if (priorBuffers == null) {
            log.warn("No prior messages, but received an EOM.  " +
                    "Considering that as nothing to do, but it indicates either a corruption or logical error " +
                    "here or in the capture");
            return;
        }
        switch (priorBuffers.state) {
            case NOTHING_SENT:
                requestHandler.accept(priorBuffers.rrPair.requestData);
                priorBuffers.state = State.REQUEST_SENT;
                break;
            case REQUEST_SENT:
                fullDataHandler.accept(priorBuffers.rrPair);
                liveStreams.remove(id);
                break;
        }
    }
}
