package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
public class CapturedTrafficToHttpTransactionAccumulator {

    public static final Duration EXPIRATION_GRANULARITY = Duration.ofSeconds(1);
    private final ExpiringTrafficStreamMap liveStreams;
    private final BiConsumer<String, HttpMessageAndTimestamp> requestHandler;
    private final Consumer<RequestResponsePacketPair> fullDataHandler;

    private final AtomicInteger newConnectionCounter = new AtomicInteger();
    private final AtomicInteger reusedKeepAliveCounter = new AtomicInteger();
    private final AtomicInteger closedConnectionCounter = new AtomicInteger();
    private final AtomicInteger connectionsExpiredCounter = new AtomicInteger();
    private final AtomicInteger requestsTerminatedUponAccumulatorCloseCounter = new AtomicInteger();

    public CapturedTrafficToHttpTransactionAccumulator(Duration minTimeout,
                                                       BiConsumer<String,HttpMessageAndTimestamp> requestReceivedHandler,
                                                       Consumer<RequestResponsePacketPair> fullDataHandler) {
        liveStreams = new ExpiringTrafficStreamMap(minTimeout, EXPIRATION_GRANULARITY,
                new ExpiringTrafficStreamMap.BehavioralPolicy() {
                    @Override
                    public void onExpireAccumulation(String partitionId,
                                                     String connectionId,
                                                     Accumulation accumulation) {
                        connectionsExpiredCounter.incrementAndGet();
                        log.error("firing accumulation for accum=[" + connectionId + "]=" + accumulation);
                        fireAccumulationsCallbacks(connectionId, accumulation);
                    }
                });
        this.requestHandler = requestReceivedHandler;
        this.fullDataHandler = fullDataHandler;
    }

    public int numberOfConnectionsCreated() { return newConnectionCounter.get(); }
    public int numberOfRequestsOnReusedConnections() { return reusedKeepAliveCounter.get(); }
    public int numberOfConnectionsClosed() { return closedConnectionCounter.get(); }
    public int numberOfConnectionsExpired() { return connectionsExpiredCounter.get(); }
    public int numberOfRequestsTerminatedUponAccumulatorClose() {
        return requestsTerminatedUponAccumulatorCloseCounter.get();
    }

