package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.atomic.AtomicReference;

import lombok.NonNull;

public final class ResourceOwnership {
    private ResourceOwnership() {}

    public enum Type {
        PREPARED_REQUEST("prepared_request"),
        ATTEMPT_PAYLOAD("attempt_payload"),
        DIAGNOSTIC_PAYLOAD("diagnostic_payload");

        private final String metricLabel;

        Type(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public interface Metrics {
        Metrics NOOP = new Metrics() {};

        default void ownershipChanged(Type type, int handleDelta, int bufferDelta, long byteDelta) {}

        default void duplicateClose(Type type) {}

        default void invariantFailure(Type type) {}
    }

    public static final class Tracker {
        private enum State {
            OPEN,
            CLOSING,
            CLOSED
        }

        private final Metrics metrics;
        private final Type type;
        private final int buffers;
        private final long bytes;
        private final boolean ownershipRecorded;
        private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

        public Tracker(
            @NonNull Metrics metrics,
            @NonNull Type type,
            int buffers,
            long bytes
        ) {
            if (buffers < 0 || bytes < 0) {
                throw new IllegalArgumentException("owned resource size cannot be negative");
            }
            this.metrics = metrics;
            this.type = type;
            this.buffers = buffers;
            this.bytes = bytes;
            ownershipRecorded = reportMetric(
                () -> metrics.ownershipChanged(type, 1, buffers, bytes)
            );
        }

        public boolean close(@NonNull Runnable releaser) {
            if (!state.compareAndSet(State.OPEN, State.CLOSING)) {
                reportMetric(() -> metrics.duplicateClose(type));
                return false;
            }
            try {
                releaser.run();
            } catch (Throwable t) {
                state.set(State.OPEN);
                reportMetric(() -> metrics.invariantFailure(type));
                throw t;
            }
            state.set(State.CLOSED);
            if (ownershipRecorded) {
                reportMetric(() -> metrics.ownershipChanged(type, -1, -buffers, -bytes));
            }
            return true;
        }

        public void invariantFailure() {
            reportMetric(() -> metrics.invariantFailure(type));
        }

        private static boolean reportMetric(Runnable callback) {
            try {
                callback.run();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
