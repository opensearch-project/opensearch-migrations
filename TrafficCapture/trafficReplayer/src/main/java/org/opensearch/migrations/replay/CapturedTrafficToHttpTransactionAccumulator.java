package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.traffic.expiration.BehavioralPolicy;
import org.opensearch.migrations.replay.traffic.expiration.ExpiringTrafficStreamMap;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.StringJoiner;
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
    private final BiConsumer<UniqueRequestKey,Instant> connectionCloseListener;

    private final AtomicInteger requestCounter = new AtomicInteger();
    private final AtomicInteger reusedKeepAliveCounter = new AtomicInteger();
    private final AtomicInteger closedConnectionCounter = new AtomicInteger();
    private final AtomicInteger exceptionConnectionCounter = new AtomicInteger();
    private final AtomicInteger connectionsExpiredCounter = new AtomicInteger();
    private final AtomicInteger requestsTerminatedUponAccumulatorCloseCounter = new AtomicInteger();

    public String getStatsString() {
        return new StringJoiner(" ")
                .add("requests: "+requestCounter.get())
                .add("reused: "+reusedKeepAliveCounter.get())
                .add("closed: "+closedConnectionCounter.get())
                .add("expired: "+connectionsExpiredCounter.get())
                .add("hardClosedAtShutdown: "+requestsTerminatedUponAccumulatorCloseCounter.get())
                .toString();
    }

    private final static MetricsLogger metricsLogger = new MetricsLogger("CapturedTrafficToHttpTransactionAccumulator");

    public CapturedTrafficToHttpTransactionAccumulator(Duration minTimeout, String hintStringToConfigureTimeout,
                                                       BiConsumer<UniqueRequestKey,HttpMessageAndTimestamp> requestReceivedHandler,
                                                       Consumer<RequestResponsePacketPair> fullDataHandler,
                                                       BiConsumer<UniqueRequestKey,Instant> connectionCloseListener)
    {
        liveStreams = new ExpiringTrafficStreamMap(minTimeout, EXPIRATION_GRANULARITY,
                new BehavioralPolicy() {
                    @Override
                    public String appendageToDescribeHowToSetMinimumGuaranteedLifetime() {
                        return hintStringToConfigureTimeout;
                    }

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
                .append(TrafficStreamUtils.getTrafficStreamIndex(ts))
                .append(" firstTimestamp: ")
                .append(ts.getSubStreamList().stream().findFirst()
                        .map(tso -> tso.getTs()).map(TrafficStreamUtils::instantFromProtoTimestamp)
                        .map(instant->instant.toString())
                        .orElse("[None]"))
                .toString();
    }

    public void accept(ITrafficStreamWithKey trafficStreamAndKey) {
        var yetToBeSequencedTrafficStream = trafficStreamAndKey.getStream();
        log.atTrace().setMessage(()->"Got trafficStream: "+summarizeTrafficStream(yetToBeSequencedTrafficStream)).log();
        var partitionId = yetToBeSequencedTrafficStream.getNodeId();
        var connectionId = yetToBeSequencedTrafficStream.getConnectionId();
        var accum = liveStreams.getOrCreateWithoutExpiration(trafficStreamAndKey.getKey(),
                tsk->createInitialAccumulation(trafficStreamAndKey));
        var terminated = new AtomicBoolean(false);
        var trafficStream = trafficStreamAndKey.getStream();
        trafficStream.getSubStreamList().stream()
                        .map(o -> {
                            if (terminated.get()) {
                                log.error("Got a traffic observation AFTER a Close observation for the stream " +
                                        trafficStream);
                                return true;
                            }
                            var didTerminate = CONNECTION_STATUS.CLOSED == addObservationToAccumulation(accum, o);
                            terminated.set(didTerminate);
                            return didTerminate;
                        })
                        .takeWhile(b->!b)
                        .forEach(b->{});
        if (terminated.get()) {
            log.atInfo().setMessage(()->"Connection terminated: removing " + partitionId + ":" + connectionId +
                    " from liveStreams map").log();
            liveStreams.remove(partitionId, connectionId);
        }
    }

    private Accumulation createInitialAccumulation(ITrafficStreamWithKey streamWithKey) {
        var stream = streamWithKey.getStream();
        var key = streamWithKey.getKey();

        if (key.getTrafficStreamIndex() == 0 &&
                (stream.getRequestCount() > 0 || stream.getLastObservationWasUnterminatedRead())) {
            log.atWarn().setMessage(()->"Encountered a TrafficStream object with inconsistent values between " +
                    "the prior request count (" + stream.getRequestCount() + ", " +
                    "lastObservationWasUnterminatedRead (" + stream.getLastObservationWasUnterminatedRead() +
                    ") and the index (" + key.getTrafficStreamIndex() +
                    ").  Traffic Observations will be ignored until Reads after the next EndOfMessage" +
                    " are encountered.   Full stream object=" + stream).log();
        }

        var requestKey = new UniqueRequestKey(key, stream.getRequestCount(), 0);
        return new Accumulation(requestKey, stream.getLastObservationWasUnterminatedRead());
    }

    private static enum CONNECTION_STATUS {
        ALIVE, CLOSED
    }

    public CONNECTION_STATUS addObservationToAccumulation(Accumulation accum,
                                                          TrafficObservation observation) {
        var connectionId = accum.rrPair.requestKey.getTrafficStreamKey().getConnectionId();
        var timestamp =
                Optional.of(observation.getTs()).map(t-> TrafficStreamUtils.instantFromProtoTimestamp(t)).get();
        liveStreams.expireOldEntries(accum.rrPair.requestKey.getTrafficStreamKey(), accum, timestamp);

        if (accum.state == Accumulation.State.IGNORING_LAST_REQUEST) {
            if (observation.hasWrite() || observation.hasWriteSegment() || observation.hasEndOfMessageIndicator()) {
                accum.state = Accumulation.State.WAITING_FOR_NEXT_READ_CHUNK;
            }
            // ignore everything until we hit an EOM
            return CONNECTION_STATUS.ALIVE;
        } else if (accum.state == Accumulation.State.WAITING_FOR_NEXT_READ_CHUNK) {
            // already processed EOMs above.  Be on the lookout to ignore writes
            if (!(observation.hasRead() || observation.hasReadSegment())) {
                return CONNECTION_STATUS.ALIVE;
            } else {
                accum.state = Accumulation.State.NOTHING_SENT;
                // fall through
            }
        }
        if (observation.hasRead()) {
            rotateAccumulationOnReadIfNecessary(connectionId, accum);
            assert accum.state == Accumulation.State.NOTHING_SENT;
            if (accum.rrPair.requestData == null) {
                requestCounter.incrementAndGet();
            }
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
                requestCounter.incrementAndGet();
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
            log.atDebug().setMessage(()->"Removing accumulated traffic pair due to " +
                    "recorded connection exception event for " + connectionId).log();
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
        metricsLogger.atSuccess()
                .addKeyValue("requestId", accumulation.getRequestId())
                .addKeyValue("connectionId", accumulation.getRequestId().getTrafficStreamKey().getConnectionId())
                .setMessage("Full captured source request was accumulated").log();
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
        metricsLogger.atSuccess()
                .addKeyValue("requestId", accumulation.getRequestId())
                .addKeyValue("connectionId", accumulation.getRequestId().getTrafficStreamKey().getConnectionId())
                .setMessage("Full captured source response was accumulated").log();
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
            switch (accumulation.state) {
                case NOTHING_SENT:
                    if (!handleEndOfRequest(accumulation)) {
                        return;
                    }
                    // fall through
                case REQUEST_SENT:
                    handleEndOfResponse(accumulation);
                default:
                    // do nothing for IGNORING... and RESPONSE_SENT
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
