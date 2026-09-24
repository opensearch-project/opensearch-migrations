/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.KafkaSourceRootContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Covers the wakeup cases in {@code kafkaLLD §17.4}. */
class WakeupControllerTest {

    private final AtomicInteger wakeups = new AtomicInteger();
    private final InMemoryInstrumentationBundle telemetry = new InMemoryInstrumentationBundle(true, true);
    private final WakeupController controller = new WakeupController(
        wakeups::incrementAndGet,
        // REBUILD-LIMBO-NOTE(G3): becomes RootReplayerContext.
        new KafkaSourceRootContext(telemetry.openTelemetrySdk)
    );

    @AfterEach
    void closeTelemetry() {
        telemetry.close();
    }

    /**
     * A wakeup absorbed by a Kafka call inside a protected operation must not leave the controller believing one
     * is still outstanding.
     *
     * <p>{@code kafkaLLD §5.4}: wakeups submitted during callback handling are "issued when callback handling
     * finishes". A blocking commit inside the callback throws {@code WakeupException} for a wakeup issued before
     * it began — {@code §5.7} names that path — which spends it. Left marked outstanding, the callback's exit
     * coalesces its deferred wakeup into nothing and the surrounding poll runs its full timeout with inputs
     * queued: the opposite of what §5.4 requires.
     */
    @Test
    void aWakeupAbsorbedInsideACallbackDoesNotSuppressTheDeferredOne() {
        controller.enterPoll();
        Assertions.assertTrue(controller.onInputSubmitted(), "precondition: one wakeup is outstanding");
        Assertions.assertEquals(1, wakeups.get());

        controller.enterRebalanceCallback();
        controller.enterProtectedOperation();
        // The commit inside the callback consumed the outstanding wakeup.
        controller.onWakeupAbsorbedByProtectedOperation();
        controller.leaveProtectedOperation();

        // An input arriving during the callback must produce a wakeup when the callback returns.
        Assertions.assertFalse(controller.onInputSubmitted(), "a callback defers rather than issuing");
        controller.leaveRebalanceCallback();

        Assertions.assertEquals(
            2,
            wakeups.get(),
            "the deferred wakeup must be issued on callback exit; it was coalesced into the one the commit had"
                + " already consumed"
        );
    }

    /**
     * Only the protected operation itself may report absorbing a wakeup.
     *
     * <p>Outside that phase the outstanding flag belongs to the poll boundary, and clearing it from anywhere else
     * consumes a wakeup {@link WakeupController#leavePollAndConsumeWakeup()} still has to see — leaving the
     * controller free to issue a second one into a Kafka operation, which {@code kafkaLLD §5.4} forbids.
     */
    @Test
    void onlyAProtectedOperationMayReportAbsorbingAWakeup() {
        controller.enterPoll();
        Assertions.assertTrue(controller.onInputSubmitted(), "precondition: one wakeup is outstanding");

        Assertions.assertThrows(
            IllegalStateException.class,
            controller::onWakeupAbsorbedByProtectedOperation,
            "a caller outside a protected operation must not consume the poll boundary's wakeup"
        );
        Assertions.assertTrue(
            controller.isWakeupOutstanding(),
            "the rejected call must leave the wakeup for the poll boundary to consume"
        );
    }

