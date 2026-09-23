/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.Objects;
import java.util.function.Consumer;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/**
 * Decides when {@code KafkaConsumer.wakeup()} may be issued.
 *
 * <p>Wakeup carries no command and changes no source state. It only asks the Kafka thread to stop waiting
 * in {@code poll()} and inspect {@link KafkaSourceInputQueue}, so the queue is always written before a
 * wakeup is considered — see {@code kafkaLLD §5.4}.
 *
 * <p>Wakeup is <strong>not</strong> safe to issue at arbitrary times. Delivered during a rebalance callback
 * or another Kafka operation it would interrupt an operation that must complete, so in those phases the
 * controller records that a wakeup is pending and issues nothing. Leaving a phase checks for a pending
 * wakeup and issues one then, which is why {@link #leaveRebalanceCallback} and
 * {@link #leaveProtectedOperation} must be called even on a failure path.
 *
 * <p>At most one wakeup is outstanding at a time. Many submissions while the Kafka thread sits in one
 * {@code poll()} coalesce into the single wakeup that poll already needs, and
 * {@link #leavePollAndConsumeWakeup()} is what clears it. That call is mandatory even when {@code poll()}
 * returned normally rather than throwing {@code WakeupException}: an unconsumed wakeup would otherwise
 * interrupt whatever Kafka operation ran next, which is the one thing wakeup must never do.
 *
 * <p>This state is shared by construction — any thread submits, only the Kafka thread polls — so unlike
 * owner state it cannot be confined to one thread. It is guarded by this object's monitor, held only for
 * the state transition itself.
 *
 * <p>Every phase is a span and every decision a counter, because the properties that matter here are about
 * <em>timing</em> and cannot be read off the final state: that a wakeup actually shortened a long poll, that
 * one never landed during a rebalance callback, that repeated submissions produced one wakeup rather than
 * several. The {@code kafkaSourcePoll} span's duration is the observable form of "a queued input wakes a long
 * poll promptly", which is otherwise only visible as a test that takes as long as its poll timeout.
 */
public final class WakeupController {

    /** What the Kafka thread is doing, which is what decides whether a wakeup may be delivered. */
    private enum Phase {
        /** Running the source loop, outside any Kafka call. A wakeup is unnecessary; the loop will look. */
        RUNNING,
        /** Inside {@code poll()}, possibly waiting. A wakeup is the only way to shorten that wait. */
        POLLING,
        /** Inside a rebalance callback, which must finish; wakeup would interrupt the rebalance itself. */
        REBALANCE_CALLBACK,
        /** Inside commit or another Kafka operation that wakeup is not permitted to interrupt. */
        PROTECTED_OPERATION
    }

    /** Span and metric names, referenced by tests, so a rename cannot silently break their assertions. */
    public static final String POLL_SPAN = "kafkaSourcePoll";
    public static final String REBALANCE_CALLBACK_SPAN = "kafkaSourceRebalanceCallback";
    public static final String PROTECTED_OPERATION_SPAN = "kafkaSourceProtectedOperation";
    public static final String WAKEUPS_ISSUED = "kafkaSourceWakeupsIssued";
    public static final String WAKEUPS_COALESCED = "kafkaSourceWakeupsCoalesced";
    public static final String WAKEUPS_DEFERRED = "kafkaSourceWakeupsDeferred";

    private final Consumer<Void> issueWakeup;
    private final Tracer tracer;
    private final LongCounter wakeupsIssued;
    private final LongCounter wakeupsCoalesced;
    private final LongCounter wakeupsDeferred;

    private Phase phase = Phase.RUNNING;
    private boolean wakeupPending;
    private boolean wakeupOutstanding;
    private Span phaseSpan;
    private Span callbackSpan;

    /**
     * @param issueWakeup normally {@code consumer::wakeup}; injected so a test can observe issuance without
     *                    a broker, and because the controller must not depend on the consumer itself
     */
    public WakeupController(Runnable issueWakeup) {
        this(issueWakeup, OpenTelemetry.noop());
    }

    public WakeupController(Runnable issueWakeup, OpenTelemetry telemetry) {
        Objects.requireNonNull(issueWakeup, "issueWakeup");
        Objects.requireNonNull(telemetry, "telemetry");
        this.issueWakeup = ignored -> issueWakeup.run();
        this.tracer = telemetry.getTracer("org.opensearch.migrations.replay.kafkasource");
        var meter = telemetry.getMeter("org.opensearch.migrations.replay.kafkasource");
        this.wakeupsIssued = meter.counterBuilder(WAKEUPS_ISSUED)
            .setDescription("Kafka consumer wakeups actually issued").build();
        this.wakeupsCoalesced = meter.counterBuilder(WAKEUPS_COALESCED)
            .setDescription("Submissions that found a wakeup already outstanding").build();
        this.wakeupsDeferred = meter.counterBuilder(WAKEUPS_DEFERRED)
            .setDescription("Submissions during a phase a wakeup must not interrupt").build();
    }

