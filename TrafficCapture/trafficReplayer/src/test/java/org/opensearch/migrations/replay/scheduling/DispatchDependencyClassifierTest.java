package org.opensearch.migrations.replay.scheduling;

import java.time.Instant;

import org.opensearch.migrations.replay.scheduling.DispatchDependencyClassifier.Dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DispatchDependencyClassifierTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusMillis(100);
    private static final Instant T2 = T0.plusMillis(200);
    private static final Instant T3 = T0.plusMillis(300);
    private static final Instant T4 = T0.plusMillis(400);

    @Test
    void strictSerial_chainsOnResponse() {
        // A: req [T0..T1], resp [T2..T3]. B: req start T4. B is strictly after A.
        var d = DispatchDependencyClassifier.classify(T1, T3, T4);
        Assertions.assertEquals(Dependency.AFTER_RESPONSE_RECEIVED, d,
                "B starting after A's response completed must chain on A's response");
    }

    @Test
    void pipelined_chainsOnRequestSent() {
        // A: req [T0..T1], resp [T3..T4]. B: req start T2.
        // B started after A's request was fully sent but before A's response landed.
        var d = DispatchDependencyClassifier.classify(T1, T4, T2);
        Assertions.assertEquals(Dependency.AFTER_REQUEST_SENT, d,
                "H1-pipelined burst chains B on A's request-sent, not response");
    }

    @Test
    void overlapping_concurrent() {
        // A: req [T0..T2]. B: req start T1.
        // B started while A was still being sent: H2 multiplex / truly concurrent.
        var d = DispatchDependencyClassifier.classify(T2, T3, T1);
        Assertions.assertEquals(Dependency.CONCURRENT, d,
                "B starting before A's request finished is concurrent (H2 multiplex)");
    }

    @Test
    void exactBoundary_atRequestEnd_isAfterRequestSent() {
        // B starts exactly when A's request finishes. No null-second-after-A scenario;
        // the boundary is inclusive on the right (≥) for chained dependencies.
        var d = DispatchDependencyClassifier.classify(T1, T3, T1);
        Assertions.assertEquals(Dependency.AFTER_REQUEST_SENT, d,
                "exact match at A's request-end is the start of the AFTER_REQUEST_SENT region");
    }

    @Test
    void exactBoundary_atResponseEnd_isAfterResponseReceived() {
        var d = DispatchDependencyClassifier.classify(T1, T3, T3);
        Assertions.assertEquals(Dependency.AFTER_RESPONSE_RECEIVED, d,
                "exact match at A's response-end is the start of the AFTER_RESPONSE_RECEIVED region");
    }

    @Test
    void nullTimestamps_degradeToConcurrent() {
        Assertions.assertEquals(Dependency.CONCURRENT,
                DispatchDependencyClassifier.classify(null, T3, T4));
        Assertions.assertEquals(Dependency.CONCURRENT,
                DispatchDependencyClassifier.classify(T1, T3, null));
    }

    @Test
    void missingResponse_butRequestEnd_chainsOnRequestSent() {
        // A's response timestamp is null (e.g. RST_STREAM mid-response).
        // B starts after A's request finished. Chain on request-sent.
        var d = DispatchDependencyClassifier.classify(T1, null, T2);
        Assertions.assertEquals(Dependency.AFTER_REQUEST_SENT, d);
    }

    @Test
    void missingResponse_andOverlap_concurrent() {
        var d = DispatchDependencyClassifier.classify(T2, null, T1);
        Assertions.assertEquals(Dependency.CONCURRENT, d);
    }

    /**
     * Speedup invariance: scaling all timestamps by a constant factor must not change
     * the classification. Reconstructed concurrency is independent of replay rate.
     */
    @Test
    void scalingPreservesClassification() {
        // Original: strict serial.
        Assertions.assertEquals(Dependency.AFTER_RESPONSE_RECEIVED,
                DispatchDependencyClassifier.classify(T1, T3, T4));
        // Scale by 0.1 (10x speedup): all gaps shrink uniformly.
        var s1 = T0.plusMillis(10);
        var s3 = T0.plusMillis(30);
        var s4 = T0.plusMillis(40);
        Assertions.assertEquals(Dependency.AFTER_RESPONSE_RECEIVED,
                DispatchDependencyClassifier.classify(s1, s3, s4));
        // Scale by 10 (10x slowdown).
        var l1 = T0.plusMillis(1000);
        var l3 = T0.plusMillis(3000);
        var l4 = T0.plusMillis(4000);
        Assertions.assertEquals(Dependency.AFTER_RESPONSE_RECEIVED,
                DispatchDependencyClassifier.classify(l1, l3, l4));
    }
}
