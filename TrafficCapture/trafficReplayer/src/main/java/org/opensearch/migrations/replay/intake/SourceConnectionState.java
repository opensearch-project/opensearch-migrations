/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Source-side HTTP assembly for one process-local connection lifetime — {@code kafkaLLD §9}.
 *
 * <p>Owns only what {@code §9} lists: the sequence baseline, the request parser and its incomplete state,
 * response parser state per {@code ReplayRequestId}, the current captured request ordinal, the continuity
 * state the {@code TrafficStream} supplied, and the lifetime. It owns no target channel, target attempt,
 * tuple, Kafka consumer, or commit position, and it does no record accounting — that is
 * {@link PartitionIntakeState}'s, and this class reports which association each observation belongs to so the
 * caller can record it.
 *
 * <p>Confined to the replay-intake thread by its owner.
 */
@Slf4j
public final class SourceConnectionState {

    /** {@code §9}: {@code open | explicitly closed | expired}. */
    public enum Lifetime {
        OPEN,
        EXPLICITLY_CLOSED,
        EXPIRED
    }

    /**
     * What the connection is doing with the observations it is being handed.
     *
     * <p>The vocabulary is deliberately split: the <em>observation</em> kinds keep the capture protocol's
     * read/write names, because that is what the wire says and what {@code TrafficObservation} accessors are
     * called, while the states name the HTTP message being assembled. A read observation carries request
     * bytes and a write observation carries response bytes, both from the proxy's point of view.
     */
    private enum Phase {
        /**
         * Discarding the tail of an HTTP message that began before this lifetime.
         *
         * <p>{@code §9}: the continuity fields decide "whether input begins in the tail of an HTTP message",
         * and such a tail "is discarded until the next captured request boundary rather than parsing it as a
         * new request".
         */
        DISCARDING_INHERITED_TAIL,
        /** Between requests: read observations begin the next one, write observations are not expected. */
        BETWEEN_REQUESTS,
        /** Assembling a request from read observations, until an end-of-message indication. */
        ASSEMBLING_REQUEST,
        /** Assembling that request's source response from write observations. */
        ASSEMBLING_RESPONSE
    }

    /**
     * What one applied observation asks the caller to do about Kafka-record accounting.
     *
     * <p>Returned rather than performed, because {@code §8} puts association bookkeeping in the partition's
     * state and {@code §9} keeps this class to HTTP assembly. The two lists are applied in order: additions
     * first, so {@code §8.2}'s no-gap rule holds even when one observation both completes a request and
     * begins the next.
     */
    public record ObservationOutcome(
        List<RecordAssociationId> associationsToAdd,
        List<RecordAssociationId> associationsFinished,
        List<Relabel> relabels,
        boolean lifetimeEnded
    ) {
        public ObservationOutcome {
            associationsToAdd = List.copyOf(associationsToAdd);
            associationsFinished = List.copyOf(associationsFinished);
            relabels = List.copyOf(relabels);
        }

        static ObservationOutcome none() {
            return new ObservationOutcome(List.of(), List.of(), List.of(), false);
        }
    }

    /** One {@code §8.2} relabel: an assembly identity becoming the request identity allocated for it. */
    public record Relabel(RecordAssociationId from, RecordAssociationId to) {}

    private final ConnectionProcessingId connectionProcessingId;
    private final SourceAssemblySink sink;

    /**
     * {@code §9}'s {@code nextExpectedObservationSequence}. Absent until the first observation of the
     * lifetime establishes the baseline, which is why this is boxed rather than defaulted: zero and one are
     * both plausible first values on the wire, so no sentinel can be distinguished from a real baseline.
     */
    private Long nextExpectedObservationSequence;

