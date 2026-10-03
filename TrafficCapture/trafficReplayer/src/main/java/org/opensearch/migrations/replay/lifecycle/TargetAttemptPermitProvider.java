/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.identity.ReplayRequestId;

import lombok.NonNull;

/**
 * Enforces the application-wide limit on target attempts without blocking a connection event loop.
 *
 * <p>The composition root owns the shared active-attempt counter and supplies this provider to every
 * connection owner. Pending acquisitions are provider-owned until they either produce a permit or are
 * cancelled. Queue order is deliberately not part of the contract.</p>
 */
public final class TargetAttemptPermitProvider {
    @FunctionalInterface
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    public interface Metrics {
        Metrics NOOP = new Metrics() {
            @Override
            public void acquisitionRequested() {}

            @Override
            public void acquisitionPendingChanged(int delta) {}

            @Override
            public void acquisitionCancelled() {}

            @Override
            public void acquisitionFailed() {}

            @Override
            public void permitAcquired() {}

            @Override
            public void activePermitsChanged(int delta) {}

            @Override
            public void permitReleased() {}

            @Override
            public void permitHeld(Duration duration) {}
        };

        void acquisitionRequested();

        void acquisitionPendingChanged(int delta);

        void acquisitionCancelled();

        void acquisitionFailed();

        void permitAcquired();

        void activePermitsChanged(int delta);

        void permitReleased();

        void permitHeld(Duration duration);
    }

    public sealed interface AcquisitionResult permits PermitAcquired, AcquisitionCancelled {}

    public record PermitAcquired(@NonNull Permit permit) implements AcquisitionResult {}

    public record AcquisitionCancelled(
        @NonNull CancellationException cause
    ) implements AcquisitionResult {}

    public interface Acquisition {
        ReplayRequestId requestId();

        /**
         * Completes on whichever thread grants or cancels the acquisition.
         *
         * <p>The receiving connection owner must post the typed result to its assigned event loop.
         * If that required post is rejected, it must release an acquired permit before reporting the
         * rejection to the fatal boundary.</p>
         */
        CompletionStage<AcquisitionResult> completion();

        boolean cancel(CancellationException cause);
    }

    public interface Permit extends AutoCloseable {
        ReplayRequestId requestId();

        @Override
        void close();
    }

    private enum AcquisitionState {
        WAITING,
        ACQUIRED,
        CANCELLED,
        FAILED
    }

    private enum Reservation {
        RESERVED,
        FULL,
        INVALID
    }

    private final int capacity;
    private final AtomicInteger activeTargetAttempts;
    private final Metrics metrics;
    private final FatalHandler fatalHandler;
    private final LongSupplier nanoTime;
    private final ConcurrentLinkedQueue<PendingAcquisition> pendingAcquisitions =
        new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicReference<Error> fatalFailure = new AtomicReference<>();

    public TargetAttemptPermitProvider(
        int capacity,
        @NonNull AtomicInteger activeTargetAttempts,
        @NonNull Metrics metrics,
        @NonNull FatalHandler fatalHandler
    ) {
        this(capacity, activeTargetAttempts, metrics, fatalHandler, System::nanoTime);
    }

    public TargetAttemptPermitProvider(
        int capacity,
        @NonNull AtomicInteger activeTargetAttempts,
        @NonNull Metrics metrics,
        @NonNull FatalHandler fatalHandler,
        @NonNull LongSupplier nanoTime
    ) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.activeTargetAttempts = activeTargetAttempts;
        this.metrics = metrics;
        this.fatalHandler = fatalHandler;
        this.nanoTime = nanoTime;

