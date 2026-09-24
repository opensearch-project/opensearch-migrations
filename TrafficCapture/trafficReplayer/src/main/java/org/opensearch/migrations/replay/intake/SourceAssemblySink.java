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
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

/**
 * Where source assembly's results go.
 *
 * <p>REBUILD-LIMBO-NOTE(G5): this is the seam for {@code connLLD §3}'s {@code ConnectionInput} family.
 * The four methods are named for the four messages replay intake sends — {@code AdmitReconstitutedRequest},
 * {@code SourceResponseComplete}, {@code SourceResponseIncomplete}, {@code AdmitCapturedClose} — and G5
 * replaces this interface with those values routed to a {@code TargetConnectionOwner}. Two fields of
 * {@code AdmitReconstitutedRequest} ({@code §3.1}) are transformation/target metadata and an
 * activity-monitor identity that G5 defines, which is why the value type is not declared yet: it cannot be
 * built without inventing them.
 *
 * <p>Called only on the replay-intake thread, synchronously from within one record's application, so an
 * implementation must not block.
 */
public interface SourceAssemblySink {

    /**
     * A request the parser reconstituted ({@code §9.1}).
     *
     * @param sourceEventTime      the request's frozen source event time, which {@code §3.1} uses to
     *                             calculate the nominal target send time
     * @param requestCompletingLogAppendTime {@code §9.1}'s "request-completing record {@code LogAppendTime
     *                             B}", frozen here because {@code §11}'s retry boundary is measured from it
     */
    void onRequestReconstituted(
        ReplayRequestId replayRequestId,
        long capturedRequestOrdinal,
        HttpMessageAndTimestamp.Request request,
        Instant sourceEventTime,
        long requestCompletingLogAppendTime
    );

    /** A source response that reached its end ({@code §9.2}). */
    void onSourceResponseComplete(ReplayRequestId replayRequestId, HttpMessageAndTimestamp.Response response);

    /**
     * A source response that will never be complete ({@code §9.2}).
     *
     * <p>Carries no bytes. {@code §9.2} requires that partial bytes are never "represented as complete", and
     * the honest way to guarantee that is for the incomplete signal to be unable to carry them at all.
     */
    void onSourceResponseIncomplete(ReplayRequestId replayRequestId, IncompleteReason reason);

    /** Why a source response ended without completing. */
    enum IncompleteReason {
        /** {@code CloseObservation} arrived while the response was still assembling ({@code §9.3}). */
        CAPTURED_CLOSE,
        /** The connection lifetime expired on broker time ({@code §9}). */
        EXPIRED,
        /** {@code ConnectionExceptionObservation} was recorded for the captured connection. */
        CONNECTION_EXCEPTION
    }

    /** The captured close for one source-connection lifetime ({@code §9.3} step 3). */
    void onCapturedClose(ConnectionProcessingId connectionProcessingId, Instant closeTime);
}
