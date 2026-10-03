package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import lombok.NonNull;

/**
 * Process-lifetime gate that linearizes admission to the Kafka publisher against a terminal
 * publisher failure.
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
