package org.opensearch.migrations.replay;

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

    public Accumulation(ITrafficStreamKey key, TrafficStream ts) {
        this(
            key,
            ts.getPriorRequestsReceived() + (ts.hasLastObservationWasUnterminatedRead() ? 1 : 0),
            ts.getLastObservationWasUnterminatedRead(),
            key.getSourceGeneration()
        );
    }

    public Accumulation(@NonNull ITrafficStreamKey trafficChannelKey, int startingSourceRequestIndex) {
        this(trafficChannelKey, startingSourceRequestIndex, false, 0);
    }

    public Accumulation(
        @NonNull ITrafficStreamKey trafficChannelKey,
        int startingSourceRequestIndex,
        boolean dropObservationsLeftoverFromPrevious
    ) {
        this(trafficChannelKey, startingSourceRequestIndex, dropObservationsLeftoverFromPrevious, 0);
    }

    public Accumulation(
        @NonNull ITrafficStreamKey trafficChannelKey,
        int startingSourceRequestIndex,
        boolean dropObservationsLeftoverFromPrevious,
        int sourceGeneration
    ) {
        this.trafficChannelKey = trafficChannelKey;
        numberOfResets = new AtomicInteger();
        this.newestPacketTimestampInMillis = new AtomicLong(0);
        this.startingSourceRequestIndex = startingSourceRequestIndex;
        this.state = dropObservationsLeftoverFromPrevious
            ? State.IGNORING_LAST_REQUEST
            : State.WAITING_FOR_NEXT_READ_CHUNK;
        this.sourceGeneration = sourceGeneration;
    }

    public boolean hasBeenExpired() {
        return hasBeenExpired;
    }

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

    /**
     * It is illegal to call this when rrPair may be equal to null.  If the caller isn't sure,
     * hasRrPair() should be called to first check.
     * @return
     */
    public @NonNull RequestResponsePacketPair getRrPair() {
        assert rrPairWithCallback != null;
        return rrPairWithCallback.pair;
    }

    /**
     * It is illegal to call this when rrPair may be equal to null.  If the caller isn't sure,
     * hasRrPair() should be called to first check.
     * @return
     */
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

    /**
     * Accumulations are reset for each new HttpRequest that is discovered.  This value indicates how
     * many times the object has been reset, indicating in a logical sequence of requests against this
     * Accumulation, what index would the current data be a part of?  Calling resetForNextRequest()
     * will increase this value by 1.
     * @return
     */
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
