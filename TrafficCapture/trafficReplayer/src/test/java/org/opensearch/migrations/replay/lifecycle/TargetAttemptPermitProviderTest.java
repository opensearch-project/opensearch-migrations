/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetAttemptPermitProviderTest {
    @Test
    void capacityOneGrantsOnlyOneAttemptAndReleaseWakesAPendingAcquisition() {
        var fixture = new Fixture(1);

        var first = acquired(fixture.provider.acquire(request(0)));
        var second = fixture.provider.acquire(request(1));

        Assertions.assertEquals(1, fixture.activeTargetAttempts.get());
        Assertions.assertFalse(second.completion().toCompletableFuture().isDone());

        first.close();

        Assertions.assertTrue(second.completion().toCompletableFuture().isDone());
        var secondPermit = acquired(second);
        Assertions.assertEquals(request(1), secondPermit.requestId());
        Assertions.assertEquals(1, fixture.activeTargetAttempts.get());
        secondPermit.close();
        Assertions.assertEquals(0, fixture.activeTargetAttempts.get());
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void cancellingAPendingAcquisitionConsumesNoPermitAndReturnsTypedCancellation() {
        var fixture = new Fixture(1);
        var active = acquired(fixture.provider.acquire(request(0)));
        var pending = fixture.provider.acquire(request(1));
        var cause = new CancellationException("generation cancelled");

        Assertions.assertTrue(pending.cancel(cause));
        var cancelled = Assertions.assertInstanceOf(
            TargetAttemptPermitProvider.AcquisitionCancelled.class,
            pending.completion().toCompletableFuture().join()
        );
        Assertions.assertSame(cause, cancelled.cause());
        Assertions.assertEquals(1, fixture.activeTargetAttempts.get());
        Assertions.assertEquals(0, fixture.metrics.pending);
        Assertions.assertEquals(1, fixture.metrics.cancellations);

        active.close();
        var replacement = acquired(fixture.provider.acquire(request(2)));
        Assertions.assertEquals(1, fixture.activeTargetAttempts.get());
        replacement.close();
        Assertions.assertEquals(0, fixture.activeTargetAttempts.get());
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void invalidCounterFailsAnAcquisitionInsteadOfLeavingItUnsettled() {
        var fixture = new Fixture(1);
        fixture.activeTargetAttempts.set(-1);

        var acquisition = fixture.provider.acquire(request(0));

        Assertions.assertTrue(acquisition.completion().toCompletableFuture().isDone());
        Assertions.assertThrows(
            CompletionException.class,
            () -> acquisition.completion().toCompletableFuture().join()
        );
        Assertions.assertEquals(-1, fixture.activeTargetAttempts.get());
        Assertions.assertEquals(1, fixture.fatalFailures.size());
        Assertions.assertEquals(1, fixture.metrics.acquisitionFailures);
    }

    @Test
    void invalidCounterStopsDrainAndFailsEveryPendingAcquisition() {
        var fixture = new Fixture(1);
        var active = acquired(fixture.provider.acquire(request(0)));
        var firstPending = fixture.provider.acquire(request(1));
        var secondPending = fixture.provider.acquire(request(2));
        fixture.activeTargetAttempts.set(-1);

        active.close();

        Assertions.assertTrue(firstPending.completion().toCompletableFuture().isDone());
        Assertions.assertTrue(secondPending.completion().toCompletableFuture().isDone());
        Assertions.assertThrows(
            CompletionException.class,
            () -> firstPending.completion().toCompletableFuture().join()
        );
        Assertions.assertThrows(
            CompletionException.class,
            () -> secondPending.completion().toCompletableFuture().join()
        );
        Assertions.assertEquals(-1, fixture.activeTargetAttempts.get());
        Assertions.assertEquals(0, fixture.metrics.pending);
        Assertions.assertEquals(1, fixture.fatalFailures.size());
        Assertions.assertEquals(2, fixture.metrics.acquisitionFailures);
    }

    @Test
    void invalidReservationDuringDrainFailsTheCurrentAndRemainingAcquisitions() {
        var metrics = new FailingPermitAcquiredMetrics(2);
        var counter = new AtomicInteger();
        var failures = new ArrayList<Error>();
        var provider = new TargetAttemptPermitProvider(
            1,
            counter,
            metrics,
            failures::add
        );
        var active = acquired(provider.acquire(request(0)));
        var firstPending = provider.acquire(request(1));
        var secondPending = provider.acquire(request(2));

        active.close();

        var firstCompletion = firstPending.completion().toCompletableFuture();
        var secondCompletion = secondPending.completion().toCompletableFuture();
        Assertions.assertTrue(firstCompletion.isDone());
        Assertions.assertTrue(secondCompletion.isDone());
        var failedAcquisition = firstCompletion.isCompletedExceptionally()
            ? firstPending
            : secondPending;
        var grantedAcquisition = firstCompletion.isCompletedExceptionally()
            ? secondPending
            : firstPending;
        var pendingPermit = acquired(grantedAcquisition);
        Assertions.assertTrue(secondPending.completion().toCompletableFuture().isDone());
        Assertions.assertThrows(
            CompletionException.class,
            () -> failedAcquisition.completion().toCompletableFuture().join()
        );
        Assertions.assertEquals(0, metrics.pending);
        Assertions.assertEquals(1, metrics.acquisitionFailures);
        Assertions.assertEquals(1, failures.size());

        pendingPermit.close();
        Assertions.assertEquals(0, counter.get());
    }

    @Test
    void everyDuplicateReleaseReachesFatalHandlingWithoutCorruptingTheCounter() {
        var fixture = new Fixture(1);
        var permit = acquired(fixture.provider.acquire(request(0)));

        permit.close();
        permit.close();
        permit.close();

        Assertions.assertEquals(0, fixture.activeTargetAttempts.get());
        Assertions.assertEquals(2, fixture.fatalFailures.size());
        Assertions.assertTrue(
            fixture.fatalFailures.get(0).getCause().getMessage().contains("more than once")
        );
        Assertions.assertTrue(
            fixture.fatalFailures.get(1).getCause().getMessage().contains("more than once")
        );
    }

    @Test
    void impossibleReleaseReachesFatalHandlingBeforeChangingTheCounter() {
        var fixture = new Fixture(1);
        var permit = acquired(fixture.provider.acquire(request(0)));
        fixture.activeTargetAttempts.set(0);

        permit.close();

        Assertions.assertEquals(0, fixture.activeTargetAttempts.get());
        Assertions.assertEquals(1, fixture.fatalFailures.size());
        Assertions.assertTrue(
            fixture.fatalFailures.get(0).getCause().getMessage().contains(
                "cannot release permit"
            )
        );
    }

    @Test
    void constructionRejectsAnAlreadyCorruptApplicationCounter() {
        var counter = new AtomicInteger(2);
        var failures = new ArrayList<Error>();

        var thrown = Assertions.assertThrows(
            IllegalStateException.class,
            () -> new TargetAttemptPermitProvider(
                1,
                counter,
                TargetAttemptPermitProvider.Metrics.NOOP,
                failures::add
            )
        );

        Assertions.assertEquals(2, counter.get());
        Assertions.assertEquals(1, failures.size());
        Assertions.assertSame(thrown, failures.get(0).getCause());
    }

    @Test
    void heldDurationAndPermitConservationAreObserved() {
        var metrics = new RecordingMetrics();
        var nanoTime = new AtomicLong(1_000_000);
        var counter = new AtomicInteger();
        var failures = new ArrayList<Error>();
        var provider = new TargetAttemptPermitProvider(
            1,
            counter,
            metrics,
            failures::add,
            nanoTime::get
        );
        var permit = acquired(provider.acquire(request(0)));

        nanoTime.set(6_000_000);
        permit.close();

        Assertions.assertEquals(1, metrics.acquisitions);
        Assertions.assertEquals(0, metrics.acquisitionFailures);
        Assertions.assertEquals(1, metrics.permitsAcquired);
        Assertions.assertEquals(1, metrics.permitsReleased);
        Assertions.assertEquals(0, metrics.activePermits);
        Assertions.assertEquals(List.of(Duration.ofMillis(5)), metrics.heldDurations);
        Assertions.assertEquals(0, counter.get());
        Assertions.assertTrue(failures.isEmpty());
    }

    private static TargetAttemptPermitProvider.Permit acquired(
        TargetAttemptPermitProvider.Acquisition acquisition
    ) {
        var completion = acquisition.completion().toCompletableFuture();
        Assertions.assertTrue(completion.isDone(), "expected synchronous permit acquisition");
        return Assertions.assertInstanceOf(
            TargetAttemptPermitProvider.PermitAcquired.class,
            completion.join()
        ).permit();
    }

    private static ReplayRequestId request(long ordinal) {
        return new ReplayRequestId(
            new ConnectionProcessingId(
                new PartitionGenerationId(new TopicPartition("topic", 0), 1),
                new CapturedConnectionId("node", "connection"),
                1
            ),
            ordinal
        );
    }

    private static final class Fixture {
        private final AtomicInteger activeTargetAttempts = new AtomicInteger();
        private final ArrayList<Error> fatalFailures = new ArrayList<>();
        private final RecordingMetrics metrics = new RecordingMetrics();
        private final TargetAttemptPermitProvider provider;

        private Fixture(int capacity) {
            provider = new TargetAttemptPermitProvider(
                capacity,
                activeTargetAttempts,
                metrics,
                fatalFailures::add
            );
        }
    }

    private static class RecordingMetrics
        implements TargetAttemptPermitProvider.Metrics {

        int acquisitions;
        int pending;
        int cancellations;
        int acquisitionFailures;
        int permitsAcquired;
        int activePermits;
        int permitsReleased;
        final ArrayList<Duration> heldDurations = new ArrayList<>();

        @Override
        public void acquisitionRequested() {
            acquisitions++;
        }

        @Override
        public void acquisitionPendingChanged(int delta) {
            pending += delta;
        }

        @Override
        public void acquisitionCancelled() {
            cancellations++;
        }

        @Override
        public void acquisitionFailed() {
            acquisitionFailures++;
        }

        @Override
        public void permitAcquired() {
            permitsAcquired++;
        }

        @Override
        public void activePermitsChanged(int delta) {
            activePermits += delta;
        }

        @Override
        public void permitReleased() {
            permitsReleased++;
        }

        @Override
        public void permitHeld(Duration duration) {
            heldDurations.add(duration);
        }
    }

    private static final class FailingPermitAcquiredMetrics extends RecordingMetrics {
        private final int failureInvocation;
        private int invocations;

        private FailingPermitAcquiredMetrics(int failureInvocation) {
            this.failureInvocation = failureInvocation;
        }

        @Override
        public void permitAcquired() {
            super.permitAcquired();
            invocations++;
            if (invocations == failureInvocation) {
                throw new IllegalStateException("permit-acquired metric failure");
            }
        }
    }
}
