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

import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.KafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.KafkaSourceRootContext;

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
 * <p>Every phase is a scoped instrumentation context and every decision a counter, because the properties
 * that matter here are about <em>timing</em> and cannot be read off the final state: that a wakeup actually
 * shortened a long poll, that one never landed during a rebalance callback, that repeated submissions
 * produced one wakeup rather than several. The {@code kafkaPoll} span's duration is the observable form of
 * "a queued input wakes a long poll promptly", which is otherwise only visible as a test that takes as long
 * as its poll timeout.
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

    private final Consumer<Void> issueWakeup;
    // REBUILD-LIMBO-NOTE(G3): becomes RootReplayerContext.
    private final KafkaSourceRootContext rootContext;

    private Phase phase = Phase.RUNNING;
    private boolean wakeupPending;
    private boolean wakeupOutstanding;
    private IKafkaConsumerContexts.IPollScopeContext pollContext;
    private IKafkaConsumerContexts.ICommitScopeContext commitContext;
    private IKafkaConsumerContexts.IRebalanceCallbackScopeContext callbackContext;

    /**
     * @param issueWakeup normally {@code consumer::wakeup}; injected so a test can observe issuance without
     *                    a broker, and because the controller must not depend on the consumer itself
     * @param rootContext the scope its phase contexts hang off, which is where the instruments live
     */
    public WakeupController(Runnable issueWakeup, KafkaSourceRootContext rootContext) {
        Objects.requireNonNull(issueWakeup, "issueWakeup");
        this.issueWakeup = ignored -> issueWakeup.run();
        this.rootContext = Objects.requireNonNull(rootContext, "rootContext");
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
            pollInstruments().wakeupsDeferred.add(1);
        }
        return false;
    }

    public synchronized void enterPoll() {
        requirePhase(Phase.RUNNING, "enterPoll");
        phase = Phase.POLLING;
        // The context counts pollsEntered as it opens, so a poll in progress is observable to other threads.
        pollContext = rootContext.createPollContext();
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
        if (wakeupOutstanding) {
            pollContext.onWokenByQueuedInput();
        }
        pollContext.close();
        pollContext = null;
        wakeupOutstanding = false;
    }

    /** True if a wakeup has been issued and not yet consumed at a poll boundary. */
    public synchronized boolean isWakeupOutstanding() {
        return wakeupOutstanding;
    }

    public synchronized void enterRebalanceCallback() {
        requirePhase(Phase.POLLING, "enterRebalanceCallback");
        phase = Phase.REBALANCE_CALLBACK;
        // The poll context stays open underneath, because the callback really does run inside poll().
        callbackContext = rootContext.createRebalanceCallbackContext();
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
        if (wakeupPending) {
            wakeupPending = false;
            if (issueUnlessOutstanding()) {
                callbackContext.onIssuedDeferredWakeupOnExit();
            }
        }
        callbackContext.close();
        callbackContext = null;
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
        commitContext = rootContext.createCommitContext();
    }

    public synchronized void leaveProtectedOperation() {
        requirePhase(Phase.PROTECTED_OPERATION, "leaveProtectedOperation");
        phase = Phase.RUNNING;
        commitContext.close();
        commitContext = null;
        // Left pending rather than issued: there is no poll to interrupt, and the loop will reach the queue.
    }

    private boolean issueUnlessOutstanding() {
        if (wakeupOutstanding) {
            pollInstruments().wakeupsCoalesced.add(1);
            return false;
        }
        wakeupOutstanding = true;
        pollInstruments().wakeupsIssued.add(1);
        issueWakeup.accept(null);
        return true;
    }

    /**
     * The wakeup counters live on the poll scope's instruments because every wakeup exists to affect a poll,
     * whichever phase the submission arrived in. Read from the root rather than from the current context, so
     * a deferral counted while no poll is open still lands on the same series.
     */
    private KafkaConsumerContexts.PollScopeContext.MetricInstruments pollInstruments() {
        return rootContext.pollInstruments;
    }

    private void requirePhase(Phase expected, String operation) {
        if (phase != expected) {
            throw new IllegalStateException(operation + " requires phase " + expected + " but was " + phase);
        }
    }
}
