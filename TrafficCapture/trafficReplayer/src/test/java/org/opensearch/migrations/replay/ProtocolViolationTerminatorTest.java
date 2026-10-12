/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.testing.TestLogAppender;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProtocolViolationTerminatorTest {
    private static final KafkaRecordId POISON = new KafkaRecordId(
        new PartitionGenerationId(new TopicPartition("traffic", 1), 2),
        41
    );

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("ProtocolViolationTerminatorLogger")
    void protocolViolationGetsOneFixedDrainThenTerminatesWithItsOwnCode() {
        var scheduledDelay = new AtomicReference<Duration>();
        var scheduledTask = new AtomicReference<Runnable>();
        var exitCodes = new ArrayList<Integer>();
        var drainsStarted = new AtomicInteger();
        var terminationsTriggered = new AtomicInteger();
        var metrics = new ProtocolViolationTerminator.Metrics() {
            @Override
            public void drainStarted() {
                drainsStarted.incrementAndGet();
            }

            @Override
            public void terminationTriggered() {
                terminationsTriggered.incrementAndGet();
            }
        };
        try (var logs = new TestLogAppender(ProtocolViolationTerminator.class)) {
            var terminator = new ProtocolViolationTerminator(
                (delay, task) -> {
                    scheduledDelay.set(delay);
                    scheduledTask.set(task);
                },
                exitCodes::add,
                metrics
            );

            terminator.begin(POISON, "missing payload");
            terminator.begin(
                new KafkaRecordId(POISON.generation(), POISON.offset() + 1),
                "later diagnostic"
            );

            Assertions.assertEquals(Duration.ofSeconds(60), ProtocolViolationTerminator.DRAIN_LIMIT);
            Assertions.assertEquals(ProtocolViolationTerminator.DRAIN_LIMIT, scheduledDelay.get());
            Assertions.assertEquals(81, ProtocolViolationTerminator.EXIT_CODE);
            Assertions.assertTrue(exitCodes.isEmpty(), "termination cannot happen before the drain expires");
            Assertions.assertEquals(1, drainsStarted.get());
            Assertions.assertTrue(logs.contains(
                "capture_protocol_violation",
                "topic=traffic",
                "partition=1",
                "generation=2",
                "offset=41",
                "drain_limit_ms=60000",
                "diagnostic=missing payload"
            ));

            scheduledTask.get().run();

            Assertions.assertEquals(List.of(ProtocolViolationTerminator.EXIT_CODE), exitCodes);
            Assertions.assertEquals(1, terminationsTriggered.get());
        }
    }
}
