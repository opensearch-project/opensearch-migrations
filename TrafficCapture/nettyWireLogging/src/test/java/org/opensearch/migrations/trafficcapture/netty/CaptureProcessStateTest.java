package org.opensearch.migrations.trafficcapture.netty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CaptureProcessStateTest {
    @Test
    void failOpenMakesOneIrreversibleProcessWidePassThroughTransition() {
        var state = new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN);
        var firstFailure = new IllegalStateException("first");

        Assertions.assertEquals(
            CaptureProcessState.State.PASS_THROUGH,
            state.requiredCaptureFailed(firstFailure)
        );
        Assertions.assertTrue(state.isPassThrough());
        Assertions.assertFalse(state.shouldCapture());
        Assertions.assertEquals(
            CaptureProcessState.State.PASS_THROUGH,
            state.requiredCaptureFailed(new IllegalStateException("later"))
        );
    }

    @Test
    void failClosedNotifiesTerminationListenerExactlyOnce() {
        var state = new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED);
        var observedFailure = new AtomicReference<Throwable>();
        var notificationCount = new AtomicInteger();
        state.addTerminationListener(failure -> {
            observedFailure.set(failure);
            notificationCount.incrementAndGet();
        });
        var firstFailure = new IllegalStateException("first");

        Assertions.assertEquals(
            CaptureProcessState.State.TERMINATING,
            state.requiredCaptureFailed(firstFailure)
        );
        state.requiredCaptureFailed(new IllegalStateException("later"));

        Assertions.assertEquals(firstFailure, observedFailure.get());
        Assertions.assertEquals(1, notificationCount.get());
    }

    @Test
    void unstableProcessAlwaysTerminatesEvenAfterFailOpenPassThrough() {
        var state = new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN);
        var observedFailure = new AtomicReference<Throwable>();
        var notificationCount = new AtomicInteger();
        state.addTerminationListener(failure -> {
            observedFailure.set(failure);
            notificationCount.incrementAndGet();
        });
        state.requiredCaptureFailed(new IllegalStateException("capture unavailable"));
        var unstableFailure = new IllegalStateException("event loop terminated");

        Assertions.assertEquals(
            CaptureProcessState.State.TERMINATING,
            state.unstableProcessFailed(unstableFailure)
        );
        state.unstableProcessFailed(new IllegalStateException("later"));

        Assertions.assertEquals(unstableFailure, observedFailure.get());
        Assertions.assertEquals(1, notificationCount.get());
    }

    @Test
    void failClosedTransitionWaitsForAlreadyStartedForwardingAndRejectsLaterForwarding()
        throws Exception {
        var state = new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED);
        var forwardingStarted = new CountDownLatch(1);
        var allowForwardingToFinish = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var forwarding = executor.submit(() ->
                state.forwardSourceTrafficIfPermitted(() -> {
                    forwardingStarted.countDown();
                    Assertions.assertTrue(allowForwardingToFinish.await(1, TimeUnit.SECONDS));
                    return null;
                })
            );
            Assertions.assertTrue(forwardingStarted.await(1, TimeUnit.SECONDS));
            var transition = executor.submit(() ->
                state.requiredCaptureFailed(new IllegalStateException("capture failed"))
            );

            allowForwardingToFinish.countDown();

            Assertions.assertTrue(forwarding.get(1, TimeUnit.SECONDS));
            Assertions.assertEquals(
                CaptureProcessState.State.TERMINATING,
                transition.get(1, TimeUnit.SECONDS)
            );
            Assertions.assertFalse(
                state.forwardSourceTrafficIfPermitted(() -> {
                    Assertions.fail("Forwarding must not run after fail-closed begins");
                    return null;
                })
            );
        } finally {
            executor.shutdownNow();
        }
    }
}
