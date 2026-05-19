package org.opensearch.migrations.replay.scheduling;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SessionDependencyTrackerTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusMillis(100);
    private static final Instant T2 = T0.plusMillis(200);
    private static final Instant T3 = T0.plusMillis(300);
    private static final Instant T4 = T0.plusMillis(400);
    private static final Instant T5 = T0.plusMillis(500);
    private static final Instant T6 = T0.plusMillis(600);

    @Test
    void firstRequest_hasCompletedGate() {
        var t = new SessionDependencyTracker();
        var gate = t.registerAndGetGate(T0, T1, T3,
                new CompletableFuture<>(), new CompletableFuture<>());
        Assertions.assertTrue(gate.isDone(), "first request has no predecessor — gate is completed");
    }

    @Test
    void serialPredecessor_gatesOnResponseReceived() {
        var t = new SessionDependencyTracker();
        var aResponseReceived = new CompletableFuture<>();
        var aRequestSent = new CompletableFuture<>();
        // A: req [T0..T1], resp [T2..T3].
        t.registerAndGetGate(T0, T1, T3, aRequestSent, aResponseReceived);
        // B: req start T4 — strictly after A's response.
        var bGate = t.registerAndGetGate(T4, T5, T6,
                new CompletableFuture<>(), new CompletableFuture<>());
        Assertions.assertSame(aResponseReceived, bGate,
                "B chains on A's response-received future");
        Assertions.assertFalse(bGate.isDone(),
                "gate stays uncompleted until A's response actually lands");
        aResponseReceived.complete(null);
        Assertions.assertTrue(bGate.isDone(), "completing A's response unblocks B");
    }

    @Test
    void pipelinedPredecessor_gatesOnRequestSent() {
        var t = new SessionDependencyTracker();
        var aRequestSent = new CompletableFuture<>();
        var aResponseReceived = new CompletableFuture<>();
        // A: req [T0..T1], resp [T4..T5].
        t.registerAndGetGate(T0, T1, T5, aRequestSent, aResponseReceived);
        // B: req start T2 — after A's request sent, before A's response.
        var bGate = t.registerAndGetGate(T2, T3, T6,
                new CompletableFuture<>(), new CompletableFuture<>());
        Assertions.assertSame(aRequestSent, bGate,
                "pipelined B chains on A's request-sent future, not response");
    }

    @Test
    void concurrentPredecessor_gateImmediate() {
        var t = new SessionDependencyTracker();
        // A: req [T0..T2].
        t.registerAndGetGate(T0, T2, T4,
                new CompletableFuture<>(), new CompletableFuture<>());
        // B: req start T1 — overlaps A's request: H2-multiplex / concurrent.
        var bGate = t.registerAndGetGate(T1, T3, T5,
                new CompletableFuture<>(), new CompletableFuture<>());
        Assertions.assertTrue(bGate.isDone(),
                "concurrent B gates immediately — fires in parallel with A");
    }

    @Test
    void mixed_threeRequests_classifiedIndependently() {
        var t = new SessionDependencyTracker();
        var aRequestSent = new CompletableFuture<>();
        var aResponseReceived = new CompletableFuture<>();
        var bRequestSent = new CompletableFuture<>();
        var bResponseReceived = new CompletableFuture<>();
        // A: req [T0..T1], resp [T4..T5].   B: req [T2..T3] overlaps A's response window.
        // C: req start T6 — after both A and B fully complete (strict serial wrt B).
        t.registerAndGetGate(T0, T1, T5, aRequestSent, aResponseReceived);
        var bGate = t.registerAndGetGate(T2, T3, T5,  // bRequestLast=T3, bResponseLast=T5
                bRequestSent, bResponseReceived);
        Assertions.assertSame(aRequestSent, bGate,
                "B chains on A's request-sent (pipelined)");

        var cGate = t.registerAndGetGate(T6, T6, T6,
                new CompletableFuture<>(), new CompletableFuture<>());
        Assertions.assertSame(bResponseReceived, cGate,
                "C chains on B's response-received (strict serial wrt the latest predecessor)");
    }

    @Test
    void clear_resetsTracker() {
        var t = new SessionDependencyTracker();
        t.registerAndGetGate(T0, T1, T3,
                new CompletableFuture<>(), new CompletableFuture<>());
        Assertions.assertEquals(1, t.size());
        t.clear();
        Assertions.assertEquals(0, t.size());
        // Next register acts as a first-request again.
        var gate = t.registerAndGetGate(T4, T5, T6,
                new CompletableFuture<>(), new CompletableFuture<>());
        Assertions.assertTrue(gate.isDone());
    }
}
