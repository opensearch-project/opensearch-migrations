/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.WaitReason;
import org.opensearch.migrations.replay.testing.FakeClock;
import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.CONNECTION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.GENERATION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.request;

class OutstandingOperationRegistryTest {
    @Test
    void snapshotsRemainImmutableAndTrackWaitReasonAndCompletion() {
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var fatalFailures = new ArrayList<Error>();
        var registry = new OutstandingOperationRegistry(
            "test owner",
            eventLoop,
            clock,
            fatalFailures::add
        );
        var registration = new AtomicReference<OutstandingOperationRegistry.Registration>();

        eventLoop.execute(() -> registration.set(registry.register(
            GENERATION,
            CONNECTION,
            request(0),
            OperationType.TARGET_ATTEMPT,
            Instant.EPOCH.plusSeconds(5),
            WaitReason.SUBMITTING
        )));
        eventLoop.runUntilIdle();

        var snapshot = registry.snapshots();
        Assertions.assertEquals(1, snapshot.size());
        Assertions.assertEquals(OperationType.TARGET_ATTEMPT, snapshot.get(0).operationType());
        Assertions.assertEquals(WaitReason.SUBMITTING, snapshot.get(0).waitReason());
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.add(snapshot.get(0))
        );

        eventLoop.execute(() -> registry.updateWaitReason(
            registration.get(),
            WaitReason.WAITING_FOR_TARGET
        ));
        eventLoop.runUntilIdle();
        Assertions.assertEquals(
            WaitReason.WAITING_FOR_TARGET,
            registry.snapshots().get(0).waitReason()
        );

        eventLoop.execute(() -> registry.complete(registration.get()));
        eventLoop.runUntilIdle();
        Assertions.assertTrue(registry.snapshots().isEmpty());
        Assertions.assertEquals(0, registry.activeCount());
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void aLargeRegistryRebuildsItsSnapshotOnlyWhenRead() {
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var fatalFailures = new ArrayList<Error>();
        var registry = new OutstandingOperationRegistry("test owner", eventLoop, clock, fatalFailures::add);
        var registrations = new ArrayList<OutstandingOperationRegistry.Registration>();
        var operations = OutstandingOperationRegistry.EAGER_PUBLISH_LIMIT + 10;

        eventLoop.execute(() -> {
            for (var i = 0; i < operations; i++) {
                registrations.add(registry.register(
                    GENERATION,
                    CONNECTION,
                    request(i),
                    OperationType.PHYSICAL_TUPLE_WRITE,
                    Instant.EPOCH,
                    WaitReason.SUBMITTING
                ));
            }
        });
        eventLoop.runUntilIdle();

        Assertions.assertEquals(operations, registry.activeCount());
        Assertions.assertEquals(
            OutstandingOperationRegistry.EAGER_PUBLISH_LIMIT,
            registry.snapshots().size(),
            "a read from another thread returns the last published snapshot"
        );
        eventLoop.runUntilIdle();
        Assertions.assertEquals(operations, registry.snapshots().size(), "the read scheduled a rebuild");

        var readOnLoop = new AtomicReference<Integer>();
        eventLoop.execute(() -> {
            registry.complete(registrations.get(0));
            readOnLoop.set(registry.snapshots().size());
        });
        eventLoop.runUntilIdle();
        Assertions.assertEquals(operations - 1, readOnLoop.get(), "a read on the event loop is current");
        Assertions.assertEquals(operations - 1, registry.activeCount());

        eventLoop.execute(() -> registrations.subList(1, operations).forEach(registry::complete));
        eventLoop.runUntilIdle();
        Assertions.assertTrue(registry.snapshots().isEmpty(), "a small registry republishes eagerly again");
        Assertions.assertEquals(0, registry.activeCount());
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void duplicateOrMissingCompletionReachesFatalHandling() {
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var fatalFailures = new ArrayList<Error>();
        var registry = new OutstandingOperationRegistry(
            "test owner",
            eventLoop,
            clock,
            fatalFailures::add
        );
        var registration = new AtomicReference<OutstandingOperationRegistry.Registration>();
        eventLoop.execute(() -> {
            registration.set(registry.register(
                GENERATION,
                CONNECTION,
                request(1),
                OperationType.REQUIRED_DELIVERY,
                null,
                WaitReason.WAITING_FOR_RECEIVER
            ));
            registry.complete(registration.get());
            registry.complete(registration.get());
        });
        eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(
            fatalFailures.get(0).getCause().getMessage().contains(
                "missing or duplicate completion"
            )
        );
    }
}
