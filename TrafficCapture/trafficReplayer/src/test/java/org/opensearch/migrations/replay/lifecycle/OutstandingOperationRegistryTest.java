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
import java.util.List;
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
    void snapshotsAndCountHooksProjectTheSameRegisteredOperations() {
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var fatalFailures = new ArrayList<Error>();
        var counts = new ArrayList<String>();
        var registry = new OutstandingOperationRegistry(
            "test owner",
            eventLoop,
            clock,
            fatalFailures::add,
            (type, typeCount, totalCount) ->
                counts.add(type + ":" + typeCount + ":" + totalCount)
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
        Assertions.assertEquals(List.of(
            "TARGET_ATTEMPT:1:1",
            "TARGET_ATTEMPT:0:0"
        ), counts);
        Assertions.assertTrue(registry.snapshots().isEmpty());
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
            fatalFailures::add,
            OutstandingOperationRegistry.CountHook.NOOP
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
