package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;

import com.google.common.base.Objects;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RequestResponsePacketPair implements IRequestResponsePacketPair {

    public enum ReconstructionStatus {
        COMPLETE,
        EXPIRED_PREMATURELY,
        CLOSED_PREMATURELY,
        /** Connection closed due to Kafka partition reassignment — not a source-side close. */
        REASSIGNED
    }

    @Getter
    HttpMessageAndTimestamp requestData;
    @Getter
    HttpMessageAndTimestamp responseData;
    @NonNull
    final ISourceTrafficChannelKey firstTrafficStreamKeyForRequest;
    List<ITrafficStreamKey> trafficStreamKeysBeingHeld;
    ReconstructionStatus completionStatus;
    // switch between RequestAccumulation/ResponseAccumulation objects when we're parsing,
    // or just leave this null, in which case, the context from the trafficStreamKey should be used
    private IScopedInstrumentationAttributes requestOrResponseAccumulationContext;

    public RequestResponsePacketPair(
        @NonNull ITrafficStreamKey startingAtTrafficStreamKey,
        Instant sourceTimestamp,
        int startingSourceRequestIndex,
        int indexOfCurrentRequest
    ) {
        this.firstTrafficStreamKeyForRequest = startingAtTrafficStreamKey;
        var requestKey = new UniqueReplayerRequestKey(
            startingAtTrafficStreamKey,
            startingSourceRequestIndex,
            indexOfCurrentRequest
        );
        var httpTransactionContext = startingAtTrafficStreamKey.getTrafficStreamsContext()
            .createHttpTransactionContext(requestKey, sourceTimestamp);
        requestOrResponseAccumulationContext = httpTransactionContext.createRequestAccumulationContext();
    }

    @NonNull
    ISourceTrafficChannelKey getBeginningTrafficStreamKey() {
        return firstTrafficStreamKeyForRequest;
    }

    public IReplayContexts.IReplayerHttpTransactionContext getHttpTransactionContext() {
        var looseCtx = requestOrResponseAccumulationContext;
        // the req/response ctx types in the assert below will always implement this with the
        // IReplayerHttpTransactionContext parameter, but this seems clearer
        // than trying to engineer a compile time static check
        assert looseCtx instanceof IWithTypedEnclosingScope;
        assert looseCtx instanceof IReplayContexts.IRequestAccumulationContext
            || looseCtx instanceof IReplayContexts.IResponseAccumulationContext;
        return ((IWithTypedEnclosingScope<IReplayContexts.IReplayerHttpTransactionContext>) looseCtx)
            .getLogicalEnclosingScope();

    }

    public @NonNull IReplayContexts.IRequestAccumulationContext getRequestContext() {
        return (IReplayContexts.IRequestAccumulationContext) requestOrResponseAccumulationContext;
    }

    public @NonNull IReplayContexts.IResponseAccumulationContext getResponseContext() {
        return (IReplayContexts.IResponseAccumulationContext) requestOrResponseAccumulationContext;
    }

    public void rotateRequestGatheringToResponse() {
        var looseCtx = requestOrResponseAccumulationContext;
        assert looseCtx instanceof IReplayContexts.IRequestAccumulationContext;
        requestOrResponseAccumulationContext = getRequestContext().getLogicalEnclosingScope()
            .createResponseAccumulationContext();
    }

    public void addRequestData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding request data: " + new String(data, StandardCharsets.UTF_8));
        }
        if (requestData == null) {
            requestData = new HttpMessageAndTimestamp.Request(packetTimeStamp);
        }
        requestData.add(data);
        requestData.setLastPacketTimestamp(packetTimeStamp);
    }

    public void addResponseData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding response data: " + new String(data, StandardCharsets.UTF_8));
        }
        if (responseData == null) {
            responseData = new HttpMessageAndTimestamp.Response(packetTimeStamp);
        }
        responseData.add(data);
        responseData.setLastPacketTimestamp(packetTimeStamp);
    }

    public void holdTrafficStream(ITrafficStreamKey trafficStreamKey) {
        if (trafficStreamKeysBeingHeld == null) {
            trafficStreamKeysBeingHeld = new ArrayList<>();
        }
        if (trafficStreamKeysBeingHeld.isEmpty()
            || trafficStreamKey != trafficStreamKeysBeingHeld.get(trafficStreamKeysBeingHeld.size() - 1)) {
            trafficStreamKeysBeingHeld.add(trafficStreamKey);
        }
    }

    private static final List<ITrafficStreamKey> emptyUnmodifiableList = List.of();

    public List<ITrafficStreamKey> getTrafficStreamsHeld() {
        return (trafficStreamKeysBeingHeld == null)
            ? emptyUnmodifiableList
            : Collections.unmodifiableList(trafficStreamKeysBeingHeld);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestResponsePacketPair that = (RequestResponsePacketPair) o;
        return Objects.equal(requestData, that.requestData)
            && Objects.equal(responseData, that.responseData)
            && Objects.equal(trafficStreamKeysBeingHeld, that.trafficStreamKeysBeingHeld);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(requestData, responseData, trafficStreamKeysBeingHeld);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("RequestResponsePacketPair{");
        sb.append("\n requestData=").append(requestData);
        sb.append("\n responseData=").append(responseData);
        sb.append('}');
        return sb.toString();
    }

}
