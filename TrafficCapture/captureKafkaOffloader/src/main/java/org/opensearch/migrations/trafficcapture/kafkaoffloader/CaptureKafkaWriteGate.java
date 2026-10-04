package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import lombok.NonNull;

/**
 * Establishes a single process-wide ordering between accepted Kafka submissions and terminal
 * capture failure. Once tripped, the first failure becomes canonical and every later submission is
 * rejected with that same cause; a submission admitted earlier remains part of the publisher's
 * in-flight work rather than being silently forgotten.
 *
 * <p>Failure listeners observe the irreversible transition exactly once, including listeners
 * registered after it happened. This keeps independent capture components from making conflicting
 * decisions about whether the process may still claim authoritative capture.
 */
final class CaptureKafkaWriteGate {
    private Throwable terminalFailure;
    private final List<Consumer<Throwable>> terminalFailureListeners = new ArrayList<>();

    static CaptureKafkaWriteGate unrestricted() {
        return new CaptureKafkaWriteGate();
    }

    void addTerminalFailureListener(@NonNull Consumer<Throwable> listener) {
        Throwable existingFailure;
        synchronized (this) {
            terminalFailureListeners.add(listener);
            existingFailure = terminalFailure;
        }
        if (existingFailure != null) {
            listener.accept(existingFailure);
        }
    }

    /** @return the terminal cause when submission was rejected, otherwise {@code null} */
    Throwable submitIfWritable(Runnable submission) {
        synchronized (this) {
            if (terminalFailure != null) {
                return terminalFailure;
            }
        }
        submission.run();
        return null;
    }

    Throwable failureIfNotWritable() {
        return submitIfWritable(() -> {});
    }

    Throwable trip(@NonNull Throwable failure) {
        List<Consumer<Throwable>> listeners = List.of();
        Throwable canonicalFailure;
        synchronized (this) {
            if (terminalFailure == null) {
                terminalFailure = failure;
                listeners = List.copyOf(terminalFailureListeners);
            }
            canonicalFailure = terminalFailure;
        }
        notifyListeners(listeners, canonicalFailure);
        return canonicalFailure;
    }

    private static void notifyListeners(
        List<Consumer<Throwable>> listeners,
        Throwable failure
    ) {
        listeners.forEach(listener -> listener.accept(failure));
    }
}
