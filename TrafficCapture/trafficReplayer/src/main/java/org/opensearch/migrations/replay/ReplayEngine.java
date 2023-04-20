package org.opensearch.migrations.replay;

import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ReplayEngine implements BiConsumer<String, TrafficObservation> {
    private final Map<String, RequestResponsePacketPair> liveStreams;
    private final Consumer<RequestResponsePacketPair> fullDataHandler;

    public ReplayEngine(Consumer<RequestResponsePacketPair> fullDataHandler) {
        liveStreams = new HashMap<>();
        this.fullDataHandler = fullDataHandler;
    }

    @Override
    public void accept(String id, TrafficObservation observation) {
        boolean updateFirstResponseTimestamp = false;
        RequestResponsePacketPair runningList = null;
        var pbts = observation.getTs();
        var timestamp = Instant.ofEpochSecond(pbts.getSeconds(), pbts.getNanos());
        if (observation.hasEndOfMessageIndicator()) {
            publishAndClear(id);
        } else if (observation.hasRead()) {
            runningList = liveStreams.putIfAbsent(id, new RequestResponsePacketPair());
            if (runningList == null) {
                runningList = liveStreams.get(id);
                // TODO - eliminate the byte[] and use the underlying nio buffer
                runningList.addRequestData(timestamp, observation.getRead().getData().toByteArray());
            }
        } else if (observation.hasReadSegment()) {
            throw new RuntimeException("Not implemented yet.");
        } else if (observation.hasWrite()) {
            updateFirstResponseTimestamp = true;
            runningList = liveStreams.get(id);
            if (runningList == null) {
                throw new RuntimeException("Apparent out of order exception - " +
                        "found a purported write to a socket before a read!");
            }
            runningList.addResponseData(timestamp, observation.getWrite().toByteArray());
        } else if (observation.hasWriteSegment()) {
            updateFirstResponseTimestamp = true;
            throw new RuntimeException("Not implemented yet.");
        } else if (observation.hasRequestReleasedDownstream()) {
            updateFirstResponseTimestamp = true;
        }
        if (updateFirstResponseTimestamp && runningList != null) {
            if (runningList.firstTimeStampForResponse == null) {
                runningList.firstTimeStampForResponse = timestamp;
            }
        }
    }

    private void publishAndClear(String id) {
        var priorBuffers = liveStreams.get(id);
        if (priorBuffers != null) {
            fullDataHandler.accept(priorBuffers);
            liveStreams.remove(id);
        }
    }
}