    /**
     * Called after an input has been placed in the queue. Issues a wakeup only if the Kafka thread may be
     * waiting in {@code poll()} and no wakeup is already outstanding.
     *
     * @return true if a wakeup was issued, for observability and tests
     */
    public synchronized boolean onInputSubmitted() {
        if (phase == Phase.POLLING) {
            return issueUnlessOutstanding();
        }
        // RUNNING needs no wakeup: the loop reaches the queue on its own. The protected phases must not be
        // interrupted, so remember the need and issue it when the phase ends.
        if (phase != Phase.RUNNING) {
            wakeupPending = true;
            wakeupsDeferred.add(1);
        }
        return false;
    }

    public synchronized void enterPoll() {
        requirePhase(Phase.RUNNING, "enterPoll");
        phase = Phase.POLLING;
        phaseSpan = tracer.spanBuilder(POLL_SPAN).startSpan();
        // A submission that arrived while the loop was RUNNING left nothing to issue, but one that arrived
        // during a protected phase may still be pending; poll must not wait on it.
        if (wakeupPending) {
            wakeupPending = false;
            issueUnlessOutstanding();
        }
    }

    /**
     * Leaves {@code poll()} and consumes any outstanding wakeup, whether {@code poll()} returned records or
     * threw {@code WakeupException}. Both are the same controlled boundary.
     */
    public synchronized void leavePollAndConsumeWakeup() {
        requirePhase(Phase.POLLING, "leavePollAndConsumeWakeup");
        phase = Phase.RUNNING;
        endPhaseSpan("wokenByQueuedInput", wakeupOutstanding);
        wakeupOutstanding = false;
    }

    /** True if a wakeup has been issued and not yet consumed at a poll boundary. */
    public synchronized boolean isWakeupOutstanding() {
        return wakeupOutstanding;
    }

    public synchronized void enterRebalanceCallback() {
        requirePhase(Phase.POLLING, "enterRebalanceCallback");
        phase = Phase.REBALANCE_CALLBACK;
        // The poll span stays open underneath: the callback runs inside poll(), and nesting would claim
        // otherwise. A separate span measures the callback itself.
        callbackSpan = tracer.spanBuilder(REBALANCE_CALLBACK_SPAN).startSpan();
    }

    /**
     * Returns to {@code POLLING} and issues one wakeup if any submission arrived while the callback ran.
     *
     * <p>The surrounding {@code poll()} then returns or throws {@code WakeupException}, and the loop drains
     * the queue. If this happens after {@code onPartitionsRevoked} but before {@code onPartitionsAssigned},
     * the next {@code poll()} continues the rebalance: the assignment callback is postponed, not lost
     * ({@code kafkaLLD §5.4}).
     */
    public synchronized void leaveRebalanceCallback() {
        requirePhase(Phase.REBALANCE_CALLBACK, "leaveRebalanceCallback");
        phase = Phase.POLLING;
        var issuedOnExit = false;
        if (wakeupPending) {
            wakeupPending = false;
            issuedOnExit = issueUnlessOutstanding();
        }
        if (callbackSpan != null) {
            callbackSpan.setAttribute("issuedDeferredWakeupOnExit", issuedOnExit);
            callbackSpan.end();
            callbackSpan = null;
        }
    }

    /**
     * Guards commit and any other Kafka operation a wakeup must not interrupt.
     *
     * <p>Only valid from {@code RUNNING}, which is reachable only through
     * {@link #leavePollAndConsumeWakeup()}, so an outstanding wakeup here is unreachable rather than merely
     * unexpected. The check remains because that reasoning depends on the two being one operation, and a
     * later change that separates them would make it reachable.
     */
    public synchronized void enterProtectedOperation() {
        requirePhase(Phase.RUNNING, "enterProtectedOperation");
        if (wakeupOutstanding) {
            throw new IllegalStateException(
                "a wakeup is still outstanding at the start of a protected Kafka operation;"
                    + " it must be consumed at a poll boundary first"
            );
        }
        phase = Phase.PROTECTED_OPERATION;
        phaseSpan = tracer.spanBuilder(PROTECTED_OPERATION_SPAN).startSpan();
    }

    public synchronized void leaveProtectedOperation() {
        requirePhase(Phase.PROTECTED_OPERATION, "leaveProtectedOperation");
        phase = Phase.RUNNING;
        endPhaseSpan("wakeupDeferredDuringOperation", wakeupPending);
        // Left pending rather than issued: there is no poll to interrupt, and the loop will reach the queue.
    }

    private boolean issueUnlessOutstanding() {
        if (wakeupOutstanding) {
            wakeupsCoalesced.add(1);
            return false;
        }
        wakeupOutstanding = true;
        wakeupsIssued.add(1);
        issueWakeup.accept(null);
        return true;
    }

    private void endPhaseSpan(String attributeName, boolean attributeValue) {
        if (phaseSpan != null) {
            phaseSpan.setAttribute(attributeName, attributeValue);
            phaseSpan.end();
            phaseSpan = null;
        }
    }

    private void requirePhase(Phase expected, String operation) {
        if (phase != expected) {
            throw new IllegalStateException(operation + " requires phase " + expected + " but was " + phase);
        }
    }
}