    public void accept(String nodeId, String connectionId, TrafficObservation observation) {
        log.debug("Stream: " + nodeId + "/" + connectionId + " Consuming observation: " + observation);
        var timestamp =
                Optional.of(observation.getTs()).map(t->Instant.ofEpochSecond(t.getSeconds(), t.getNanos())).get();
        if (observation.hasEndOfMessageIndicator()) {
            var accum = liveStreams.get(nodeId, connectionId, timestamp);
            if (accum == null) {
                log.warn("Received EOM w/out an accumulated value, assuming an empty server interaction and " +
                        "NOT reproducing this to the target cluster (TODO - do something better?)");
                return;
            }
            handleEndOfMessage(nodeId, connectionId, accum);
        } else if (observation.hasRead()) {
            var accum = getAccumulationForFirstRequestObservation(nodeId, connectionId, timestamp);
            assert accum.state == Accumulation.State.NOTHING_SENT;
            log.trace("Adding request data for accum[" + connectionId + "]=" + accum);
            accum.rrPair.addRequestData(timestamp, observation.getRead().getData().toByteArray());
            log.trace("Added request data for accum[" + connectionId + "]=" + accum);
        } else if (observation.hasWrite()) {
            var accum = liveStreams.get(nodeId, connectionId, timestamp);
            assert accum != null && accum.state == Accumulation.State.REQUEST_SENT;
            var runningList = accum.rrPair;
            if (runningList == null) {
                throw new RuntimeException("Apparent out of order exception - " +
                        "found a purported write to a socket before a read!");
            }
            log.trace("Adding response data for accum[" + connectionId + "]=" + accum);
            runningList.addResponseData(timestamp, observation.getWrite().getData().toByteArray());
            log.trace("Added response data for accum[" + connectionId + "]=" + accum);
        } else if (observation.hasReadSegment()) {
            var accum = getAccumulationForFirstRequestObservation(nodeId, connectionId, timestamp);
            assert accum.state == Accumulation.State.NOTHING_SENT;
            log.trace("Adding request segment for accum[" + connectionId + "]=" + accum);
            if (accum.rrPair.requestData == null) {
                accum.rrPair.requestData = new HttpMessageAndTimestamp(timestamp);
            }
            accum.rrPair.requestData.addSegment(observation.getReadSegment().getData().toByteArray());
            log.trace("Added request segment for accum[" + connectionId + "]=" + accum);
        } else if (observation.hasWriteSegment()) {
            var accum = liveStreams.get(nodeId, connectionId, timestamp);
            assert accum != null && accum.state == Accumulation.State.REQUEST_SENT;
            log.trace("Adding response segment for accum[" + connectionId + "]=" + accum);
            if (accum.rrPair.responseData == null) {
                accum.rrPair.responseData = new HttpMessageAndTimestamp(timestamp);
            }
            accum.rrPair.responseData.addSegment(observation.getWrite().getData().toByteArray());
            log.trace("Added response segment for accum[" + connectionId + "]=" + accum);
        } else if (observation.hasSegmentEnd()) {
            var accum = liveStreams.get(nodeId, connectionId, timestamp);
            assert accum != null && accum.state == Accumulation.State.REQUEST_SENT;
            if (accum.rrPair.requestData.hasInProgressSegment()) {
                accum.rrPair.requestData.finalizeRequestSegments(timestamp);
            } else if (accum.rrPair.responseData.hasInProgressSegment()) {
                accum.rrPair.responseData.finalizeRequestSegments(timestamp);
            } else {
                throw new RuntimeException("Got an end of segment indicator, but no segments are in progress");
            }
        } else if (observation.hasConnectionException()) {
            closedConnectionCounter.incrementAndGet();
            var accum = liveStreams.remove(nodeId, connectionId);
            log.warn("Removing accumulated traffic pair for " + connectionId);
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
    private Accumulation getAccumulationForFirstRequestObservation(String nodeId,
                                                                   String connectionId,
                                                                   Instant timestamp) {
        var accum = liveStreams.getOrCreate(nodeId, connectionId, timestamp);
        // If this was brand new or if we've already closed the item out and pushed
        // the state to RESPONSE_SENT, we don't need to care about triggering the
        // callback.  We only need to worry about this if we have yet to send the
        // RESPONSE.  Notice that handleEndOfMessage will bump the state itself
        // on the (soon to be recycled) accum object.
        if (accum.state == Accumulation.State.REQUEST_SENT) {
            reusedKeepAliveCounter.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("Resetting accum[" + connectionId + "]=" + accum);
            }
            handleEndOfMessage(nodeId, connectionId, accum);
            // We shouldn't need to check the state again - it should be NOT_SENT, but in case
            // this code goes multi-threaded, this might be a bit safer.
            // TODO - reevaluate once the packet assembly code is more mature
            return getAccumulationForFirstRequestObservation(nodeId, connectionId, timestamp);
        } else if (accum.state == Accumulation.State.NOTHING_SENT) {
            newConnectionCounter.incrementAndGet();
        }
        return accum;
    }

    /**
     * @param connectionId
     * @param accumulation
     * @return true if there are still callbacks remaining to be called
     */
    private boolean handleEndOfMessage(String nodeId, String connectionId, Accumulation accumulation) {
        switch (accumulation.state) {
            case NOTHING_SENT:
                requestHandler.accept(connectionId, accumulation.rrPair.requestData);
                accumulation.state = Accumulation.State.REQUEST_SENT;
                return true;
            case REQUEST_SENT:
                fullDataHandler.accept(accumulation.rrPair);
                accumulation.state = Accumulation.State.RESPONSE_SENT;
                liveStreams.remove(nodeId, connectionId);
        }
        return false;
    }

    public void close() {
        liveStreams.entries().forEach(kvp -> {
            if (kvp.getValue().state != Accumulation.State.RESPONSE_SENT) {
                requestsTerminatedUponAccumulatorCloseCounter.incrementAndGet();
            }
            fireAccumulationsCallbacks(kvp.getKey().connectionId, kvp.getValue());
        });
        liveStreams.clear();
    }

    private void fireAccumulationsCallbacks(String connectionId, Accumulation accumulation) {
        switch (accumulation.state) {
            case NOTHING_SENT:
                requestHandler.accept(connectionId, accumulation.rrPair.requestData);
                accumulation.state = Accumulation.State.REQUEST_SENT;
                // fall through
            case REQUEST_SENT:
                fullDataHandler.accept(accumulation.rrPair);
                accumulation.state = Accumulation.State.RESPONSE_SENT;
        }
    }
}
