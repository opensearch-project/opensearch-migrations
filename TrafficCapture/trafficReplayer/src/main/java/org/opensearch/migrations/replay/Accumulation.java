package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Accumulation {

    enum State {
        // Ignore all initial READs, the first EOM & the following WRITEs (if they or EOMs exist)
        IGNORING_LAST_REQUEST,
        // Finished scanning past initial READs. The next request should be processed,
        // so be on the lookout for the next READ
        WAITING_FOR_NEXT_READ_CHUNK,
        ACCUMULATING_READS,
        ACCUMULATING_WRITES
    }

    @AllArgsConstructor
    static class RequestResponsePacketPairWithCallback {
        @NonNull
        RequestResponsePacketPair pair;
        private Consumer<RequestResponsePacketPair> fullDataContinuation = null;

        void setFullDataContinuation(Consumer<RequestResponsePacketPair> v) {
            assert fullDataContinuation == null;
            fullDataContinuation = v;
        }

        Consumer<RequestResponsePacketPair> getFullDataContinuation() {
            return fullDataContinuation;
        }
    }

    public final ITrafficStreamKey trafficChannelKey;
    private RequestResponsePacketPairWithCallback rrPairWithCallback;
    AtomicLong newestPacketTimestampInMillis;
    State state;
    AtomicInteger numberOfResets;
    int startingSourceRequestIndex;
    private boolean hasBeenExpired;
    final int sourceGeneration;
*/
// REBUILD-LIMBO-END(G11)
    /** True when this connection was mid-flight during a partition reassignment (resumed). */
// REBUILD-LIMBO-START(G11)
/*
    final boolean isResumedConnection;

    public Accumulation(ITrafficStreamKey key, TrafficStream ts) {
        this(
            key,
            ts.getPriorRequestsReceived() + (ts.hasLastObservationWasUnterminatedRead() ? 1 : 0),
            ts.getLastObservationWasUnterminatedRead(),
            key.getSourceGeneration(),
            false
        );
    }

    public Accumulation(ITrafficStreamKey key, TrafficStream ts, boolean isResumedConnection) {
        this(
            key,
            ts.getPriorRequestsReceived() + (ts.hasLastObservationWasUnterminatedRead() ? 1 : 0),
            ts.getLastObservationWasUnterminatedRead(),
            key.getSourceGeneration(),
            isResumedConnection
        );
    }

    public Accumulation(@NonNull ITrafficStreamKey trafficChannelKey, int startingSourceRequestIndex) {
        this(trafficChannelKey, startingSourceRequestIndex, false, 0, false);
    }

    public Accumulation(
        @NonNull ITrafficStreamKey trafficChannelKey,
        int startingSourceRequestIndex,
        boolean dropObservationsLeftoverFromPrevious
    ) {
        this(trafficChannelKey, startingSourceRequestIndex, dropObservationsLeftoverFromPrevious, 0, false);
    }

    public Accumulation(
        @NonNull ITrafficStreamKey trafficChannelKey,
        int startingSourceRequestIndex,
        boolean dropObservationsLeftoverFromPrevious,
        int sourceGeneration
    ) {
        this(trafficChannelKey, startingSourceRequestIndex, dropObservationsLeftoverFromPrevious, sourceGeneration, false);
    }

    public Accumulation(
        @NonNull ITrafficStreamKey trafficChannelKey,
        int startingSourceRequestIndex,
        boolean dropObservationsLeftoverFromPrevious,
        int sourceGeneration,
        boolean isResumedConnection
    ) {
        this.trafficChannelKey = trafficChannelKey;
        numberOfResets = new AtomicInteger();
        this.newestPacketTimestampInMillis = new AtomicLong(0);
        this.startingSourceRequestIndex = startingSourceRequestIndex;
        this.state = dropObservationsLeftoverFromPrevious
            ? State.IGNORING_LAST_REQUEST
            : State.WAITING_FOR_NEXT_READ_CHUNK;
        this.sourceGeneration = sourceGeneration;
        this.isResumedConnection = isResumedConnection;
    }

    public boolean hasBeenExpired() {
        return hasBeenExpired;
    }

*/
// REBUILD-LIMBO-END(G11)
// REBUILD-TRACE-START(G6,source): retain through the rebuild; remove in final pre-merge cleanup.
// Accumulation.expire -> SourceConnectionState.expire
// REBUILD-TRACE-END(G6,source)
// REBUILD-LIMBO-START(G11)
/*
    public void expire() {
        hasBeenExpired = true;
    }

    public RequestResponsePacketPair getOrCreateTransactionPair(
        ITrafficStreamKey forTrafficStreamKey,
        Instant originTimestamp
    ) {
        if (rrPairWithCallback != null) {
            return rrPairWithCallback.pair;
        }
        var rrPair = new RequestResponsePacketPair(
            forTrafficStreamKey,
            originTimestamp,
            startingSourceRequestIndex,
            getIndexOfCurrentRequest()
        );
        this.rrPairWithCallback = new RequestResponsePacketPairWithCallback(rrPair, null);
        return rrPair;
    }

    public boolean hasSignaledRequests() {
        return numberOfResets.get() > 0 || state == Accumulation.State.ACCUMULATING_WRITES;
    }

    public boolean hasRrPair() {
        return rrPairWithCallback != null;
    }

*/
// REBUILD-LIMBO-END(G11)
    /**
     * It is illegal to call this when rrPair may be equal to null.  If the caller isn't sure,
     * hasRrPair() should be called to first check.
     * @return
     */
// REBUILD-LIMBO-START(G11)
/*
    public @NonNull RequestResponsePacketPair getRrPair() {
        assert rrPairWithCallback != null;
        return rrPairWithCallback.pair;
    }

*/
// REBUILD-LIMBO-END(G11)
    /**
     * It is illegal to call this when rrPair may be equal to null.  If the caller isn't sure,
     * hasRrPair() should be called to first check.
     * @return
     */
// REBUILD-LIMBO-START(G11)
/*
    public @NonNull RequestResponsePacketPairWithCallback getRrPairWithCallback() {
        assert rrPairWithCallback != null;
        return rrPairWithCallback;
    }

    public Instant getLastTimestamp() {
        return Instant.ofEpochMilli(newestPacketTimestampInMillis.get());
    }

    public AtomicLong getNewestPacketTimestampInMillisReference() {
        return newestPacketTimestampInMillis;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Accumulation{");
        sb.append("rrPair=").append(rrPairWithCallback);
        sb.append(", state=").append(state);
        sb.append('}');
        return sb.toString();
    }

*/
// REBUILD-LIMBO-END(G11)
    /**
     * Accumulations are reset for each new HttpRequest that is discovered.  This value indicates how
     * many times the object has been reset, indicating in a logical sequence of requests against this
     * Accumulation, what index would the current data be a part of?  Calling resetForNextRequest()
     * will increase this value by 1.
     * @return
     */
// REBUILD-LIMBO-START(G11)
/*
    public int getIndexOfCurrentRequest() {
        return numberOfResets.get();
    }

    public void resetForNextRequest() {
        numberOfResets.incrementAndGet();
        this.state = State.ACCUMULATING_READS;
        this.rrPairWithCallback = null;
    }

    public void resetToIgnoreAndForgetCurrentRequest() {
        if (state == State.IGNORING_LAST_REQUEST) {
            --startingSourceRequestIndex;
        }
        this.state = State.WAITING_FOR_NEXT_READ_CHUNK;
        this.rrPairWithCallback = null;
    }
}

*/
// REBUILD-LIMBO-END(G11)
