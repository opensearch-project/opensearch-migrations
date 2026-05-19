package org.opensearch.migrations.replay.scheduling;

import java.time.Instant;

/**
 * Classifies the dispatch dependency between two requests captured on the same source
 * connection, based on their wire-level happens-before relations. Used by the H2-aware
 * replayer scheduler to reconstruct concurrency under arbitrary speedup factors.
 *
 * <p>Given an earlier request {@code A} and a later request {@code B} (both observed on
 * the same source connection), this classifier returns one of three dispatch decisions:
 *
 * <ul>
 *   <li>{@link Dependency#CONCURRENT} — B's first frame was on the wire before A's
 *       last frame finished, OR the captured timestamps are missing. Replay fires B
 *       independently of A; multiplexed transports send concurrently.</li>
 *   <li>{@link Dependency#AFTER_REQUEST_SENT} — A's request side was fully on the wire
 *       before B's request started, but A's response had not yet completed. Replay
 *       chains B's send on A's send-completion (HTTP/1.1 pipelining shape).</li>
 *   <li>{@link Dependency#AFTER_RESPONSE_RECEIVED} — A's response was fully back before
 *       B's request started. Replay chains B's send on A's response-completion
 *       (HTTP/1.1 keep-alive shape, the strict serial case).</li>
 * </ul>
 *
 * <p>The classification is invariant under speedup scaling: scaling all timestamps by a
 * constant factor preserves the relative ordering of the four anchors per request.
 */
public final class DispatchDependencyClassifier {

    private DispatchDependencyClassifier() {}

    /** Dependency between two requests on the same source connection. */
    public enum Dependency {
        CONCURRENT,
        AFTER_REQUEST_SENT,
        AFTER_RESPONSE_RECEIVED
    }

    /**
     * Classify B's dependency on A given their captured wire timestamps.
     *
     * <p>Any null timestamp degrades to {@link Dependency#CONCURRENT} — when the source
     * didn't capture full timing data we can't safely chain, so we fire concurrently.
     *
     * @param aResponseLast  A's response last-frame time (when A fully completed)
     * @param aRequestLast   A's request last-frame time (when A was fully sent)
     * @param bRequestFirst  B's request first-frame time (when B started being sent)
     * @return classification governing how B should be scheduled relative to A
     */
    public static Dependency classify(Instant aRequestLast,
                                        Instant aResponseLast,
                                        Instant bRequestFirst) {
        if (aRequestLast == null || bRequestFirst == null) {
            return Dependency.CONCURRENT;
        }
        if (aResponseLast != null && !bRequestFirst.isBefore(aResponseLast)) {
            // B started no earlier than A's response finished: strict serial.
            return Dependency.AFTER_RESPONSE_RECEIVED;
        }
        if (!bRequestFirst.isBefore(aRequestLast)) {
            // B started no earlier than A's request finished, but A's response wasn't back:
            // pipelined send (H1 pipelining shape).
            return Dependency.AFTER_REQUEST_SENT;
        }
        // B started while A was still mid-flight on the wire: truly concurrent (H2).
        return Dependency.CONCURRENT;
    }
}
