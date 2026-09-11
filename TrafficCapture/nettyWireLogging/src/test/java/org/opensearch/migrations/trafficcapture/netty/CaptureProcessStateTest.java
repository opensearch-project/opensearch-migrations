package org.opensearch.migrations.trafficcapture.netty;

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
}
