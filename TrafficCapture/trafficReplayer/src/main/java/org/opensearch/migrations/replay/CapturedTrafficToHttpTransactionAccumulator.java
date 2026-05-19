package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.opensearch.migrations.Utils;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.kafka.TrafficSourceReaderInterruptedClose;
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
    private final Duration connectionTimeout;

    private final AtomicInteger requestCounter = new AtomicInteger();
    private final AtomicInteger reusedKeepAliveCounter = new AtomicInteger();
    private final AtomicInteger closedConnectionCounter = new AtomicInteger();
    private final AtomicInteger exceptionConnectionCounter = new AtomicInteger();
    private final AtomicInteger connectionsExpiredCounter = new AtomicInteger();
    private final AtomicInteger requestsTerminatedUponAccumulatorCloseCounter = new AtomicInteger();
    private static final org.slf4j.Logger heartbeatLogger =
        org.slf4j.LoggerFactory.getLogger("AccumulatorHeartbeat");

    public String getStatsString() {
        return new StringJoiner(" ").add("requests: " + requestCounter.get())
            .add("reused: " + reusedKeepAliveCounter.get())
            .add("closed: " + closedConnectionCounter.get())
            .add("expired: " + connectionsExpiredCounter.get())
            .add("hardClosedAtShutdown: " + requestsTerminatedUponAccumulatorCloseCounter.get())
            .toString();
    }

    /** Emit a periodic heartbeat log summarizing the accumulator state. */
    public void logHeartbeat() {
        var sb = new StringBuilder();
        int waiting = 0;
        int reads = 0;
        int writes = 0;
        int ignoring = 0;
        String oldestWriteConn = null;
        long oldestWriteLastPacketAgeMs = 0;
        String oldestWriteOffset = null;

        var now = System.currentTimeMillis();
        var allAccumulations = liveStreams.values().collect(java.util.stream.Collectors.toList());
        int liveConnections = allAccumulations.size();

        for (var accum : allAccumulations) {
            switch (accum.state) {
                case WAITING_FOR_NEXT_READ_CHUNK: waiting++; break;
                case ACCUMULATING_READS: reads++; break;
                case ACCUMULATING_WRITES: writes++; break;
                case IGNORING_LAST_REQUEST: ignoring++; break;
            }
            if (accum.state == Accumulation.State.ACCUMULATING_WRITES) {
                var lastPacketMs = accum.getNewestPacketTimestampInMillisReference().get();
                var lastPacketAge = lastPacketMs > 0 ? now - lastPacketMs : 0;
                // Use lastPacketAge as a proxy for how long this accumulation has been stuck
                if (lastPacketAge > oldestWriteLastPacketAgeMs) {
                    oldestWriteLastPacketAgeMs = lastPacketAge;
                    oldestWriteConn = accum.trafficChannelKey.getConnectionId();
                    oldestWriteOffset = accum.trafficChannelKey.toString();
                }
            }
        }

        sb.append("liveConnections=").append(liveConnections);
        sb.append(" byState={WAITING=").append(waiting)
            .append(", READS=").append(reads)
            .append(", WRITES=").append(writes)
            .append(", IGNORING=").append(ignoring).append("}");

        if (oldestWriteConn != null) {
            sb.append(" oldestInWrites={conn=").append(oldestWriteConn)
                .append(", lastPacketAge=").append(Utils.formatDurationInSeconds(Duration.ofMillis(oldestWriteLastPacketAgeMs)))
                .append(", tsk=").append(oldestWriteOffset);
            sb.append("}");
        }

        sb.append(" expiryConfig=").append(Utils.formatDurationInSeconds(connectionTimeout));
        sb.append(" totals={requests=").append(requestCounter.get())
            .append(", closed=").append(closedConnectionCounter.get())
            .append(", expired=").append(connectionsExpiredCounter.get())
            .append(", exceptions=").append(exceptionConnectionCounter.get()).append("}");

        heartbeatLogger.atInfo().setMessage("{}").addArgument(sb).log();
    }


    public CapturedTrafficToHttpTransactionAccumulator(
        Duration minTimeout,
        String hintStringToConfigureTimeout,
        AccumulationCallbacks accumulationCallbacks
    ) {
        this.connectionTimeout = minTimeout;
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
            @NonNull HttpMessageAndTimestamp request,
            boolean isResumedConnection
        ) {
            requestCtx.close();
            var innerCallback = underlying.onRequestReceived(requestCtx.getLogicalEnclosingScope(), request, isResumedConnection);
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
        var tsk = trafficStreamAndKey.getKey();
        // Synthetic close from partition reassignment
        if (trafficStreamAndKey instanceof TrafficSourceReaderInterruptedClose) {
            var partitionId = tsk.getNodeId();
            var connectionId = tsk.getConnectionId();

            var existingAccum = liveStreams.getIfPresent(tsk);
            if (existingAccum != null) {
                // fireAccumulationsCallbacksAndClose with TRAFFIC_SOURCE_READER_INTERRUPTED status:
                // - completes finishedAccumulatingResponseFuture for any in-flight request
                //   (ACCUMULATING_WRITES state), allowing the OnlineRadixSorter to drain
                // - fires onConnectionClose(TRAFFIC_SOURCE_READER_INTERRUPTED) in its finally block,
                //   which calls replayEngine.closeConnection() to schedule the channel close after
                //   any in-flight requests complete
                fireAccumulationsCallbacksAndClose(
                    existingAccum,
                    RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED
                );
                liveStreams.remove(partitionId, connectionId);
            } else {
                // No accumulation — fire onConnectionClose directly so replayEngine.closeConnection
                // is called and the session drains
                tsk.getTrafficStreamsContext().close();
                listener.underlying.onConnectionClose(
                    0,
                    tsk.getTrafficStreamsContext().getLogicalEnclosingScope(),
                    0,
                    RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED,
                    Instant.now(),
                    List.of(tsk)
                );
            }
            return;
        }

        var yetToBeSequencedTrafficStream = trafficStreamAndKey.getStream();
        TrafficStreamUtils.requireSupportedCaptureFormatVersion(yetToBeSequencedTrafficStream);
        log.atDebug().setMessage("accept() trafficStream: {} state={}")
            .addArgument(() -> summarizeTrafficStream(yetToBeSequencedTrafficStream))
            .addArgument(() -> {
                var existing = liveStreams.getIfPresent(trafficStreamAndKey.getKey());
                return existing != null ? existing.state : "NEW";
            })
            .log();
        var partitionId = yetToBeSequencedTrafficStream.getNodeId();
        var connectionId = yetToBeSequencedTrafficStream.getConnectionId();

        // If the incoming key has a higher generation than the stored accumulation, the partition
        // was revoked and reassigned without a TrafficSourceReaderInterruptedClose being processed
        // for this connection. This should not happen in normal operation — the interrupted-close
        // path should have cleaned up the accumulation before new-generation data arrives.
        // Log an error and discard the stale accumulation defensively.
        var existingAccum = liveStreams.getIfPresent(tsk);
        if (existingAccum != null && existingAccum.sourceGeneration < tsk.getSourceGeneration()) {
            log.atError().setMessage("Stale accumulation found for {}:{} (stored gen={}, incoming gen={}) — " +
                    "TrafficSourceReaderInterruptedClose was not processed for this connection. " +
                    "This indicates a gap in interrupted-close coverage.")
                .addArgument(partitionId)
                .addArgument(connectionId)
                .addArgument(existingAccum.sourceGeneration)
                .addArgument(tsk::getSourceGeneration)
                .log();
            fireAccumulationsCallbacksAndClose(
                existingAccum,
                RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY
            );
            liveStreams.remove(partitionId, connectionId);
        }

        var accum = liveStreams.getOrCreateWithoutExpiration(tsk, k -> createInitialAccumulation(trafficStreamAndKey));
        var trafficStream = trafficStreamAndKey.getStream();
        for (int i = 0; i < trafficStream.getSubStreamCount(); ++i) {
            var o = trafficStream.getSubStreamList().get(i);
            log.atTrace().setMessage("Processing obs {} of {} for {}:{} state={} type={}")
                .addArgument(i)
                .addArgument(trafficStream::getSubStreamCount)
                .addArgument(partitionId)
                .addArgument(connectionId)
                .addArgument(accum.state)
                .addArgument(() -> o.getCaptureCase().name())
                .log();
            var connectionStatus = addObservationToAccumulation(accum, tsk, o);
            if (CONNECTION_STATUS.CLOSED == connectionStatus) {
                log.atDebug().setMessage("Connection terminated: removing {}:{} from liveStreams map")
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
                .setMessage("Encountered a TrafficStream object with inconsistent values between " +
                    "the prior request count ({}, lastObservationWasUnterminatedRead ({}) and the index ({}).  " +
                    "Traffic Observations will be ignored until Reads after the next EndOfMessage" +
                    " are encountered.   Full stream object={}")
                .addArgument(stream::getPriorRequestsReceived)
                .addArgument(stream::getLastObservationWasUnterminatedRead)
                .addArgument(key::getTrafficStreamIndex)
                .addArgument(stream)
                .log();
        }

        return createTypedAccumulation(streamWithKey, stream);
    }

    /**
     * protocol dispatch. Decides between {@link Accumulation} (H1) and
     * {@link H2Accumulation} based on the {@code negotiatedAlpn} envelope field, falling back
     * to {@code captureFormatVersion} and finally to substream sniffing.
     *
     * <p>The default for unknown / empty fields is H1 (legacy behavior); a v2 record without
     * an explicit ALPN string means the connection was H1 within a v2-capable proxy.
     */
    private Accumulation createTypedAccumulation(ITrafficStreamWithKey streamWithKey, TrafficStream stream) {
        var alpn = stream.getNegotiatedAlpn();
        var isH2 = "h2".equals(alpn) || (alpn.isEmpty() && hasH2Observation(stream));
        if (isH2) {
            log.atDebug().setMessage("Creating H2Accumulation for connectionId={} (alpn={})")
                .addArgument(streamWithKey.getKey().getConnectionId()).addArgument(alpn).log();
            return new H2Accumulation(streamWithKey.getKey(), stream, streamWithKey.isResumedConnection());
        }
        return new Accumulation(streamWithKey.getKey(), stream, streamWithKey.isResumedConnection());
    }

    /**
     * Substream sniff for protocol detection: scan the first ~16 observations for an ALPN
     * observation or any H2 frame. If neither is found, classify as H1.
     */
    private static boolean hasH2Observation(TrafficStream stream) {
        int limit = Math.min(16, stream.getSubStreamCount());
        for (int i = 0; i < limit; i++) {
            var sub = stream.getSubStream(i);
            if (sub.hasAlpn() || sub.hasHttp2Frame()) {
                return true;
            }
            if (sub.hasRead() || sub.hasWrite()) {
                return false;
            }
        }
        return false;
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

        if (accum instanceof H2Accumulation) {
            return addH2Observation((H2Accumulation) accum, observation, timestamp);
        }

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
            rrPair.responseData.setLastPacketTimestamp(timestamp);
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
        boolean isResumedConnection = accumulation.getIndexOfCurrentRequest() == 0 && accumulation.isResumedConnection;
        log.atDebug().setMessage("handleEndOfRequest for {} requestIdx={} resumed={} requestBytes={}")
            .addArgument(accumulation.trafficChannelKey)
            .addArgument(accumulation::getIndexOfCurrentRequest)
            .addArgument(isResumedConnection)
            .addArgument(() -> httpMessage.packetBytes.stream().mapToLong(b -> b.length).sum())
            .log();
        var requestCtx = rrPair.getRequestContext();
        rrPair.rotateRequestGatheringToResponse();
        var callbackTrackedData = listener.onRequestReceived(requestCtx, httpMessage, isResumedConnection);
        rrPairWithCallback.setFullDataContinuation(callbackTrackedData);
        accumulation.state = Accumulation.State.ACCUMULATING_WRITES;
        return true;
    }

    private void handleEndOfResponse(Accumulation accumulation, RequestResponsePacketPair.ReconstructionStatus status) {
        assert accumulation.state == Accumulation.State.ACCUMULATING_WRITES;
        log.atDebug().setMessage("handleEndOfResponse for {} status={}")
            .addArgument(accumulation.trafficChannelKey)
            .addArgument(status)
            .log();
        var rrPairWithCallback = accumulation.getRrPairWithCallback();
        var rrPair = rrPairWithCallback.pair;
        rrPair.completionStatus = status;
        rrPairWithCallback.getFullDataContinuation().accept(rrPair);
        log.atTrace().setMessage("resetting for end of response").log();
        accumulation.resetForNextRequest();
    }

    /**
     * — frame-table dispatch for HTTP/2 observations. Implements per-frame
     * type handling on an {@link H2Accumulation} — HEADERS / DATA / RST_STREAM / GOAWAY /
     * SETTINGS / others. Connection-scoped frames (streamId=0) update connection state;
     * stream-scoped frames update the per-stream accumulation.
     *
     * <p>This is a minimal implementation that establishes the dispatch surface; full
     * lifecycle handling (per table) is filled out across subsequent
     * commits as fixtures land.
     */
    private CONNECTION_STATUS addH2Observation(
        H2Accumulation accum,
        TrafficObservation observation,
        java.time.Instant timestamp
    ) {
        if (observation.hasAlpn()) {
            // ALPN observation already drove the H2 dispatch decision; no-op here.
            return CONNECTION_STATUS.ALIVE;
        }
        if (observation.hasClose() || observation.hasDisconnect()) {
            return CONNECTION_STATUS.CLOSED;
        }
        if (!observation.hasHttp2Frame()) {
            // Pass through other observation types (read/write at TLS layer should not appear
            // for an H2 connection, but fail-soft).
            return CONNECTION_STATUS.ALIVE;
        }
        var frame = observation.getHttp2Frame();
        int streamId = frame.getStreamId();
        switch (frame.getType()) {
            case H2_HEADERS:
                handleH2Headers(accum, streamId, frame, timestamp);
                break;
            case H2_DATA:
                handleH2Data(accum, streamId, frame, timestamp);
                break;
            case H2_RST_STREAM:
                handleH2RstStream(accum, streamId, frame);
                break;
            case H2_GOAWAY:
                accum.goAwayLastStreamId = frame.getGoAway().getLastStreamId();
                accum.goAwayErrorCode = (long) frame.getGoAway().getErrorCode();
                break;
            case H2_SETTINGS:
                if (!frame.getSettings().getAck()) {
                    var target = streamId == 0 ? accum.clientSettings : accum.serverSettings;
                    target.putAll(frame.getSettings().getSettingsMap().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,
                            e -> Long.valueOf(e.getValue()))));
                }
                break;
            case H2_WINDOW_UPDATE:
            case H2_PING:
            case H2_PRIORITY:
            case H2_CONTINUATION:
                // metric-only; no effect on logical request reconstruction
                break;
            case H2_PUSH_PROMISE:
                log.atDebug().setMessage("Discarding PUSH_PROMISE on streamId={} (not replayable)")
                    .addArgument(streamId).log();
                break;
            default:
                break;
        }
        return CONNECTION_STATUS.ALIVE;
    }

    private void handleH2Headers(H2Accumulation accum, int streamId,
                                  org.opensearch.migrations.trafficcapture.protos.Http2FrameObservation frame,
                                  java.time.Instant timestamp) {
        var stream = accum.getOrCreateStream(streamId);
        var headers = frame.getHeaders();
        // Distinguish request- vs response-direction by presence of :status pseudo-header.
        boolean isResponse = headers.getFieldsList().stream()
            .anyMatch(f -> ":status".equals(f.getName().toStringUtf8()));
        if (isResponse) {
            if (stream.responseFirstFrameTs == null) {
                stream.responseFirstFrameTs = timestamp;
            }
            stream.responseLastFrameTs = timestamp;
            H2Accumulation.applyHeadersToResponse(stream, headers);
            if (headers.getEndStream()) {
                stream.serverEndStream = true;
                stream.phase = H2Accumulation.LifecyclePhase.CLOSED;
                maybeEmitResponse(stream);
            } else {
                stream.phase = H2Accumulation.LifecyclePhase.RECEIVING_RESPONSE_BODY;
            }
        } else {
            if (stream.requestFirstFrameTs == null) {
                stream.requestFirstFrameTs = timestamp;
            }
            stream.requestLastFrameTs = timestamp;
            H2Accumulation.applyHeadersToRequest(stream, headers);
            if (headers.getEndStream()) {
                stream.clientEndStream = true;
                stream.phase = H2Accumulation.LifecyclePhase.AWAITING_RESPONSE;
                maybeEmitRequest(accum, stream, timestamp);
            } else {
                stream.phase = H2Accumulation.LifecyclePhase.RECEIVING_BODY;
            }
        }
    }

    private void handleH2Data(H2Accumulation accum, int streamId,
                               org.opensearch.migrations.trafficcapture.protos.Http2FrameObservation frame,
                               java.time.Instant timestamp) {
        var stream = accum.getOrCreateStream(streamId);
        var data = frame.getData();
        var bytes = data.getData().toByteArray();
        if (stream.phase == H2Accumulation.LifecyclePhase.RECEIVING_RESPONSE_BODY) {
            stream.responseLastFrameTs = timestamp;
            stream.responseBody.add(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
            if (data.getEndStream()) {
                stream.serverEndStream = true;
                stream.phase = H2Accumulation.LifecyclePhase.CLOSED;
                maybeEmitResponse(stream);
            }
        } else {
            stream.requestLastFrameTs = timestamp;
            stream.requestBody.add(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
            if (data.getEndStream()) {
                stream.clientEndStream = true;
                stream.phase = H2Accumulation.LifecyclePhase.AWAITING_RESPONSE;
                maybeEmitRequest(accum, stream, timestamp);
            }
        }
    }

    /**
     * — fire {@code onRequestReceived} when an H2 stream's request side completes.
     * Each H2 stream gets its own {@link RequestResponsePacketPair} (multiplexing means
     * we can't reuse the connection-level pair on Accumulation — that would collide across
     * concurrent streams). The context for that pair is built from the connection-level
     * traffic-stream context.
     */
    private void maybeEmitRequest(H2Accumulation accum, H2Accumulation.StreamState stream, java.time.Instant ts) {
        if (stream.requestEmitted) return;
        stream.requestEmitted = true;
        try {
            var firstTs = stream.requestFirstFrameTs == null ? ts : stream.requestFirstFrameTs;
            // Per-stream RRP: distinct from accum.getOrCreateTransactionPair to avoid collisions
            // when many streams are in flight on the same connection.
            int requestIndex = accum.getIndexOfCurrentRequest() + stream.streamId;
            var rrPair = new RequestResponsePacketPair(
                    accum.trafficChannelKey,
                    firstTs,
                    accum.startingSourceRequestIndex,
                    requestIndex);
            // tag the pair with H2 protocol identity for tuple JSON visibility.
            rrPair.setSourceProtocolAndStream("HTTP/2.0", stream.streamId);
            // Wire-level timestamps so the orchestrator can reconstruct happens-before
            // and decide chained vs concurrent dispatch on the target side.
            rrPair.setSourceWireTimestamps(
                stream.requestFirstFrameTs,
                stream.requestLastFrameTs,
                stream.responseFirstFrameTs,
                stream.responseLastFrameTs);
            var requestMessage = buildH2RequestMessage(stream, firstTs);
            rrPair.requestData = requestMessage;
            // Capture the request context BEFORE rotating to response gathering, mirroring the
            // H1 path's handleEndOfRequest sequence. The wrapped consumer access
            // rrPair.getResponseContext() once the response side resolves — that requires the
            // pair to already be in response context.
            var requestCtx = rrPair.getRequestContext();
            rrPair.rotateRequestGatheringToResponse();
            stream.inFlightPair = rrPair;
            boolean isResumed = accum.getIndexOfCurrentRequest() == 0 && accum.isResumedConnection;
            stream.responseContinuation = listener.onRequestReceived(
                    requestCtx, requestMessage, isResumed);
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage(
                    "H2 onRequestReceived emission failed for streamId={}; stream marked CLOSED")
                .addArgument(stream.streamId).log();
            stream.phase = H2Accumulation.LifecyclePhase.CLOSED;
        }
    }

    /** Build an HttpMessageAndTimestamp for the response side of an H2 stream. Trace-only on
     *  serialization failure: the response is best-effort visibility, not byte-exact. */
    private HttpMessageAndTimestamp buildH2ResponseMessage(H2Accumulation.StreamState stream) {
        var responseTs = stream.responseFirstFrameTs == null
                ? stream.requestFirstFrameTs : stream.responseFirstFrameTs;
        var responseMessage = new HttpMessageAndTimestamp(
                responseTs == null ? java.time.Instant.now() : responseTs);
        try {
            var bytes = serializeH2ResponseToH1Bytes(stream);
            if (bytes.length > 0) responseMessage.add(bytes);
        } catch (Exception e) {
            log.atTrace().setCause(e).setMessage(
                    "H2 response serialization failed for streamId={}")
                .addArgument(stream.streamId).log();
        }
        return responseMessage;
    }

    /** Build an HttpMessageAndTimestamp for the request side of an H2 stream, swallowing
     *  serialization failures and emitting an empty body in the rare error case. */
    private HttpMessageAndTimestamp buildH2RequestMessage(
            H2Accumulation.StreamState stream, java.time.Instant firstTs) {
        var requestMessage = new HttpMessageAndTimestamp(firstTs);
        try {
            var bytes = serializeH2RequestToH1Bytes(stream);
            if (bytes.length > 0) requestMessage.add(bytes);
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage(
                    "Failed to serialize H2 stream {} as H1 bytes; emitting empty request body")
                .addArgument(stream.streamId).log();
        }
        return requestMessage;
    }

    /**
     * Fire the response continuation when the response side of an H2 stream completes. Sets
     * {@code completionStatus} on the in-flight RRP and accepts it via the stored consumer.
     */
    private void maybeEmitResponse(H2Accumulation.StreamState stream) {
        if (stream.responseContinuation == null || stream.inFlightPair == null) {
            // Either the request side never emitted (e.g. RST_STREAM mid-body) or already drained.
            return;
        }
        try {
            stream.inFlightPair.completionStatus = stream.isReset()
                    ? RequestResponsePacketPair.ReconstructionStatus.RESET_BY_PEER
                    : RequestResponsePacketPair.ReconstructionStatus.COMPLETE;
            if (stream.inFlightPair.responseData == null) {
                stream.inFlightPair.responseData = buildH2ResponseMessage(stream);
            }
            // Refresh wire-time stamps now that the response side has fully landed.
            stream.inFlightPair.setSourceWireTimestamps(
                stream.requestFirstFrameTs,
                stream.requestLastFrameTs,
                stream.responseFirstFrameTs,
                stream.responseLastFrameTs);
            stream.responseContinuation.accept(stream.inFlightPair);
        } finally {
            stream.responseContinuation = null;
            stream.inFlightPair = null;
        }
    }

    private static byte[] serializeH2RequestToH1Bytes(H2Accumulation.StreamState stream) {
        var objects = org.opensearch.migrations.replay.datahandlers.http.H2ToH1ObjectAdapter.toH1RequestObjects(stream);
        return serializeH1RequestObjects(objects);
    }

    private static byte[] serializeH2ResponseToH1Bytes(H2Accumulation.StreamState stream) {
        var objects = org.opensearch.migrations.replay.datahandlers.http.H2ToH1ObjectAdapter.toH1ResponseObjects(stream);
        return serializeH1ResponseObjects(objects);
    }

    private static byte[] serializeH1RequestObjects(java.util.List<io.netty.handler.codec.http.HttpObject> objects) {
        var ec = new io.netty.channel.embedded.EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestEncoder());
        return drainEncoded(ec, objects);
    }

    private static byte[] serializeH1ResponseObjects(java.util.List<io.netty.handler.codec.http.HttpObject> objects) {
        var ec = new io.netty.channel.embedded.EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseEncoder());
        return drainEncoded(ec, objects);
    }

    private static byte[] drainEncoded(io.netty.channel.embedded.EmbeddedChannel ec,
                                        java.util.List<io.netty.handler.codec.http.HttpObject> objects) {
        try {
            for (var o : objects) ec.writeOutbound(o);
            ec.flushOutbound();
            var pieces = new java.util.ArrayList<byte[]>();
            int total = 0;
            Object next;
            while ((next = ec.readOutbound()) != null) {
                var buf = (io.netty.buffer.ByteBuf) next;
                var arr = new byte[buf.readableBytes()];
                buf.readBytes(arr);
                buf.release();
                pieces.add(arr);
                total += arr.length;
            }
            var combined = new byte[total];
            int off = 0;
            for (var a : pieces) {
                System.arraycopy(a, 0, combined, off, a.length);
                off += a.length;
            }
            return combined;
        } finally {
            ec.finishAndReleaseAll();
        }
    }

    private void handleH2RstStream(H2Accumulation accum, int streamId,
                                    org.opensearch.migrations.trafficcapture.protos.Http2FrameObservation frame) {
        var stream = accum.getOrCreateStream(streamId);
        stream.resetErrorCode = (long) frame.getRstStream().getErrorCode();
        stream.phase = H2Accumulation.LifecyclePhase.CLOSED;
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
