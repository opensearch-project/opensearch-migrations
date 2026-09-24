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

    /**
     * A source response that reached a terminal boundary ({@code §9.2}).
     *
     * @param keptAlive true when the next request's read observation on the same connection ended this
     *                  response, which proves the source finished it. False when a close or a connection
     *                  exception ended it, where completion is unproven — nothing else can prove it, because
     *                  the capture protocol carries an end-of-message indication for requests only and the
     *                  replayer deliberately does not parse response framing. A consumer needing certainty
     *                  reads this rather than inferring from the outcome type
     */
    void onSourceResponseComplete(
        ReplayRequestId replayRequestId,
        HttpMessageAndTimestamp.Response response,
        boolean keptAlive
    );

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
    void onCapturedClose(ConnectionProcessingId connectionProcessingId, Instant closeTime);
}