        var initialCount = activeTargetAttempts.get();
        if (initialCount != 0) {
            var failure = new IllegalStateException(
                "application target-attempt counter must start at zero but was " + initialCount
            );
            reportFatal("invalid application target-attempt counter at construction", failure);
            throw failure;
        }
    }

    public Acquisition acquire(@NonNull ReplayRequestId requestId) {
        var acquisition = new PendingAcquisition(requestId);
        recordMetric(metrics::acquisitionRequested, "recording a requested target-attempt permit");
        var existingFatalFailure = fatalFailure.get();
        if (existingFatalFailure != null) {
            acquisition.fail(existingFatalFailure);
            return acquisition;
        }

        var reservation = reserveAttempt();
        if (reservation == Reservation.RESERVED) {
            grantReservedAttempt(acquisition);
        } else if (reservation == Reservation.FULL) {
            acquisition.markPending();
            pendingAcquisitions.add(acquisition);
            drainPendingAcquisitions();
        } else {
            acquisition.fail(requireFatalFailure());
        }
        return acquisition;
    }

    private Reservation reserveAttempt() {
        while (true) {
            if (fatalFailure.get() != null) {
                return Reservation.INVALID;
            }
            var active = activeTargetAttempts.get();
            if (active < 0 || active > capacity) {
                reportInvariantFailure(
                    "active target-attempt count " + active + " is outside [0, " + capacity + "]"
                );
                return Reservation.INVALID;
            }
            if (active == capacity) {
                return Reservation.FULL;
            }
            if (activeTargetAttempts.compareAndSet(active, active + 1)) {
                return Reservation.RESERVED;
            }
        }
    }

    private void grantReservedAttempt(PendingAcquisition acquisition) {
        if (!acquisition.state.compareAndSet(AcquisitionState.WAITING, AcquisitionState.ACQUIRED)) {
            releaseUnusedReservation();
            return;
        }

        acquisition.clearPending();
        var permit = new OwnedPermit(acquisition.requestId, nanoTime.getAsLong());
        recordMetric(metrics::permitAcquired, "recording an acquired target-attempt permit");
        recordMetric(
            () -> metrics.activePermitsChanged(1),
            "recording an active target-attempt permit"
        );
        if (!acquisition.completion.complete(new PermitAcquired(permit))) {
            reportInvariantFailure(
                "acquisition completed more than once for " + acquisition.requestId
            );
            permit.releaseAfterFailedDelivery();
        }
    }

    private void drainPendingAcquisitions() {
        if (fatalFailure.get() != null) {
            failPendingAcquisitions(requireFatalFailure());
            return;
        }
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        while (true) {
            drainWhileCapacityIsAvailable();
            draining.set(false);
            if (fatalFailure.get() != null) {
                failPendingAcquisitions(requireFatalFailure());
                return;
            }
            if (pendingAcquisitions.isEmpty()
                || activeTargetAttempts.get() >= capacity
                || !draining.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private void drainWhileCapacityIsAvailable() {
        while (true) {
            var acquisition = pendingAcquisitions.poll();
            if (acquisition == null) {
                return;
            }
            if (acquisition.state.get() != AcquisitionState.WAITING) {
                acquisition.clearPending();
                continue;
            }

            var reservation = reserveAttempt();
            if (reservation == Reservation.FULL) {
                pendingAcquisitions.add(acquisition);
                return;
            }
            if (reservation == Reservation.INVALID) {
                acquisition.fail(requireFatalFailure());
                failPendingAcquisitions(requireFatalFailure());
                return;
            }
            grantReservedAttempt(acquisition);
        }
    }

    private void failPendingAcquisitions(Error failure) {
        PendingAcquisition acquisition;
        while ((acquisition = pendingAcquisitions.poll()) != null) {
            acquisition.fail(failure);
        }
    }

    private void releaseUnusedReservation() {
        while (true) {
            var active = activeTargetAttempts.get();
            if (active <= 0 || active > capacity) {
                reportInvariantFailure(
                    "cannot release an undelivered reservation from active count " + active
                );
                failPendingAcquisitions(requireFatalFailure());
                return;
            }
            if (activeTargetAttempts.compareAndSet(active, active - 1)) {
                return;
            }
        }
    }

    private void releasePermit(OwnedPermit permit) {
        while (true) {
            var active = activeTargetAttempts.get();
            if (active <= 0 || active > capacity) {
                reportInvariantFailure(
                    "cannot release permit for "
                        + permit.requestId
                        + " from active count "
                        + active
                );
                failPendingAcquisitions(requireFatalFailure());
                return;
            }
            if (activeTargetAttempts.compareAndSet(active, active - 1)) {
                break;
            }
        }

        recordMetric(
            () -> metrics.activePermitsChanged(-1),
            "recording a released active target-attempt permit"
        );
        recordMetric(metrics::permitReleased, "recording a released target-attempt permit");
        recordMetric(
            () -> metrics.permitHeld(
                Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - permit.acquiredNanos))
            ),
            "recording target-attempt permit held duration"
        );
        drainPendingAcquisitions();
    }

    private void recordMetric(Runnable recorder, String operation) {
        try {
            recorder.run();
        } catch (Throwable failure) {
            reportFatal("failed while " + operation, failure);
        }
    }

    private Error reportInvariantFailure(String message) {
        return reportFatal(
            "target-attempt permit invariant failed",
            new IllegalStateException(message)
        );
    }

    private Error reportFatal(String message, Throwable cause) {
        var proposed = new Error(message, cause);
        fatalFailure.compareAndSet(null, proposed);
        fatalHandler.onFatal(proposed);
        return fatalFailure.get();
    }

    private Error requireFatalFailure() {
        var failure = fatalFailure.get();
        if (failure != null) {
            return failure;
        }
        return reportFatal(
            "target-attempt permit provider entered an invalid state",
            new IllegalStateException("invalid reservation without a recorded fatal failure")
        );
    }

    private final class PendingAcquisition implements Acquisition {
        private final ReplayRequestId requestId;
        private final CompletableFuture<AcquisitionResult> completion = new CompletableFuture<>();
        private final CompletionStage<AcquisitionResult> readOnlyCompletion =
            completion.minimalCompletionStage();
        private final AtomicReference<AcquisitionState> state =
            new AtomicReference<>(AcquisitionState.WAITING);
        private final AtomicBoolean countedPending = new AtomicBoolean();

        private PendingAcquisition(ReplayRequestId requestId) {
            this.requestId = Objects.requireNonNull(requestId);
        }

        @Override
        public ReplayRequestId requestId() {
            return requestId;
        }

        @Override
        public CompletionStage<AcquisitionResult> completion() {
            return readOnlyCompletion;
        }

        @Override
        public boolean cancel(@NonNull CancellationException cause) {
            if (!state.compareAndSet(AcquisitionState.WAITING, AcquisitionState.CANCELLED)) {
                return false;
            }
            pendingAcquisitions.remove(this);
            clearPending();
            recordMetric(
                metrics::acquisitionCancelled,
                "recording a cancelled target-attempt permit acquisition"
            );
            if (!completion.complete(new AcquisitionCancelled(cause))) {
                reportInvariantFailure(
                    "cancelled acquisition completed more than once for " + requestId
                );
            }
            return true;
        }

        private void markPending() {
            if (countedPending.compareAndSet(false, true)) {
                recordMetric(
                    () -> metrics.acquisitionPendingChanged(1),
                    "recording a pending target-attempt permit acquisition"
                );
            }
        }

        private void clearPending() {
            if (countedPending.compareAndSet(true, false)) {
                recordMetric(
                    () -> metrics.acquisitionPendingChanged(-1),
                    "recording a settled target-attempt permit acquisition"
                );
            }
        }

        private void fail(Error failure) {
            if (!state.compareAndSet(AcquisitionState.WAITING, AcquisitionState.FAILED)) {
                return;
            }
            pendingAcquisitions.remove(this);
            clearPending();
            recordMetric(
                metrics::acquisitionFailed,
                "recording a failed target-attempt permit acquisition"
            );
            completion.completeExceptionally(failure);
        }
    }

    private final class OwnedPermit implements Permit {
        private final ReplayRequestId requestId;
        private final long acquiredNanos;
        private final AtomicBoolean released = new AtomicBoolean();

        private OwnedPermit(ReplayRequestId requestId, long acquiredNanos) {
            this.requestId = requestId;
            this.acquiredNanos = acquiredNanos;
        }

        @Override
        public ReplayRequestId requestId() {
            return requestId;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                reportInvariantFailure(
                    "permit released more than once for " + requestId
                );
                return;
            }
            releasePermit(this);
        }

        private void releaseAfterFailedDelivery() {
            if (released.compareAndSet(false, true)) {
                releasePermit(this);
            }
        }
    }
}
