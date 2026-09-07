package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Process-lifetime gate that linearizes Kafka submission against eviction and membership staleness.
 */
final class CaptureKafkaWriteGate {
    private static final long NO_SUCCESSFUL_POLL = Long.MIN_VALUE;

    private final long maximumStalenessNanos;
    private final LongSupplier nanoTime;
    private long lastSuccessfulPollNanos = NO_SUCCESSFUL_POLL;
    private Throwable terminalFailure;
    private final List<Consumer<Throwable>> terminalFailureListeners = new ArrayList<>();

    CaptureKafkaWriteGate(Duration maximumStaleness, LongSupplier nanoTime) {
        Objects.requireNonNull(maximumStaleness);
        if (maximumStaleness.isZero() || maximumStaleness.isNegative()) {
            throw new IllegalArgumentException("maximumStaleness must be positive");
        }
        maximumStalenessNanos = maximumStaleness.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    static CaptureKafkaWriteGate unrestricted() {
        var gate = new CaptureKafkaWriteGate(Duration.ofNanos(Long.MAX_VALUE), System::nanoTime);
        gate.recordSuccessfulPoll();
        return gate;
    }

    void addTerminalFailureListener(Consumer<Throwable> listener) {
        Throwable existingFailure;
        synchronized (this) {
            Objects.requireNonNull(listener);
            terminalFailureListeners.add(listener);
            existingFailure = terminalFailure;
        }
        if (existingFailure != null) {
            listener.accept(existingFailure);
        }
    }

    synchronized void recordSuccessfulPoll() {
        if (terminalFailure == null) {
            lastSuccessfulPollNanos = nanoTime.getAsLong();
        }
    }

    /**
     * Runs {@code submission} while holding the same lock used to trip the gate.
     *
     * @return the terminal cause when submission was rejected, otherwise {@code null}
     */
    Throwable submitIfWritable(Runnable submission) {
        List<Consumer<Throwable>> listeners = List.of();
        Throwable rejection;
        synchronized (this) {
            rejection = terminalFailure;
            if (rejection == null) {
                rejection = stalenessFailure();
                if (rejection != null) {
                    terminalFailure = rejection;
                    listeners = List.copyOf(terminalFailureListeners);
                } else {
                    submission.run();
                }
            }
        }
        notifyListeners(listeners, rejection);
        return rejection;
    }

    Throwable failureIfNotWritable() {
        return submitIfWritable(() -> {});
    }

    Throwable trip(Throwable failure) {
        List<Consumer<Throwable>> listeners = List.of();
        Throwable canonicalFailure;
        synchronized (this) {
            Objects.requireNonNull(failure);
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

    private Throwable stalenessFailure() {
        if (lastSuccessfulPollNanos == NO_SUCCESSFUL_POLL) {
            return new IllegalStateException("Kafka membership has not completed a successful poll");
        }
        long staleNanos = nanoTime.getAsLong() - lastSuccessfulPollNanos;
        if (staleNanos > maximumStalenessNanos) {
            return new IllegalStateException(
                "Kafka membership poll is stale by "
                    + Duration.ofNanos(staleNanos)
                    + "; maximum permitted staleness is "
                    + Duration.ofNanos(maximumStalenessNanos)
            );
        }
        return null;
    }
}
