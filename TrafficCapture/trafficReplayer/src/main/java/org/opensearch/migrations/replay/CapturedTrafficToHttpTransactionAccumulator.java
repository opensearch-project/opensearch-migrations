package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * This class consumes TrafficObservation objects, which will be predominated by reads and writes that
 * were received by some HTTP source.  Reads represent data read by a server from traffic that was
 * submitted by a client.  Writes will be those packets sent back to the client.
 *
 * This class is basically a groupBy operation over a sequence of Observations, where the grouping key
 * is the id of the TrafficStream that contained the observations.  Recall that a TrafficStream
 * represents packets that have been read/written to a given socket.  The id represents that connection.
 *
 * Today, this class expects traffic to be from HTTP/1.1 or lower.  It will expect well-formed sequences to
 * have reads, followed by an end of message indicator, followed by writes, which should then be followed
 * by an end of message indicator.  This pattern may be repeated any number of times for each or any id.
 * This class expects that ids are unique and that multiple streams will not share the same id AND be
 * overlapped in time.
 *
 * Upon receiving all the packets for a full request, this class will call the first of two callbacks, the
 * requestReceivedHandler that was passed to the constructor.  A second callback will be called after the
 * full contents of the source response has been received.  The first callback will ONLY include the
 * reconstructed source HttpMessageAndTimestamp.  The second callback acts upon the
 * RequestResponsePacketPair object, which will include the message from the first timestamp as the
 * requestData field.
 *
 * This class needs to do a better job of dealing with edge cases, such as packets/streams being terminated.
 * It has no notion of time, limiting its ability to terminate and prune transactions whose requests or
 * responses may not have been completely received.
 */
@Slf4j
public class CapturedTrafficToHttpTransactionAccumulator implements BiConsumer<String, TrafficObservation> {
    enum State {
        NOTHING_SENT,
        RESPONSE_SENT,
        REQUEST_SENT
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

    public CapturedTrafficToHttpTransactionAccumulator(Consumer<HttpMessageAndTimestamp> requestReceivedHandler,
                                                       Consumer<RequestResponsePacketPair> fullDataHandler) {
        liveStreams = new HashMap<>();
        this.requestHandler = requestReceivedHandler;
        this.fullDataHandler = fullDataHandler;
    }

    @Override
    public void accept(String id, TrafficObservation observation) {
        log.error("Stream: " + id + " Consuming observation: " + observation);
        var timestamp =
                Optional.of(observation.getTs()).map(t->Instant.ofEpochSecond(t.getSeconds(), t.getNanos())).get();
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

    // This function manages the lifecycles of the objects in the liveStreams map.
    // It will create a new Accumulation object when the first read of a request is
    // discovered, which may be for the very first observation of a TrafficStream
    // or it could be for the first read observation after the last response was
    // received.  For the latter case, this will close out the old accumulation,
    // recycling it from the liveStream map and into the response callback (by
    // invoking the callback).
    private Accumulation getAccumulationForFirstRequestObservation(String id) {
        var accum = liveStreams.computeIfAbsent(id, k -> new Accumulation());
        // If this was brand new or if we've already closed the item out and pushed
        // the state to RESPONSE_SENT, we don't need to care about triggering the
        // callback.  We only need to worry about this if we have yet to send the
        // RESPONSE.  Notice that handleEndOfMessage will bump the state itself
        // on the (soon to be recycled) accum object.
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