    private Phase phase;
    private boolean ignoringInformationalWriteSegments;
    private boolean requestEverReconstituted;
    private long currentCapturedRequestOrdinal;
    private HttpMessageAndTimestamp.Request incomingRequest;
    private IReplayContexts.IRequestContext requestContext;
    private IReplayContexts.IRequestAccumulationContext requestAccumulationContext;
    private IReplayContexts.IResponseAccumulationContext responseAccumulationContext;
    private ReplayRequestId responseBeingAssembledFor;
    private final Map<ReplayRequestId, HttpMessageAndTimestamp.Response> responseStateByRequest =
        new LinkedHashMap<>();
    private Lifetime lifetime = Lifetime.OPEN;
    private Instant latestObservationTime = Instant.EPOCH;

    /**
     * @param firstStream the {@code TrafficStream} that opened this lifetime, read only for {@code §9}'s
     *                    continuity fields: {@code priorRequestsReceived} sets the captured request ordinal
     *                    and {@code lastObservationWasUnterminatedRead} decides whether the first bytes are
     *                    the tail of a message that began outside this lifetime
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.createInitialAccumulation ->
    //     SourceConnectionState.<init>
    // REBUILD-TRACE-END(G5,target)
    public SourceConnectionState(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull TrafficStream firstStream,
        @NonNull SourceAssemblySink sink
    ) {
        this.connectionProcessingId = connectionProcessingId;
        this.sink = sink;
        var inheritedTail = firstStream.getLastObservationWasUnterminatedRead();
        // The unterminated read belongs to a request this lifetime will never see the start of, so it counts
        // toward the captured ordinal even though it is discarded: the next request the source sent is the
        // one after it, and an ordinal that ignored it would collide with the predecessor's.
        this.currentCapturedRequestOrdinal =
            firstStream.getPriorRequestsReceived() + (inheritedTail ? 1 : 0);
        this.phase = inheritedTail ? Phase.DISCARDING_INHERITED_TAIL : Phase.BETWEEN_REQUESTS;
    }

    public ConnectionProcessingId connectionProcessingId() {
        return connectionProcessingId;
    }

    public Lifetime lifetime() {
        return lifetime;
    }

    public long currentCapturedRequestOrdinal() {
        return currentCapturedRequestOrdinal;
    }

    /** True when a request is part-assembled, which is what {@code §17.1}'s expiry case releases. */
    public boolean hasIncompleteRequest() {
        return incomingRequest != null;
    }

