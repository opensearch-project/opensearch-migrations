package org.opensearch.migrations.replay.scheduling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.replay.scheduling.DispatchDependencyClassifier.Dependency;

/**
 * Tracks per-session dispatch dependencies for replay scheduling. Each registered
 * request carries the predecessor's wire-level "request-sent" and "response-received"
 * futures. When a new request arrives, this tracker classifies its dependency on the
 * most-recent registered request and returns the appropriate gating future.
 *
 * <p>The tracker is a per-{@code ConnectionReplaySession} object so the dependencies
 * are scoped to a single source connection. Cross-session dispatch is always concurrent.
 *
 * <p>Thread-safety: callers must synchronize externally if they invoke from multiple
 * threads. The replayer's existing scheduler runs all session-scoped work on a single
 * Netty event loop, so this is safe by construction.
 */
public class SessionDependencyTracker {

    /** Snapshot of an in-flight or completed request used to gate later requests. */
    private static class TrackedRequest {
        final Instant requestFirstFrame;
        final Instant requestLastFrame;
        final Instant responseLastFrame;
        final CompletableFuture<?> requestSentFuture;
        final CompletableFuture<?> responseReceivedFuture;

        TrackedRequest(Instant requestFirstFrame, Instant requestLastFrame, Instant responseLastFrame,
                       CompletableFuture<?> requestSentFuture,
                       CompletableFuture<?> responseReceivedFuture) {
            this.requestFirstFrame = requestFirstFrame;
            this.requestLastFrame = requestLastFrame;
            this.responseLastFrame = responseLastFrame;
            this.requestSentFuture = requestSentFuture;
            this.responseReceivedFuture = responseReceivedFuture;
        }
    }

    private final List<TrackedRequest> tracked = new ArrayList<>();

    /**
     * Register a new request and return the future on which the scheduler should gate
     * it before dispatching. Returns a completed future when the request has no
     * dependency (concurrent with the predecessors or no predecessors yet).
     *
     * @param requestFirstFrame      this request's wire-level start
     * @param requestLastFrame       this request's wire-level end (last frame sent)
     * @param responseLastFrame      this request's wire-level response end (may be null)
     * @param requestSentFuture      future that completes when this request's bytes are
     *                                fully sent on the target side
     * @param responseReceivedFuture future that completes when this request's response
     *                                is fully received on the target side
     * @return future to await before dispatching this request; never null
     */
    public CompletableFuture<?> registerAndGetGate(Instant requestFirstFrame,
                                                     Instant requestLastFrame,
                                                     Instant responseLastFrame,
                                                     CompletableFuture<?> requestSentFuture,
                                                     CompletableFuture<?> responseReceivedFuture) {
        CompletableFuture<?> gate = CompletableFuture.completedFuture(null);
        if (requestFirstFrame != null && !tracked.isEmpty()) {
            // Find the latest predecessor that imposes a dependency. Sweep most-recent
            // first; the first non-CONCURRENT classification wins because more recent
            // predecessors dominate older ones (transitively).
            for (int i = tracked.size() - 1; i >= 0; i--) {
                var pred = tracked.get(i);
                var dep = DispatchDependencyClassifier.classify(
                        pred.requestLastFrame, pred.responseLastFrame, requestFirstFrame);
                if (dep == Dependency.AFTER_RESPONSE_RECEIVED) {
                    gate = pred.responseReceivedFuture;
                    break;
                } else if (dep == Dependency.AFTER_REQUEST_SENT) {
                    gate = pred.requestSentFuture;
                    break;
                }
                // Concurrent — keep scanning. A still-earlier predecessor might impose a
                // chained dependency even if the immediately-preceding one is concurrent.
            }
        }
        tracked.add(new TrackedRequest(
                requestFirstFrame, requestLastFrame, responseLastFrame,
                requestSentFuture, responseReceivedFuture));
        return gate;
    }

    /** Number of requests registered so far. Useful in tests. */
    public int size() { return tracked.size(); }

    /** Remove all tracked predecessors. Call when the session closes. */
    public void clear() { tracked.clear(); }
}
