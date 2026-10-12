/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.time.Instant;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.SourceResponseCompletionEvidence;
import org.opensearch.migrations.replay.SourceResponseFramingFallbackReason;
import org.opensearch.migrations.replay.identity.CancellationGrace;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

/**
 * Defines the non-blocking handoff from captured-source reconstruction to downstream replay processing.
 *
 * <p>The events preserve the source facts needed after Kafka records have been decoded: reconstituted
 * requests, interim and final source responses, captured closes, broker-time expiration, and generation
 * cancellation. Request and connection identities keep those facts attached to the exact local lifetime
 * that produced them.</p>
 *
 * <p>Final-response delivery and retry-policy input are deliberately separate one-shot signals. A response
 * may remain useful for tuple output after the bounded retry window has frozen its retry input as
 * unavailable. Likewise, an incomplete response describes replay abandoning assembly, not proof that the
 * captured source completed a response.</p>
 */
public interface SourceAssemblySink {

    /**
     * A request the parser reconstituted ({@code §9.1}).
     *
     * @param requestFirstByteSourceTime the first request byte's source event time
     * @param requestEndOfMessageSourceTime the request end marker's source event time
     * @param requestCompletingLogAppendTime {@code §9.1}'s "request-completing record {@code LogAppendTime
     *                             B}", frozen here because {@code §11}'s retry boundary is measured from it
     */
    void onRequestReconstituted(
        ReplayRequestId replayRequestId,
        long capturedRequestOrdinal,
        HttpMessageAndTimestamp.Request request,
        Instant requestFirstByteSourceTime,
        Instant requestEndOfMessageSourceTime,
        long requestCompletingLogAppendTime
    );

    /** A typed source interim response, preserved independently from the final response. */
    void onSourceInterimResponse(
        ReplayRequestId replayRequestId,
        HttpMessageAndTimestamp.InterimResponse interimResponse
    );

    /**
     * The same request delivery with its explicitly propagated replay scope.
     *
     * <p>The default keeps context-free deterministic source-assembly fixtures usable. The production sink
     * overrides this overload and requires the context.</p>
     */
    default void onRequestReconstituted(
        HttpMessageAndTimestamp.Request request,
        Instant requestEndOfMessageSourceTime,
        long requestCompletingLogAppendTime,
        IReplayContexts.IRequestContext replayContext
    ) {
        onRequestReconstituted(
            replayContext.getRequestId(),
            replayContext.getCapturedRequestOrdinal(),
            request,
            replayContext.getTimeOfOriginalRequest(),
            requestEndOfMessageSourceTime,
            requestCompletingLogAppendTime
        );
    }

    /**
     * A source response that reached a terminal boundary ({@code §9.2}).
     *
     * @param completionEvidence the observed boundary and whether it proves a complete HTTP response
     */
    void onSourceResponseComplete(
        ReplayRequestId replayRequestId,
        HttpMessageAndTimestamp.Response response,
        SourceResponseCompletionEvidence completionEvidence
    );

    /** Streaming HTTP framing could not safely determine this response's end. */
    default void onSourceResponseFramingFallback(
        ReplayRequestId replayRequestId,
        SourceResponseFramingFallbackReason reason,
        Throwable failure
    ) {}

    /**
     * Captured source output observed after HTTP framing already proved the preceding response complete.
     *
     * <p>This is diagnostic source evidence, not invalid capture input. Replay retains the complete capture
     * observation that contained the boundary, ignores later observations for that completed response, and
     * continues waiting for the next request or connection boundary. Zero-byte observations may be capture
     * lifecycle artifacts; positive-byte observations may indicate trailing source output.</p>
     */
    default void onPostFramingResponseObservation(
        ReplayRequestId replayRequestId,
        PostFramingObservationType observationType,
        int byteCount
    ) {}

    /** Fixed-cardinality classification for post-framing source observations. */
    enum PostFramingObservationType {
        WRITE,
        WRITE_SEGMENT,
        INTERIM_RESPONSE,
        INTERIM_RESPONSE_SEGMENT,
        SEGMENT_END,
        BUFFERED_WRITE
    }

    /**
     * The complete response frozen as the request's retry-policy input ({@code §11}).
     *
     * <p>This is deliberately distinct from {@link #onSourceResponseComplete}: the retry input may become
     * unavailable before a later response completes for final tuple output. Each receiver therefore accepts
     * its own one-shot input rather than inferring one lifetime from the other.</p>
     */
    default void onRetrySourceResponseComplete(
        ReplayRequestId replayRequestId,
        HttpMessageAndTimestamp.Response response
    ) {}

    /**
     * The request's retry-policy input was irreversibly frozen as unavailable ({@code §11}).
     */
    default void onSourceResponseUnavailableForRetry(ReplayRequestId replayRequestId) {}

    /**
     * Replay intake stopped assembling this response ({@code §9.2}).
     *
     * <p>This states something about the replayer, not about the captured bytes: {@code §9.2} reserves it for
     * expiration and generation cancellation, the two cases where intake gives up rather than observing an end.
     * It carries no bytes, because a signal that cannot carry them cannot misrepresent them.
     */
    void onSourceResponseIncomplete(ReplayRequestId replayRequestId, IncompleteReason reason);

    /** Why replay intake stopped assembling. Both are the replayer's own doing. */
    enum IncompleteReason {
        /** The connection lifetime expired on broker time ({@code §9}, {@code §10.3}). */
        EXPIRED,
        /** The partition generation was cancelled at revocation ({@code §15.1}, {@code §15.2}). */
        GENERATION_CANCELLED
    }

    /**
     * The captured close for a source-connection lifetime that reconstituted at least one request
     * ({@code §9.3} step 3). A request-less lifetime has no connection owner to receive this command.
     */
    void onCapturedClose(
        ConnectionProcessingId connectionProcessingId,
        long capturedOrdinal,
        Instant closeTime
    );

    /**
     * Broker-time expiration ended this process-local lifetime while preserving its captured identity for
     * possible fresh reconstruction ({@code §10.3}).
     */
    default void onCapturedConnectionExpired(ConnectionProcessingId connectionProcessingId) {}

    /** Delivers partition-generation grace to one published connection owner. */
    void onGracefulGenerationCancellation(
        ConnectionProcessingId connectionProcessingId,
        CancellationGrace grace
    );

    /** Delivers forced revocation cleanup to one published connection owner. */
    void onForceGenerationCancellation(ConnectionProcessingId connectionProcessingId);

    /**
     * Replay intake handled the matching owner's terminal completion and removed its source-side lifetime.
     */
    void onConnectionOwnerFinished(ConnectionProcessingId connectionProcessingId);
}
