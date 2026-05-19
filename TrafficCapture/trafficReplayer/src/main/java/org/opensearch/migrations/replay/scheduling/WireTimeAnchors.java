package org.opensearch.migrations.replay.scheduling;

import java.time.Instant;

/**
 * Immutable wire-level timestamps captured on the source side of a single request, used by
 * {@link SessionDependencyTracker} to classify dispatch dependencies between successive
 * requests on the same source connection.
 *
 * <p>All four anchors may be {@code null} (e.g. the H1 path doesn't observe per-frame
 * timestamps); a null anchor degrades to {@code CONCURRENT} in the classifier and the
 * scheduler falls back to its pre-existing time-based behavior. Non-null anchors are
 * always source-side instants — translation to real time is the scheduler's job, not
 * this record's.
 *
 * @param requestFirstFrame  earliest source-side frame of the request side
 * @param requestLastFrame   final source-side frame of the request side
 * @param responseFirstFrame earliest source-side frame of the response side
 * @param responseLastFrame  final source-side frame of the response side
 */
public record WireTimeAnchors(Instant requestFirstFrame,
                              Instant requestLastFrame,
                              Instant responseFirstFrame,
                              Instant responseLastFrame) {

    /** True when neither request anchor is present — the tracker has nothing to classify on. */
    public boolean isEmpty() {
        return requestFirstFrame == null && requestLastFrame == null;
    }
}