    /**
     * Applies one observation, in the order {@code §9} defines.
     *
     * @param logAppendTimeMillis the containing record's broker timestamp, frozen as {@code §9.1}'s
     *                            request-completing {@code LogAppendTime B} if this observation completes a
     *                            request
     */
    public ObservationOutcome apply(
        @NonNull TrafficObservation observation,
        @NonNull KafkaRecordId containingRecord,
        long logAppendTimeMillis
    ) {
        return apply(observation, containingRecord, logAppendTimeMillis, null);
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.addObservationToAccumulation(
    //     Accumulation,ITrafficStreamKey,TrafficObservation,KafkaRecordId) ->
    //     SourceConnectionState.apply(TrafficObservation,KafkaRecordId,long,ITrafficStreamsLifecycleContext)
    // REBUILD-TRACE-END(G5,target)
    public ObservationOutcome apply(
        @NonNull TrafficObservation observation,
        @NonNull KafkaRecordId containingRecord,
        long logAppendTimeMillis,
        IReplayContexts.ITrafficStreamsLifecycleContext trafficContext
    ) {
        requireSequenceContiguous(observation);
        if (lifetime == Lifetime.EXPLICITLY_CLOSED) {
            throw new CaptureProtocolViolation(
                "observation for " + connectionProcessingId + " arrived after CloseObservation: "
                    + observation.getCaptureCase().name()
            );
        }
        if (lifetime == Lifetime.EXPIRED) {
            log.atWarn().setMessage("Observation for {} arrived after the lifetime ended as {}: {}")
                .addArgument(connectionProcessingId)
                .addArgument(lifetime)
                .addArgument(() -> observation.getCaptureCase().name())
                .log();
            return ObservationOutcome.none();
        }
        latestObservationTime = timestampOf(observation);

        if (observation.hasClose()) {
            return applyCapturedClose(containingRecord);
        }
        if (observation.hasConnectionException()) {
            // §9.3: ConnectionExceptionObservation does not perform the close steps, so the lifetime stays
            // open and an incomplete request remains under assembly. It is still a response boundary the
            // replayer observed, which §9.2 makes a completion with completion unproven.
            return applyConnectionException();
        }
        if (observation.hasRequestDropped()) {
            return applyRequestDropped();
        }

        return switch (phase) {
            case DISCARDING_INHERITED_TAIL -> applyWhileDiscardingInheritedTail(observation);
            case BETWEEN_REQUESTS ->
                applyBetweenRequests(observation, containingRecord, logAppendTimeMillis, trafficContext);
            case ASSEMBLING_REQUEST ->
                applyToRequest(observation, containingRecord, logAppendTimeMillis, trafficContext);
            case ASSEMBLING_RESPONSE ->
                applyToResponse(observation, containingRecord, logAppendTimeMillis, trafficContext);
        };
    }

    /**
     * Ends the lifetime on broker-time expiration ({@code §9}, {@code §10.3}).
     *
     * <p>A part-assembled request is released without a tuple, per {@code §8.2}: no {@code ReplayRequestId}
     * was ever allocated for it, so nothing downstream was asked to produce one. A response already being
     * assembled for a reconstituted request does get {@code SourceResponseIncomplete}, because that request
     * exists and something is waiting on its source response.
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.close -> SourceConnectionState.expire
    // REBUILD-TRACE-END(G5,target)
    public ObservationOutcome expire() {
        if (lifetime != Lifetime.OPEN) {
            return ObservationOutcome.none();
        }
        var outcome = stopAssembling(SourceAssemblySink.IncompleteReason.EXPIRED);
        lifetime = Lifetime.EXPIRED;
        return new ObservationOutcome(
            outcome.associationsToAdd(),
            outcome.associationsFinished(),
            outcome.relabels(),
            true
        );
    }

    // ------------------------------------------------------------------ phases

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.handleObservationForSkipState ->
    //     SourceConnectionState.applyWhileDiscardingInheritedTail
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome applyWhileDiscardingInheritedTail(TrafficObservation observation) {
        // Everything up to and including the inherited message's end is discarded. Its end is whichever
        // comes first: the EOM that terminates its request, or the writes that answer it.
        if (observation.hasWrite() || observation.hasWriteSegment()
            || observation.hasEndOfMessageIndicator()) {
            phase = Phase.BETWEEN_REQUESTS;
        }
        return ObservationOutcome.none();
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.handleObservationForReadState ->
    //     SourceConnectionState.applyBetweenRequests
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome applyBetweenRequests(
        TrafficObservation observation,
        KafkaRecordId containingRecord,
        long logAppendTimeMillis,
        IReplayContexts.ITrafficStreamsLifecycleContext trafficContext
    ) {
        if (!(observation.hasRead() || observation.hasReadSegment())) {
            // A write with no request in front of it belongs to the discarded tail or to a request this
            // lifetime never saw. Ignoring it is what keeps it from being parsed as a response to the
            // request that is about to start.
            return ObservationOutcome.none();
        }
        phase = Phase.ASSEMBLING_REQUEST;
        return applyToRequest(observation, containingRecord, logAppendTimeMillis, trafficContext);
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.handleObservationForReadState ->
    //     SourceConnectionState.applyToRequest
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome applyToRequest(
        TrafficObservation observation,
        KafkaRecordId containingRecord,
        long logAppendTimeMillis,
        IReplayContexts.ITrafficStreamsLifecycleContext trafficContext
    ) {
        var timestamp = timestampOf(observation);
        var assembly = currentAssemblyId();
        if (observation.hasRead()) {
            appendTo(
                requestUnderAssembly(timestamp, trafficContext),
                observation.getRead().getData().toByteArray(),
                timestamp
            );
            return added(assembly);
        }
        if (observation.hasReadSegment()) {
            requestUnderAssembly(timestamp, trafficContext)
                .addSegment(observation.getReadSegment().getData().toByteArray());
            return added(assembly);
        }
        if (observation.hasSegmentEnd()) {
            if (ignoringInformationalWriteSegments) {
                ignoringInformationalWriteSegments = false;
                return ObservationOutcome.none();
            }
            var request = requireRequestUnderAssembly();
            if (!request.hasInProgressSegment()) {
                return ObservationOutcome.none();
            }
            request.finalizeRequestSegments(timestamp);
            return added(assembly);
        }
        if (observation.hasEndOfMessageIndicator()) {
            return reconstituteRequest(timestamp, logAppendTimeMillis, containingRecord);
        }
        if (observation.hasWrite()) {
            return ObservationOutcome.none();
        }
        if (observation.hasWriteSegment()) {
            ignoringInformationalWriteSegments = true;
            return ObservationOutcome.none();
        }
        return ObservationOutcome.none();
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.handleObservationForWriteState ->
    //     SourceConnectionState.applyToResponse
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome applyToResponse(
        TrafficObservation observation,
        KafkaRecordId containingRecord,
        long logAppendTimeMillis,
        IReplayContexts.ITrafficStreamsLifecycleContext trafficContext
    ) {
        var timestamp = timestampOf(observation);
        var requestId = responseBeingAssembledFor;
        var association = new RecordAssociationId.Request(requestId);
        if (observation.hasWrite()) {
            appendTo(responseUnderAssembly(timestamp), observation.getWrite().getData().toByteArray(), timestamp);
            return added(association);
        }
        if (observation.hasWriteSegment()) {
            responseUnderAssembly(timestamp)
                .addSegment(observation.getWriteSegment().getData().toByteArray());
            return added(association);
        }
        if (observation.hasSegmentEnd()) {
            var response = responseUnderAssembly(timestamp);
            if (!response.hasInProgressSegment()) {
                return ObservationOutcome.none();
            }
            response.finalizeRequestSegments(timestamp);
            return added(association);
        }
        if (observation.hasRead() || observation.hasReadSegment()) {
            // Keep-alive: the next request begins, which is what ends the previous response. The response's
            // association stays until its tuple is durable, so nothing is finished here.
            // §9.2: the next request's read is the boundary that proves the source finished the previous
            // response. It is the only evidence of that available anywhere in the protocol.
            var completed = completeResponse(true);
            phase = Phase.ASSEMBLING_REQUEST;
            var next = applyToRequest(
                observation,
                containingRecord,
                logAppendTimeMillis,
                trafficContext
            );
            return new ObservationOutcome(
                concat(completed.associationsToAdd(), next.associationsToAdd()),
                concat(completed.associationsFinished(), next.associationsFinished()),
                concat(completed.relabels(), next.relabels()),
                false
            );
        }
        if (observation.hasEndOfMessageIndicator()) {
            // An end-of-message for a request while its response is assembling is the proxy reporting the
            // next request's boundary without any read observation yet; nothing to add to the response.
            return ObservationOutcome.none();
        }
        return ObservationOutcome.none();
    }

    // ------------------------------------------------------------------ transitions

    /** {@code §9.1}: the parser reconstituted a request, so it gains an identity and leaves this class. */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.handleEndOfRequest ->
    //     SourceConnectionState.reconstituteRequest
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome reconstituteRequest(
        Instant requestEndOfMessageSourceTime,
        long requestCompletingLogAppendTime,
        KafkaRecordId containingRecord
    ) {
        var request = requireRequestUnderAssembly();
        var assembly = currentAssemblyId();
        var replayRequestId = requestContext == null
            ? new ReplayRequestId(connectionProcessingId, currentCapturedRequestOrdinal)
            : requestContext.getRequestId();
        var requestAssociation = new RecordAssociationId.Request(replayRequestId);

        incomingRequest = null;
        ignoringInformationalWriteSegments = false;
        responseBeingAssembledFor = replayRequestId;
        responseStateByRequest.put(
            replayRequestId,
            new HttpMessageAndTimestamp.Response(requestEndOfMessageSourceTime)
        );
        requestEverReconstituted = true;
        phase = Phase.ASSEMBLING_RESPONSE;
        if (requestContext != null) {
            requestContext.onRequestReconstituted();
            requestAccumulationContext.close();
            requestAccumulationContext = null;
            responseAccumulationContext = requestContext.createResponseAccumulationContext();
        }

        // REBUILD-LIMBO-NOTE(G7): create §9.1's request-state bookkeeping for retry input, final source
        // response and demand before admitting the request to its connection owner.
        if (requestContext == null) {
            sink.onRequestReconstituted(
                replayRequestId,
                currentCapturedRequestOrdinal,
                request,
                request.getFirstPacketTimestamp(),
                requestEndOfMessageSourceTime,
                requestCompletingLogAppendTime
            );
        } else {
            sink.onRequestReconstituted(
                request,
                requestEndOfMessageSourceTime,
                requestCompletingLogAppendTime,
                requestContext
            );
        }
        // The end-of-message record contributes to the request too, and its association is the request's
        // from the outset rather than an assembly identity that is immediately relabelled.
        return new ObservationOutcome(
            List.of(requestAssociation),
            List.of(),
            List.of(new Relabel(assembly, requestAssociation)),
            false
        );
    }

    /**
     * Ends response assembly at a terminal boundary ({@code §9.2}).
     *
     * @param keptAlive whether the next request's read observation ended it, which is the only thing that
     *                  proves the source finished the response
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.rotateAccumulationIfNecessary ->
    //     SourceConnectionState.completeResponse
    // CapturedTrafficToHttpTransactionAccumulator.rotateAccumulationOnReadIfNecessary ->
    //     SourceConnectionState.completeResponse
    // CapturedTrafficToHttpTransactionAccumulator.handleEndOfResponse ->
    //     SourceConnectionState.completeResponse
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome completeResponse(boolean keptAlive) {
        var requestId = responseBeingAssembledFor;
        var response = responseStateByRequest.remove(requestId);
        responseBeingAssembledFor = null;
        if (response != null) {
            closeResponseAccumulationContext();
            sink.onSourceResponseComplete(requestId, response, keptAlive);
            currentCapturedRequestOrdinal++;
        }
        return ObservationOutcome.none();
    }

    /**
     * A connection exception ends only a response that is already under assembly.
     *
     * <p>{@code §9.2} names it as a response terminal boundary, while {@code §9.3} says it performs none of
     * the captured-close steps. In particular, it does not discard an incomplete request or end the lifetime.
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.handleCloseObservationThatAffectEveryState ->
    //     SourceConnectionState.applyConnectionException
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome applyConnectionException() {
        if (phase != Phase.ASSEMBLING_RESPONSE) {
            return ObservationOutcome.none();
        }
        var completed = completeResponse(false);
        phase = Phase.BETWEEN_REQUESTS;
        return completed;
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.handleCloseObservationThatAffectEveryState ->
    //     SourceConnectionState.applyCapturedClose
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome applyCapturedClose(KafkaRecordId containingRecord) {
        var terminal = new RecordAssociationId.TerminalConnection(connectionProcessingId);
        var abandoned = endAssemblyAtBoundary();
        lifetime = Lifetime.EXPLICITLY_CLOSED;
        if (requestEverReconstituted) {
            sink.onCapturedClose(
                connectionProcessingId,
                currentCapturedRequestOrdinal,
                latestObservationTime
            );
        }
        return new ObservationOutcome(
            concat(abandoned.associationsToAdd(), List.of(terminal)),
            concat(abandoned.associationsFinished(), List.of(terminal)),
            abandoned.relabels(),
            true
        );
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.handleDroppedRequestForAccumulation ->
    //     SourceConnectionState.applyRequestDropped
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome applyRequestDropped() {
        if (phase == Phase.DISCARDING_INHERITED_TAIL) {
            // §9/§9.4: fresh reconstruction already reserved the inherited request's ordinal. The marker is
            // its boundary, not a second suppressed request, and there are no process-local associations to
            // release because this lifetime never assembled its prefix.
            phase = Phase.BETWEEN_REQUESTS;
            return ObservationOutcome.none();
        }
        if (phase != Phase.ASSEMBLING_REQUEST || incomingRequest == null) {
            throw new CaptureProtocolViolation(
                "RequestIntentionallyDropped for " + connectionProcessingId
                    + " arrived without an incomplete request under assembly or inherited tail under discard"
            );
        }
        // §9.4: capture suppression became known only after the proxy had recorded a prefix. That prefix is
        // deliberately discarded, no replay request or tuple is created, and the next captured request keeps
        // the source's ordinal progression.
        var finished = List.<RecordAssociationId>of(currentAssemblyId());
        incomingRequest = null;
        closeIncompleteRequestContexts();
        ignoringInformationalWriteSegments = false;
        currentCapturedRequestOrdinal++;
        phase = Phase.BETWEEN_REQUESTS;
        return new ObservationOutcome(List.of(), finished, List.of(), false);
    }

    /**
     * Ends assembly at a captured close.
     *
     * <p>A response already under assembly is completed with {@code keptAlive = false}: it reached an end the
     * replayer observed, while whether the source had finished writing it is unprovable. Connection exceptions
     * use {@link #applyConnectionException()} because {@code §9.3} leaves an incomplete request untouched.
     *
     * <p>A request that never reached end-of-message has no identity at all, so nothing is notified about it
     * and its assembly association is simply released ({@code §8.2}).
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.fireAccumulationsCallbacksAndClose ->
    //     SourceConnectionState.endAssemblyAtBoundary
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome endAssemblyAtBoundary() {
        var finished = new java.util.ArrayList<RecordAssociationId>();
        if (incomingRequest != null) {
            finished.add(currentAssemblyId());
            incomingRequest = null;
            closeIncompleteRequestContexts();
        }
        ignoringInformationalWriteSegments = false;
        if (responseBeingAssembledFor != null) {
            completeResponse(false);
        }
        phase = Phase.BETWEEN_REQUESTS;
        return new ObservationOutcome(List.of(), finished, List.of(), false);
    }

    /**
     * Ends assembly because replay intake stopped, not because an end was observed.
     *
     * <p>{@code §9.2} reserves {@code SourceResponseIncomplete} for expiration and generation cancellation.
     * Both are the replayer's own doing, which is the whole content of the word: it says nothing about whether
     * the captured bytes are partial, because nothing here can know that.
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.fireAccumulationsCallbacksAndClose ->
    //     SourceConnectionState.stopAssembling
    // REBUILD-TRACE-END(G5,target)
    private ObservationOutcome stopAssembling(SourceAssemblySink.IncompleteReason reason) {
        var finished = new java.util.ArrayList<RecordAssociationId>();
        if (incomingRequest != null) {
            finished.add(currentAssemblyId());
            incomingRequest = null;
            closeIncompleteRequestContexts();
        }
        ignoringInformationalWriteSegments = false;
        if (responseBeingAssembledFor != null) {
            var requestId = responseBeingAssembledFor;
            responseStateByRequest.remove(requestId);
            responseBeingAssembledFor = null;
            closeResponseAccumulationContext();
            sink.onSourceResponseIncomplete(requestId, reason);
        }
        phase = Phase.BETWEEN_REQUESTS;
        return new ObservationOutcome(List.of(), finished, List.of(), false);
    }

    // ------------------------------------------------------------------ internals

    /**
     * {@code §9}: "The first observation in fresh reconstruction establishes the process-local sequence
     * baseline. Later observations in that lifetime must be contiguous."
     *
     * <p>A gap or regression is a capture-protocol violation rather than something to tolerate — it means
     * observations for this connection were lost or reordered, and any request assembled across the gap
     * would be wrong in a way no later check could detect.
     */
    private void requireSequenceContiguous(TrafficObservation observation) {
        if (!observation.hasConnectionObservationSequence()) {
            throw new CaptureProtocolViolation(
                "observation for " + connectionProcessingId + " carries no connectionObservationSequence"
            );
        }
        var sequence = observation.getConnectionObservationSequence();
        if (nextExpectedObservationSequence == null) {
            nextExpectedObservationSequence = sequence + 1;
            return;
        }
        if (sequence != nextExpectedObservationSequence) {
            throw new CaptureProtocolViolation(
                "observation sequence for " + connectionProcessingId + " expected "
                    + nextExpectedObservationSequence + " but was " + sequence
            );
        }
        nextExpectedObservationSequence = sequence + 1;
    }

    /** {@code §16}: invalid capture input, which reaches the Kafka source as a protocol violation. */
    public static final class CaptureProtocolViolation extends RuntimeException {
        public CaptureProtocolViolation(String message) {
            super(message);
        }
    }

    private RecordAssociationId currentAssemblyId() {
        return new RecordAssociationId.RequestAssembly(
            connectionProcessingId,
            currentCapturedRequestOrdinal
        );
    }

    private HttpMessageAndTimestamp.Request requestUnderAssembly(
        Instant firstPacketTimestamp,
        IReplayContexts.ITrafficStreamsLifecycleContext trafficContext
    ) {
        if (incomingRequest == null) {
            incomingRequest = new HttpMessageAndTimestamp.Request(firstPacketTimestamp);
            if (trafficContext != null) {
                requestContext = trafficContext.createRequestContext(
                    new ReplayRequestId(
                        connectionProcessingId,
                        currentCapturedRequestOrdinal
                    ),
                    firstPacketTimestamp
                );
                requestAccumulationContext = requestContext.createRequestAccumulationContext();
            }
        }
        return incomingRequest;
    }

    private void closeIncompleteRequestContexts() {
        if (requestAccumulationContext != null) {
            requestAccumulationContext.close();
            requestAccumulationContext = null;
        }
        if (requestContext != null) {
            requestContext.close();
            requestContext = null;
        }
    }

    private void closeResponseAccumulationContext() {
        if (responseAccumulationContext != null) {
            responseAccumulationContext.close();
            responseAccumulationContext = null;
        }
        requestContext = null;
    }

    private HttpMessageAndTimestamp.Request requireRequestUnderAssembly() {
        if (incomingRequest == null) {
            throw new CaptureProtocolViolation(
                "request observation for " + connectionProcessingId + " with no request under assembly"
            );
        }
        return incomingRequest;
    }

    private HttpMessageAndTimestamp.Response responseUnderAssembly(Instant firstPacketTimestamp) {
        if (responseBeingAssembledFor == null) {
            throw new CaptureProtocolViolation(
                "response observation for " + connectionProcessingId + " with no reconstituted request"
            );
        }
        return responseStateByRequest.computeIfAbsent(
            responseBeingAssembledFor,
            ignored -> new HttpMessageAndTimestamp.Response(firstPacketTimestamp)
        );
    }

    /**
     * Appends one packet and moves the message's last-packet timestamp.
     *
     * <p>Both, together: the timestamp is what decides the replay's inter-packet timing, so a packet added
     * without advancing it would replay at the wrong moment while looking correct byte for byte.
     */
    private static void appendTo(HttpMessageAndTimestamp message, byte[] packet, Instant timestamp) {
        message.add(packet);
        message.setLastPacketTimestamp(timestamp);
    }

    private static ObservationOutcome added(RecordAssociationId association) {
        return new ObservationOutcome(List.of(association), List.of(), List.of(), false);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        var combined = new java.util.ArrayList<T>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return combined;
    }

    private static Instant timestampOf(TrafficObservation observation) {
        var ts = observation.getTs();
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    @Override
    public String toString() {
        return connectionProcessingId + " " + phase + " ordinal=" + currentCapturedRequestOrdinal
            + " lifetime=" + lifetime;
    }
}
