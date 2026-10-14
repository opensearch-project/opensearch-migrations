package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.FailureReason;

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
    private final CapturePublishingMetrics metrics;
    private Throwable terminalFailure;
    private final List<Consumer<Throwable>> terminalFailureListeners = new ArrayList<>();

    CaptureKafkaWriteGate(@NonNull CapturePublishingMetrics metrics) {
        this.metrics = metrics;
    }

    static CaptureKafkaWriteGate unrestricted(@NonNull CapturePublishingMetrics metrics) {
        return new CaptureKafkaWriteGate(metrics);
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
                metrics.writeRejected();
                return terminalFailure;
            }
        }
        submission.run();
        return null;
    }

    Throwable failureIfNotWritable() {
        synchronized (this) {
            return terminalFailure;
        }
    }

    Throwable trip(@NonNull Throwable failure, @NonNull FailureReason reason) {
        List<Consumer<Throwable>> listeners = List.of();
        Throwable canonicalFailure;
        boolean transitioned = false;
        synchronized (this) {
            if (terminalFailure == null) {
                terminalFailure = failure;
                listeners = List.copyOf(terminalFailureListeners);
                transitioned = true;
            }
            canonicalFailure = terminalFailure;
        }
        if (transitioned) {
            metrics.writeGateTripped(reason);
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
