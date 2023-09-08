package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.traffic.expiration.BehavioralPolicy;
import org.opensearch.migrations.replay.traffic.expiration.ExpiringTrafficStreamMap;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final BiConsumer<UniqueRequestKey, HttpMessageAndTimestamp> requestHandler;
    private final Consumer<RequestResponsePacketPair> fullDataHandler;
    private final Consumer<Accumulation> onTrafficStreamMissingOnExpiration;
    private final BiConsumer<UniqueRequestKey,Instant> connectionCloseListener;

    private final AtomicInteger reusedKeepAliveCounter = new AtomicInteger();
    private final AtomicInteger closedConnectionCounter = new AtomicInteger();
    private final AtomicInteger exceptionConnectionCounter = new AtomicInteger();
    private final AtomicInteger connectionsExpiredCounter = new AtomicInteger();
    private final AtomicInteger requestsTerminatedUponAccumulatorCloseCounter = new AtomicInteger();

    public CapturedTrafficToHttpTransactionAccumulator(Duration minTimeout,
                                                       BiConsumer<UniqueRequestKey,HttpMessageAndTimestamp> requestReceivedHandler,
                                                       Consumer<RequestResponsePacketPair> fullDataHandler,
                                                       BiConsumer<UniqueRequestKey,Instant> connectionCloseListener)
    {
        this(minTimeout, requestReceivedHandler, fullDataHandler, connectionCloseListener,
                accumulation -> log.atWarn()
                        .setMessage(()->"TrafficStreams are still pending for this expiring accumulation: " +
                                accumulation).log());
    }

    public CapturedTrafficToHttpTransactionAccumulator(Duration minTimeout,
                                                       BiConsumer<UniqueRequestKey,HttpMessageAndTimestamp> requestReceivedHandler,
                                                       Consumer<RequestResponsePacketPair> fullDataHandler,
                                                       BiConsumer<UniqueRequestKey,Instant> connectionCloseListener,
                                                       Consumer<Accumulation> onTrafficStreamMissingOnExpiration) {
        liveStreams = new ExpiringTrafficStreamMap(minTimeout, EXPIRATION_GRANULARITY,
                new BehavioralPolicy() {
                    @Override
                    public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                        connectionsExpiredCounter.incrementAndGet();
                        log.atTrace().setMessage(()->"firing accumulation for accum=["
                                + accumulation.getRequestId() + "]=" + accumulation)
                                .log();
                        fireAccumulationsCallbacksAndClose(accumulation);
                    }
                });
        this.requestHandler = requestReceivedHandler;
        this.fullDataHandler = fullDataHandler;
        this.connectionCloseListener = connectionCloseListener;
        this.onTrafficStreamMissingOnExpiration = onTrafficStreamMissingOnExpiration;
    }

    public int numberOfConnectionsCreated() { return liveStreams.numberOfConnectionsCreated(); }
    public int numberOfRequestsOnReusedConnections() { return reusedKeepAliveCounter.get(); }
    public int numberOfConnectionsClosed() { return closedConnectionCounter.get(); }
    public int numberOfConnectionExceptions() { return exceptionConnectionCounter.get(); }
    public int numberOfConnectionsExpired() { return connectionsExpiredCounter.get(); }
    public int numberOfRequestsTerminatedUponAccumulatorClose() {
        return requestsTerminatedUponAccumulatorCloseCounter.get();
    }

    private static String summarizeTrafficStream(TrafficStream ts) {
        return new StringBuilder()
                .append("nodeId: ")
                .append(ts.getNodeId())
                .append(" connId: ")
                .append(ts.getConnectionId())
                .append(" index: ")
                .append(ts.hasNumber() ? ts.getNumber() : ts.getNumberOfThisLastChunk())
                .toString();
    }

    public void accept(TrafficStream yetToBeSequencedTrafficStream) {
        log.atTrace().setMessage(()->"Got trafficStream: "+summarizeTrafficStream(yetToBeSequencedTrafficStream)).log();
        var partitionId = yetToBeSequencedTrafficStream.getNodeId();
        var connectionId = yetToBeSequencedTrafficStream.getConnectionId();
        var accum = liveStreams.getOrCreateWithoutExpiration(partitionId, connectionId);
        var terminated = new AtomicBoolean(false);
        accum.sequenceTrafficStream(yetToBeSequencedTrafficStream,
                ts -> ts.getSubStreamList().stream()
                        .map(o -> {
                            if (terminated.get()) {
                                log.error("Got a traffic observation AFTER a Close observation for the stream " +  ts);
                                return true;
                            }
                            var didTerminate = CONNECTION_STATUS.CLOSED ==
                                    addObservationToAccumulation(ts.getNodeId(), accum, o);
                            terminated.set(didTerminate);
                            return didTerminate;
                        })
                        .takeWhile(b->!b)
                        .forEach(b->{}));
        if (terminated.get()) {
            log.atTrace().setMessage(()->"Connection terminated: removing " + partitionId + ":" + connectionId +
                    " from liveStreams map").log();
            liveStreams.remove(partitionId, connectionId);
        }
    }

    private static enum CONNECTION_STATUS {
        ALIVE, CLOSED
    }

    public CONNECTION_STATUS addObservationToAccumulation(String nodeId, Accumulation accum,
                                                          TrafficObservation observation) {
        var connectionId = accum.rrPair.connectionId.connectionId;
        var timestamp =
                Optional.of(observation.getTs()).map(t->Instant.ofEpochSecond(t.getSeconds(), t.getNanos())).get();
        liveStreams.expireOldEntries(nodeId, accum.rrPair.connectionId.connectionId, accum, timestamp);
        if (observation.hasRead()) {
            rotateAccumulationOnReadIfNecessary(connectionId, accum);
            assert accum.state == Accumulation.State.NOTHING_SENT;
            log.atTrace().setMessage(()->"Adding request data for accum[" + connectionId + "]=" + accum).log();
            accum.rrPair.addRequestData(timestamp, observation.getRead().getData().toByteArray());
            log.atTrace().setMessage(()->"Added request data for accum[" + connectionId + "]=" + accum).log();
        } else if (observation.hasWrite()) {
            assert accum != null && accum.state == Accumulation.State.REQUEST_SENT;
            var runningList = accum.rrPair;
            if (runningList == null) {
                throw new RuntimeException("Apparent out of order exception - " +
                        "found a purported write to a socket before a read!");
            }
            log.atTrace().setMessage(()->"Adding response data for accum[" + connectionId + "]=" + accum).log();
            runningList.addResponseData(timestamp, observation.getWrite().getData().toByteArray());
            log.atTrace().setMessage(()->"Added response data for accum[" + connectionId + "]=" + accum).log();
        } else if (observation.hasEndOfMessageIndicator()) {
            handleEndOfRequest(accum);
        } else if (observation.hasReadSegment()) {
            rotateAccumulationOnReadIfNecessary(connectionId, accum);
            assert accum.state == Accumulation.State.NOTHING_SENT;
            log.atTrace().setMessage(()->"Adding request segment for accum[" + connectionId + "]=" + accum).log();
            if (accum.rrPair.requestData == null) {
                accum.rrPair.requestData = new HttpMessageAndTimestamp.Request(timestamp);
            }
            accum.rrPair.requestData.addSegment(observation.getReadSegment().getData().toByteArray());
            log.atTrace().setMessage(()->"Added request segment for accum[" + connectionId + "]=" + accum).log();
        } else if (observation.hasWriteSegment()) {
            assert accum != null && accum.state == Accumulation.State.REQUEST_SENT;
            log.atTrace().setMessage(()->"Adding response segment for accum[" + connectionId + "]=" + accum).log();
            if (accum.rrPair.responseData == null) {
                accum.rrPair.responseData = new HttpMessageAndTimestamp.Response(timestamp);
            }
            accum.rrPair.responseData.addSegment(observation.getWrite().getData().toByteArray());
            log.atTrace().setMessage(()->"Added response segment for accum[" + connectionId + "]=" + accum).log();
        } else if (observation.hasSegmentEnd()) {
            assert accum != null && accum.state == Accumulation.State.REQUEST_SENT;
            if (accum.rrPair.requestData.hasInProgressSegment()) {
                accum.rrPair.requestData.finalizeRequestSegments(timestamp);
            } else if (accum.rrPair.responseData.hasInProgressSegment()) {
                accum.rrPair.responseData.finalizeRequestSegments(timestamp);
            } else {
                throw new RuntimeException("Got an end of segment indicator, but no segments are in progress");
            }
        } else if (observation.hasClose()) {
            rotateAccumulationIfNecessary(connectionId, accum);
            closedConnectionCounter.incrementAndGet();
            connectionCloseListener.accept(accum.getRequestId(), timestamp);
            return CONNECTION_STATUS.CLOSED;
        } else if (observation.hasConnectionException()) {
            rotateAccumulationIfNecessary(connectionId, accum);
            exceptionConnectionCounter.incrementAndGet();
            accum.resetForNextRequest();
            log.atWarn().setMessage(()->"Removing accumulated traffic pair for " + connectionId).log();
            log.atTrace().setMessage(()->"Accumulated object: " + accum).log();
        } else {
            log.atWarn().setMessage(()->"unaccounted for observation type " + observation).log();
        }
        return CONNECTION_STATUS.ALIVE;
    }

    // This function manages the transition case when an observation comes in that would terminate
    // any previous HTTP transaction for the connection.  It returns true if there WAS a previous
    // transaction that has been reset and false otherwise
    private boolean rotateAccumulationIfNecessary(String connectionId, Accumulation accum) {
        // If this was brand new or if we've already closed the item out and pushed
        // the state to RESPONSE_SENT, we don't need to care about triggering the
        // callback.  We only need to worry about this if we have yet to send the
        // RESPONSE.
        if (accum.state == Accumulation.State.REQUEST_SENT) {
            log.atDebug().setMessage(()->"Resetting accum[" + connectionId + "]=" + accum).log();
            handleEndOfResponse(accum);
            return true;
        }
        return false;
    }

    private boolean  rotateAccumulationOnReadIfNecessary(String connectionId, Accumulation accum) {
        if (rotateAccumulationIfNecessary(connectionId, accum)) {
            reusedKeepAliveCounter.incrementAndGet();
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return True if something was sent to the callback, false if nothing had been accumulated
     */
    private boolean handleEndOfRequest(Accumulation accumulation) {
        assert accumulation.state == Accumulation.State.NOTHING_SENT : "state == " + accumulation.state;
        var requestPacketBytes = accumulation.rrPair.requestData;
        if (requestPacketBytes != null) {
            requestHandler.accept(accumulation.getRequestId(), requestPacketBytes);
            accumulation.state = Accumulation.State.REQUEST_SENT;
            return true;
        } else {
            log.warn("Received EOM w/out an accumulated value, assuming an empty server interaction and " +
                    "NOT reproducing this to the target cluster (TODO - do something better?)");
            accumulation.resetForNextRequest();
            return false;
        }
    }

    private void handleEndOfResponse(Accumulation accumulation) {
        assert accumulation.state == Accumulation.State.REQUEST_SENT;
        fullDataHandler.accept(accumulation.rrPair);
        accumulation.resetForNextRequest();
    }

    public void close() {
        liveStreams.values().forEach(accum -> {
            if (accum.state != Accumulation.State.RESPONSE_SENT) {
                requestsTerminatedUponAccumulatorCloseCounter.incrementAndGet();
            }
            fireAccumulationsCallbacksAndClose(accum);
        });
        liveStreams.clear();
    }

    private void fireAccumulationsCallbacksAndClose(Accumulation accumulation) {
        try {
            if (accumulation.trafficStreamsSorter.hasPending()) {
                onTrafficStreamMissingOnExpiration.accept(accumulation);
            }
            switch (accumulation.state) {
                case NOTHING_SENT:
                    if (!handleEndOfRequest(accumulation)) {
                        return;
                    }
                    // fall through
                case REQUEST_SENT:
                    handleEndOfResponse(accumulation);
            }
        } finally {
            switch (accumulation.state) {
                case REQUEST_SENT:
                case RESPONSE_SENT:
                    connectionCloseListener.accept(accumulation.getRequestId(),
                            accumulation.rrPair.getLastTimestamp().get());
                default:
                    ; // nothing
            }
        }
    }
}