    /**
     * Absorption is counted, because no other series records it.
     *
     * <p>The wakeup was issued, so {@code WAKEUPS_ISSUED} has it; the poll it was meant to shorten was never
     * woken, so {@code POLLS_WOKEN_BY_QUEUED_INPUT} correctly does not. Without its own counter, a commit
     * repeatedly swallowing wakeups is indistinguishable from a run with no wakeups at all — which is exactly
     * the state that produced two defects on this path.
     */
    @Test
    void absorbingAWakeupIsCounted() {
        controller.enterPoll();
        Assertions.assertTrue(controller.onInputSubmitted(), "precondition: one wakeup is outstanding");
        controller.enterRebalanceCallback();
        controller.enterProtectedOperation();

        controller.onWakeupAbsorbedByProtectedOperation();

        controller.leaveProtectedOperation();
        controller.leaveRebalanceCallback();
        Assertions.assertEquals(
            1,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                IKafkaConsumerContexts.MetricNames.WAKEUPS_ABSORBED_BY_PROTECTED_OPERATION
            ),
            "an absorbed wakeup must be visible as its own series"
        );
    }

    /**
     * A poll must not settle in to wait on input that is already queued.
     *
     * <p>A submission while the loop is {@code RUNNING} correctly issues no wakeup — the loop reaches the queue
     * on its own — but the loop has already passed its drain by the time it polls, so that input would otherwise
     * wait out the whole poll timeout. {@code kafkaLLD §5.4}'s predicate is deliberately conservative: act
     * whenever the Kafka thread <em>may</em> be waiting.
     *
     * <p>This gap is what the {@code §4.1} falsification pass caught. The production fix had no test, so
     * reverting it changed nothing any test observed.
     */
    @Test
    void aPollDoesNotWaitWhenInputIsAlreadyQueued() {
        // Submitted while RUNNING: nothing issued, and nothing recorded as pending either.
        Assertions.assertFalse(controller.onInputSubmitted(), "a RUNNING submission needs no wakeup");
        Assertions.assertEquals(0, wakeups.get());

        controller.enterPoll(true);

        Assertions.assertEquals(
            1,
            wakeups.get(),
            "entering a poll with input already queued must issue a wakeup, or that input waits out the whole"
                + " poll timeout even though the loop has already passed its drain"
        );
        Assertions.assertTrue(controller.isWakeupOutstanding());
        controller.leavePollAndConsumeWakeup();
        Assertions.assertFalse(controller.isWakeupOutstanding());
    }

    /** The control case: an empty queue must not provoke a wakeup, or every poll would be interrupted. */
    @Test
    void aPollWithNothingQueuedIssuesNoWakeup() {
        controller.enterPoll(false);

        Assertions.assertEquals(0, wakeups.get(), "an empty queue must leave the poll alone");
        controller.leavePollAndConsumeWakeup();
    }

    @Test
    void aQueuedInputWakesALongPollAndRepeatSubmissionsCoalesce() {
        controller.enterPoll();

        Assertions.assertTrue(controller.onInputSubmitted());
        Assertions.assertFalse(controller.onInputSubmitted());
        Assertions.assertFalse(controller.onInputSubmitted());

        Assertions.assertEquals(1, wakeups.get(), "three submissions during one poll must coalesce to one");
        Assertions.assertTrue(controller.isWakeupOutstanding());

        controller.leavePollAndConsumeWakeup();
        Assertions.assertFalse(controller.isWakeupOutstanding());
    }

    @Test
    void noWakeupIsIssuedWhileTheLoopIsRunningBecauseItReachesTheQueueAnyway() {
        Assertions.assertFalse(controller.onInputSubmitted());
        Assertions.assertEquals(0, wakeups.get());
        Assertions.assertFalse(controller.isWakeupOutstanding());
    }

    @Test
    void wakeupsDuringARebalanceCallbackAreDeferredThenIssuedOnceWhenItReturns() {
        controller.enterPoll();
        controller.leavePollAndConsumeWakeup();
        controller.enterPoll();
        controller.enterRebalanceCallback();

        Assertions.assertFalse(controller.onInputSubmitted());
        Assertions.assertFalse(controller.onInputSubmitted());
        Assertions.assertEquals(0, wakeups.get(), "a wakeup must not interrupt a rebalance callback");

        controller.leaveRebalanceCallback();

        Assertions.assertEquals(1, wakeups.get(), "deferred wakeups coalesce into one when the callback ends");
        Assertions.assertTrue(controller.isWakeupOutstanding());
    }

    @Test
    void aRebalanceCallbackWithNoSubmissionIssuesNothing() {
        controller.enterPoll();
        controller.enterRebalanceCallback();
        controller.leaveRebalanceCallback();

        Assertions.assertEquals(0, wakeups.get());
    }

    @Test
    void wakeupsDuringAProtectedOperationAreDeferredUntilTheNextPollRatherThanInterruptingIt() {
        controller.enterProtectedOperation();

        Assertions.assertFalse(controller.onInputSubmitted());
        Assertions.assertEquals(0, wakeups.get(), "a wakeup must not interrupt a commit");

        controller.leaveProtectedOperation();
        Assertions.assertEquals(
            0,
            wakeups.get(),
            "still nothing to interrupt: the loop reaches the queue without help"
        );

        controller.enterPoll();
        Assertions.assertEquals(1, wakeups.get(), "the pending wakeup must not let the next poll wait on it");
    }

    @Test
    void phaseTransitionsAreCheckedRatherThanAssumed() {
        Assertions.assertThrows(IllegalStateException.class, controller::leaveRebalanceCallback);
        Assertions.assertThrows(IllegalStateException.class, controller::leaveProtectedOperation);
        Assertions.assertThrows(IllegalStateException.class, controller::enterRebalanceCallback);

        controller.enterPoll();
        Assertions.assertThrows(IllegalStateException.class, controller::enterPoll);
        Assertions.assertThrows(IllegalStateException.class, controller::enterProtectedOperation);
    }
}
