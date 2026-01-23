package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.expiration.BehavioralPolicy;
import org.opensearch.migrations.replay.traffic.expiration.ExpiringTrafficStreamMap;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

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
    private final SpanWrappingAccumulationCallbacks listener;

    private final AtomicInteger requestCounter = new AtomicInteger();
    private final AtomicInteger reusedKeepAliveCounter = new AtomicInteger();
    private final AtomicInteger closedConnectionCounter = new AtomicInteger();
    private final AtomicInteger exceptionConnectionCounter = new AtomicInteger();
    private final AtomicInteger connectionsExpiredCounter = new AtomicInteger();
    private final AtomicInteger requestsTerminatedUponAccumulatorCloseCounter = new AtomicInteger();

    public String getStatsString() {
        return new StringJoiner(" ").add("requests: " + requestCounter.get())
            .add("reused: " + reusedKeepAliveCounter.get())
            .add("closed: " + closedConnectionCounter.get())
            .add("expired: " + connectionsExpiredCounter.get())
            .add("hardClosedAtShutdown: " + requestsTerminatedUponAccumulatorCloseCounter.get())
            .toString();
    }

    public CapturedTrafficToHttpTransactionAccumulator(
        Duration minTimeout,
        String hintStringToConfigureTimeout,
        AccumulationCallbacks accumulationCallbacks
    ) {
        liveStreams = new ExpiringTrafficStreamMap(minTimeout, EXPIRATION_GRANULARITY, new BehavioralPolicy() {
            @Override
            public String appendageToDescribeHowToSetMinimumGuaranteedLifetime() {
                return hintStringToConfigureTimeout;
            }

            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                connectionsExpiredCounter.incrementAndGet();
                log.atTrace().setMessage("firing accumulation for accum=[{}]={}")
                    .addArgument(() -> accumulation.getRrPair().getBeginningTrafficStreamKey())
                    .addArgument(accumulation)
                    .log();
                fireAccumulationsCallbacksAndClose(
                    accumulation,
                    RequestResponsePacketPair.ReconstructionStatus.EXPIRED_PREMATURELY
                );
            }
        });
        this.listener = new SpanWrappingAccumulationCallbacks(accumulationCallbacks);
    }

    @AllArgsConstructor
    private static class SpanWrappingAccumulationCallbacks {
        private final AccumulationCallbacks underlying;

        public Consumer<RequestResponsePacketPair> onRequestReceived(
            IReplayContexts.IRequestAccumulationContext requestCtx,
            @NonNull HttpMessageAndTimestamp request
        ) {
            requestCtx.close();
            var innerCallback = underlying.onRequestReceived(requestCtx.getLogicalEnclosingScope(), request);
            return rrpp -> {
                rrpp.getResponseContext().close();
                innerCallback.accept(rrpp);
            };
        }

        public void onConnectionClose(
            @NonNull Accumulation accum,
            RequestResponsePacketPair.ReconstructionStatus status,
            @NonNull Instant when,
            @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
        ) {
            var tsCtx = accum.trafficChannelKey.getTrafficStreamsContext();
            underlying.onConnectionClose(
                accum.numberOfResets.get(),
                tsCtx.getLogicalEnclosingScope(),
                accum.startingSourceRequestIndex,
                status,
                when,
                trafficStreamKeysBeingHeld
            );
        }

        public void onTrafficStreamsExpired(
            RequestResponsePacketPair.ReconstructionStatus status,
            IReplayContexts.ITrafficStreamsLifecycleContext tsCtx,
            @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
        ) {
            underlying.onTrafficStreamsExpired(status, tsCtx.getLogicalEnclosingScope(), trafficStreamKeysBeingHeld);
        }

        public void onTrafficStreamIgnored(@NonNull ITrafficStreamKey tsk) {
            underlying.onTrafficStreamIgnored(tsk.getTrafficStreamsContext());
        }
    }

    public int numberOfConnectionsCreated() {
        return liveStreams.numberOfConnectionsCreated();
    }

    public int numberOfRequestsOnReusedConnections() {
        return reusedKeepAliveCounter.get();
    }

    public int numberOfConnectionsClosed() {
        return closedConnectionCounter.get();
    }

    public int numberOfConnectionExceptions() {
        return exceptionConnectionCounter.get();
    }

    public int numberOfConnectionsExpired() {
        return connectionsExpiredCounter.get();
    }

    public int numberOfRequestsTerminatedUponAccumulatorClose() {
        return requestsTerminatedUponAccumulatorCloseCounter.get();
    }

    private static String summarizeTrafficStream(TrafficStream ts) {
        return new StringBuilder().append("nodeId: ")
            .append(ts.getNodeId())
            .append(" connId: ")
            .append(ts.getConnectionId())
            .append(" index: ")
            .append(TrafficStreamUtils.getTrafficStreamIndex(ts))
            .append(" firstTimestamp: ")
            .append(
                ts.getSubStreamList()
                    .stream()
                    .findFirst()
                    .map(tso -> tso.getTs())
                    .map(TrafficStreamUtils::instantFromProtoTimestamp)
                    .map(Object::toString)
                    .orElse("[None]")
            )
            .toString();
    }

    public void accept(ITrafficStreamWithKey trafficStreamAndKey) {
        var yetToBeSequencedTrafficStream = trafficStreamAndKey.getStream();
        log.atTrace().setMessage("Got trafficStream: {}")
            .addArgument(() -> summarizeTrafficStream(yetToBeSequencedTrafficStream))
            .log();
        var partitionId = yetToBeSequencedTrafficStream.getNodeId();
        var connectionId = yetToBeSequencedTrafficStream.getConnectionId();
        var tsk = trafficStreamAndKey.getKey();
        var accum = liveStreams.getOrCreateWithoutExpiration(tsk, k -> createInitialAccumulation(trafficStreamAndKey));
        var trafficStream = trafficStreamAndKey.getStream();
        for (int i = 0; i < trafficStream.getSubStreamCount(); ++i) {
            var o = trafficStream.getSubStreamList().get(i);
            var connectionStatus = addObservationToAccumulation(accum, tsk, o);
            if (CONNECTION_STATUS.CLOSED == connectionStatus) {
                log.atInfo().setMessage("Connection terminated: removing {}:{} from liveStreams map")
                    .addArgument(partitionId)
                    .addArgument(connectionId)
                    .log();
                liveStreams.remove(partitionId, connectionId);
                break;
            }
        }
        if (accum.hasRrPair()) {
            accum.getRrPair().holdTrafficStream(tsk);
        } else if (!trafficStream.getSubStream(trafficStream.getSubStreamCount() - 1).hasClose()) {
            assert accum.state == Accumulation.State.WAITING_FOR_NEXT_READ_CHUNK
                || accum.state == Accumulation.State.IGNORING_LAST_REQUEST
                || trafficStream.getSubStreamCount() == 0;
            listener.onTrafficStreamIgnored(tsk);
        }
    }

    private Accumulation createInitialAccumulation(ITrafficStreamWithKey streamWithKey) {
        var stream = streamWithKey.getStream();
        var key = streamWithKey.getKey();

        if (key.getTrafficStreamIndex() == 0
            && (stream.getPriorRequestsReceived() > 0 || stream.getLastObservationWasUnterminatedRead())) {
            log.atWarn()
                .setMessage("Encountered a TrafficStream object with inconsistent values between the prior request count ({}, lastObservationWasUnterminatedRead ({}) and the index ({}). Traffic Observations will be ignored until Reads after the next EndOfMessage are encountered. Full stream object={}")
                .addArgument(stream::getPriorRequestsReceived)
                .addArgument(stream::getLastObservationWasUnterminatedRead)
                .addArgument(key::getTrafficStreamIndex)
                .addArgument(stream)
                .log();
        }

        return new Accumulation(streamWithKey.getKey(), stream);
    }

    private enum CONNECTION_STATUS {
        ALIVE,
        CLOSED
    }

    public CONNECTION_STATUS addObservationToAccumulation(
        @NonNull Accumulation accum,
        @NonNull ITrafficStreamKey trafficStreamKey,
        TrafficObservation observation
    ) {
        log.atTrace().setMessage("Adding observation: {} with state={}")
            .addArgument(observation)
            .addArgument(accum.state)
            .log();
        var timestamp = TrafficStreamUtils.instantFromProtoTimestamp(observation.getTs());
        liveStreams.expireOldEntries(trafficStreamKey, accum, timestamp);

        return handleCloseObservationThatAffectEveryState(accum, observation, trafficStreamKey, timestamp).or(
            () -> handleObservationForSkipState(accum, observation)
        )
            .or(() -> handleObservationForReadState(accum, observation, trafficStreamKey, timestamp))
            .or(() -> handleObservationForWriteState(accum, observation, trafficStreamKey, timestamp))
            .orElseGet(() -> {
                log.atWarn().setMessage("unaccounted for observation type {} for {}")
                    .addArgument(observation)
                    .addArgument(accum.trafficChannelKey)
                    .log();
                return CONNECTION_STATUS.ALIVE;
            });
    }

    private Optional<CONNECTION_STATUS> handleObservationForSkipState(
        Accumulation accum,
        TrafficObservation observation
    ) {
        assert !observation.hasClose() : "close will be handled earlier in handleCloseObservationThatAffectEveryState";
        if (accum.state == Accumulation.State.IGNORING_LAST_REQUEST) {
            if (observation.hasWrite() || observation.hasWriteSegment() || observation.hasEndOfMessageIndicator()) {
                accum.state = Accumulation.State.WAITING_FOR_NEXT_READ_CHUNK;
            } else if (observation.hasRequestDropped()) {
                handleDroppedRequestForAccumulation(accum);
            }
            // ignore everything until we hit an EOM
            return Optional.of(CONNECTION_STATUS.ALIVE);
        } else if (accum.state == Accumulation.State.WAITING_FOR_NEXT_READ_CHUNK) {
            // already processed EOMs above. Be on the lookout to ignore writes
            if (!(observation.hasRead() || observation.hasReadSegment())) {
                return Optional.of(CONNECTION_STATUS.ALIVE);
            } else {
                accum.state = Accumulation.State.ACCUMULATING_READS;
            }
        }
        return Optional.empty();
    }

    private static List<ITrafficStreamKey> getTrafficStreamsHeldByAccum(Accumulation accum) {
        return accum.hasRrPair() ? accum.getRrPair().trafficStreamKeysBeingHeld : List.of();
    }

    private Optional<CONNECTION_STATUS> handleCloseObservationThatAffectEveryState(
        Accumulation accum,
        TrafficObservation observation,
        @NonNull ITrafficStreamKey trafficStreamKey,
        Instant timestamp
    ) {
        var originTimestamp = TrafficStreamUtils.instantFromProtoTimestamp(observation.getTs());
        if (observation.hasClose()) {
            accum.getOrCreateTransactionPair(trafficStreamKey, originTimestamp).holdTrafficStream(trafficStreamKey);
            var heldTrafficStreams = getTrafficStreamsHeldByAccum(accum);
            if (rotateAccumulationIfNecessary(trafficStreamKey.getConnectionId(), accum)) {
                heldTrafficStreams = List.of();
            }
            closedConnectionCounter.incrementAndGet();
            listener.onConnectionClose(
                accum,
                RequestResponsePacketPair.ReconstructionStatus.COMPLETE,
                timestamp,
                heldTrafficStreams
            );
            return Optional.of(CONNECTION_STATUS.CLOSED);
        } else if (observation.hasConnectionException()) {
            accum.getOrCreateTransactionPair(trafficStreamKey, originTimestamp).holdTrafficStream(trafficStreamKey);
            rotateAccumulationIfNecessary(trafficStreamKey.getConnectionId(), accum);
            exceptionConnectionCounter.incrementAndGet();
            accum.resetForNextRequest();
            log.atDebug()
                .setMessage("Removing accumulated traffic pair due to recorded connection exception event for {}")
                .addArgument(trafficStreamKey::getConnectionId)
                .log();
            log.atTrace().setMessage("Accumulated object: {}").addArgument(accum).log();
            return Optional.of(CONNECTION_STATUS.ALIVE);
        }
        return Optional.empty();
    }

    private Optional<CONNECTION_STATUS> handleObservationForReadState(
        @NonNull Accumulation accum,
        TrafficObservation observation,
        @NonNull ITrafficStreamKey trafficStreamKey,
        Instant timestamp
    ) {
        if (accum.state != Accumulation.State.ACCUMULATING_READS) {
            return Optional.empty();
        }

        var connectionId = trafficStreamKey.getConnectionId();
        var originTimestamp = TrafficStreamUtils.instantFromProtoTimestamp(observation.getTs());
        if (observation.hasRead()) {
            if (!accum.hasRrPair()) {
                requestCounter.incrementAndGet();
            }
            var rrPair = accum.getOrCreateTransactionPair(trafficStreamKey, originTimestamp);
            log.atTrace().setMessage("Adding request data for accum[{}]={}")
                .addArgument(connectionId)
                .addArgument(accum).log();
            rrPair.addRequestData(timestamp, observation.getRead().getData().toByteArray());
            log.atTrace().setMessage("Added request data for accum[{}]={}")
                .addArgument(connectionId)
                .addArgument(accum)
                .log();
        } else if (observation.hasEndOfMessageIndicator()) {
            assert accum.hasRrPair();
            handleEndOfRequest(accum);
        } else if (observation.hasReadSegment()) {
            log.atTrace().setMessage("Adding request segment for accum[{}]={}")
                .addArgument(connectionId).
                addArgument(accum)
                .log();
            var rrPair = accum.getOrCreateTransactionPair(trafficStreamKey, originTimestamp);
            if (rrPair.requestData == null) {
                rrPair.requestData = new HttpMessageAndTimestamp.Request(timestamp);
                requestCounter.incrementAndGet();
            }
            rrPair.addRequestData(timestamp, observation.getRead().getData().toByteArray());
            rrPair.requestData.addSegment(observation.getReadSegment().getData().toByteArray());
            log.atTrace().setMessage("Added request segment for accum[{}]={}")
                .addArgument(connectionId)
                .addArgument(accum)
                .log();
        } else if (observation.hasSegmentEnd()) {
            var rrPair = accum.getRrPair();
            assert rrPair.requestData.hasInProgressSegment();
            rrPair.requestData.finalizeRequestSegments(timestamp);
        } else if (observation.hasRequestDropped()) {
            requestCounter.decrementAndGet();
            handleDroppedRequestForAccumulation(accum);
        } else {
            return Optional.empty();
        }
        return Optional.of(CONNECTION_STATUS.ALIVE);
    }

    private Optional<CONNECTION_STATUS> handleObservationForWriteState(
        Accumulation accum,
        TrafficObservation observation,
        @NonNull ITrafficStreamKey trafficStreamKey,
        Instant timestamp
    ) {
        if (accum.state != Accumulation.State.ACCUMULATING_WRITES) {
            return Optional.empty();
        }

        var connectionId = trafficStreamKey.getConnectionId();
        if (observation.hasWrite()) {
            var rrPair = accum.getRrPair();
            log.atTrace().setMessage("Adding response data for accum[{}]={}")
                .addArgument(connectionId)
                .addArgument(accum)
                .log();
            rrPair.addResponseData(timestamp, observation.getWrite().getData().toByteArray());
            log.atTrace().setMessage("Added response data for accum[{}]={}")
                .addArgument(connectionId)
                .addArgument(accum)
                .log();
        } else if (observation.hasWriteSegment()) {
            log.atTrace().setMessage("Adding response segment for accum[{}]={}")
                .addArgument(connectionId)
                .addArgument(accum)
                .log();
            var rrPair = accum.getRrPair();
            if (rrPair.responseData == null) {
                rrPair.responseData = new HttpMessageAndTimestamp.Response(timestamp);
            }
            rrPair.responseData.addSegment(observation.getWriteSegment().getData().toByteArray());
            log.atTrace().setMessage("Added response segment for accum[{}]={}")
                .addArgument(connectionId)
                .addArgument(accum)
                .log();
        } else if (observation.hasSegmentEnd()) {
            var rrPair = accum.getRrPair();
            assert rrPair.responseData.hasInProgressSegment();
            rrPair.responseData.finalizeRequestSegments(timestamp);
        } else if (observation.hasRead() || observation.hasReadSegment()) {
            rotateAccumulationOnReadIfNecessary(connectionId, accum);
            return handleObservationForReadState(accum, observation, trafficStreamKey, timestamp);
        } else {
            return Optional.empty();
        }
        return Optional.of(CONNECTION_STATUS.ALIVE);
    }

    private void handleDroppedRequestForAccumulation(Accumulation accum) {
        if (accum.hasRrPair()) {
            var rrPair = accum.getRrPair();
            rrPair.getTrafficStreamsHeld().forEach(listener::onTrafficStreamIgnored);
        }
        log.atTrace().setMessage("resetting to forget {}").addArgument(accum.trafficChannelKey).log();
        accum.resetToIgnoreAndForgetCurrentRequest();
        log.atTrace().setMessage("done resetting to forget and accum={}").addArgument(accum).log();
    }

    // This function manages the transition case when an observation comes in that would terminate
    // any previous HTTP transaction for the connection. It returns true if there WAS a previous
    // transaction that has been reset and false otherwise
    private boolean rotateAccumulationIfNecessary(String connectionId, Accumulation accum) {
        // If this was brand new, we don't need to care about triggering the callback.
        // We only need to worry about this if we have yet to send the RESPONSE.
        if (accum.state == Accumulation.State.ACCUMULATING_WRITES) {
            log.atDebug().setMessage("handling EOM for accum[{}]={}").addArgument(connectionId).addArgument(accum).log();
            handleEndOfResponse(accum, RequestResponsePacketPair.ReconstructionStatus.COMPLETE);
            return true;
        }
        return false;
    }

    private boolean rotateAccumulationOnReadIfNecessary(String connectionId, Accumulation accum) {
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
        assert accumulation.state == Accumulation.State.ACCUMULATING_READS : "state == " + accumulation.state;
        var rrPairWithCallback = accumulation.getRrPairWithCallback();
        var rrPair = rrPairWithCallback.pair;
        var httpMessage = rrPair.requestData;
        assert (httpMessage != null);
        assert (!httpMessage.hasInProgressSegment());
        var requestCtx = rrPair.getRequestContext();
        rrPair.rotateRequestGatheringToResponse();
        var callbackTrackedData = listener.onRequestReceived(requestCtx, httpMessage);
        rrPairWithCallback.setFullDataContinuation(callbackTrackedData);
        accumulation.state = Accumulation.State.ACCUMULATING_WRITES;
        return true;
    }

    private void handleEndOfResponse(Accumulation accumulation, RequestResponsePacketPair.ReconstructionStatus status) {
        assert accumulation.state == Accumulation.State.ACCUMULATING_WRITES;
        var rrPairWithCallback = accumulation.getRrPairWithCallback();
        var rrPair = rrPairWithCallback.pair;
        rrPair.completionStatus = status;
        rrPairWithCallback.getFullDataContinuation().accept(rrPair);
        log.atTrace().setMessage("resetting for end of response").log();
        accumulation.resetForNextRequest();
    }

    public void close() {
        liveStreams.values().forEach(accum -> {
            requestsTerminatedUponAccumulatorCloseCounter.incrementAndGet();
            fireAccumulationsCallbacksAndClose(
                accum,
                RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY
            );
        });
        liveStreams.clear();
    }

    private void fireAccumulationsCallbacksAndClose(
        Accumulation accumulation,
        RequestResponsePacketPair.ReconstructionStatus status
    ) {
        try {
            switch (accumulation.state) {
                case ACCUMULATING_READS:
                    // This is a safer bet than sending a partial response. If we drop 1 in a million requests
                    // where the next TrafficStream had an EOM message and that TrafficStream was dropped, we'll
                    // NOT send many more requests that never would have made it to the source cluster because
                    // they weren't well-formed requests in the first place.
                    //
                    // It might be advantageous to replicate these to provide stress to the target server, but
                    // it's a difficult decision and one to be managed with a policy.
                    // TODO - add Jira/github issue here.
                    log.atWarn()
                        .setMessage("Terminating a TrafficStream reconstruction before data was accumulated "
                                + "for {} assuming an empty server interaction and NOT "
                                + "reproducing this to the target cluster.")
                        .addArgument(accumulation.trafficChannelKey)
                        .log();
                    if (accumulation.hasRrPair()) {
                        listener.onTrafficStreamsExpired(
                            status,
                            accumulation.trafficChannelKey.getTrafficStreamsContext(),
                            Collections.unmodifiableList(accumulation.getRrPair().trafficStreamKeysBeingHeld)
                        );
                    }
                    return;
                case ACCUMULATING_WRITES:
                    handleEndOfResponse(accumulation, status);
                    break;
                case WAITING_FOR_NEXT_READ_CHUNK:
                case IGNORING_LAST_REQUEST:
                    break;
                default:
                    throw new IllegalStateException("Unknown enum type: " + accumulation.state);
            }
        } finally {
            if (accumulation.hasSignaledRequests()) {
                listener.onConnectionClose(
                    accumulation,
                    status,
                    accumulation.getLastTimestamp(),
                    getTrafficStreamsHeldByAccum(accumulation)
                );
            }
        }
    }
}
