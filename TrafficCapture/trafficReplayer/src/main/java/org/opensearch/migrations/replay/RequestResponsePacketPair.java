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
        TRAFFIC_SOURCE_READER_INTERRUPTED,
        /** H2 stream terminated by RST_STREAM from peer. */
        RESET_BY_PEER,
        /** H2 stream orphaned by GOAWAY (streamId > lastStreamId). */
        GOAWAY_DROPPED,
        /** H2 capture failed validation (CRLF in header value, missing:method, etc.). */
        MALFORMED,
        /** H2 stream uses an unsupported feature (CONNECT method, server PUSH_PROMISE). */
        UNSUPPORTED,
        /** H2 frame exceeded maxTrafficBufferSize and was emitted with payload omitted. */
        TRUNCATED
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

    /** — wire-protocol of the source side: "HTTP/1.1" or "HTTP/2.0". Null when unknown. */
    @Getter
    private String sourceProtocol;
    /** — H2 stream id on the source side; null for H1 / unknown. */
    @Getter
    private Integer sourceStreamId;
    /** — H2 stream id on the target side; null for H1 / unknown. */
    @Getter
    private Integer targetStreamId;

    /** Sets H2 source identity on this pair. Setter (rather than constructor arg) keeps the H1 path unchanged. */
    public void setSourceProtocolAndStream(String protocol, Integer streamId) {
        this.sourceProtocol = protocol;
        this.sourceStreamId = streamId;
    }

    public void setTargetStreamId(Integer streamId) {
        this.targetStreamId = streamId;
    }

    /** Wire-level timestamp when the request's first frame was observed at the source. */
    @Getter
    private Instant sourceRequestFirstFrameTs;
    /** Wire-level timestamp when the request's last frame was observed at the source. */
    @Getter
    private Instant sourceRequestLastFrameTs;
    /** Wire-level timestamp when the response's first frame was observed at the source. */
    @Getter
    private Instant sourceResponseFirstFrameTs;
    /** Wire-level timestamp when the response's last frame was observed at the source. */
    @Getter
    private Instant sourceResponseLastFrameTs;

    /**
     * Stamp wire-level timestamps captured on the source connection so the replayer's
     * scheduler can reconstruct the happens-before relations between requests on the
     * same source connection. Used to decide chained vs. concurrent dispatch when
     * replaying H2-multiplexed traffic at any speedup factor.
     */
    public void setSourceWireTimestamps(Instant requestFirst, Instant requestLast,
                                         Instant responseFirst, Instant responseLast) {
        this.sourceRequestFirstFrameTs = requestFirst;
        this.sourceRequestLastFrameTs = requestLast;
        this.sourceResponseFirstFrameTs = responseFirst;
        this.sourceResponseLastFrameTs = responseLast;
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
