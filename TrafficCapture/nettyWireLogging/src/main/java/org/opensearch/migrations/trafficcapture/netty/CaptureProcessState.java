package org.opensearch.migrations.trafficcapture.netty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

/**
 * One-way process-wide response to a failure that compromises required capture.
 */
@Slf4j
public final class CaptureProcessState {
    public enum State {
        CAPTURE,
        PASS_THROUGH,
        TERMINATING
    }

    private final CaptureFailurePolicy failurePolicy;
    private final List<Consumer<Throwable>> terminationListeners = new ArrayList<>();
    private State state = State.CAPTURE;
    private Throwable captureFailure;

    public CaptureProcessState(CaptureFailurePolicy failurePolicy) {
        this.failurePolicy = Objects.requireNonNull(failurePolicy);
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean shouldCapture() {
        return state == State.CAPTURE;
    }

    public synchronized boolean isPassThrough() {
        return state == State.PASS_THROUGH;
    }

    public void addTerminationListener(Consumer<Throwable> listener) {
        Throwable existingFailure;
        synchronized (this) {
            terminationListeners.add(Objects.requireNonNull(listener));
            existingFailure = state == State.TERMINATING ? captureFailure : null;
        }
        if (existingFailure != null) {
            listener.accept(existingFailure);
        }
    }

    /**
     * Applies the configured process policy exactly once.
     *
     * @return the resulting process state
     */
    public State requiredCaptureFailed(Throwable failure) {
        List<Consumer<Throwable>> listeners = List.of();
        State resultingState;
        synchronized (this) {
            Objects.requireNonNull(failure);
            if (state != State.CAPTURE) {
                return state;
            }
            captureFailure = failure;
            if (failurePolicy == CaptureFailurePolicy.FAIL_OPEN) {
                state = State.PASS_THROUGH;
            } else {
                state = State.TERMINATING;
                listeners = List.copyOf(terminationListeners);
            }
            resultingState = state;
        }

        if (resultingState == State.PASS_THROUGH) {
            log.atError()
                .setCause(failure)
                .setMessage(
                    "Required capture failed; this process has irreversibly entered pass-through mode"
                )
                .log();
        } else {
            log.atError()
                .setCause(failure)
                .setMessage("Required capture failed; this process will terminate")
                .log();
            listeners.forEach(listener -> listener.accept(failure));
        }
        return resultingState;
    }
}
