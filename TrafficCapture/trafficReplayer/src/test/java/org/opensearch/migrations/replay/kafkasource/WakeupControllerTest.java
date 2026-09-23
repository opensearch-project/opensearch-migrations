/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.concurrent.atomic.AtomicInteger;

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
