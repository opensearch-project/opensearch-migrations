/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.replay.kafkasource.assignor.AssignorControl;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** How the source port carries out the quiet-window assignor's rebalance deadlines. */
class KafkaConsumerSourcePortRebalanceDeadlineTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);

    private static final class RecordingConsumer extends MockConsumer<String, byte[]> {
        final List<Duration> pollTimeouts = new ArrayList<>();
        final List<String> rebalanceReasons = new ArrayList<>();

        RecordingConsumer() {
            super("earliest");
        }

        @Override
        public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
            pollTimeouts.add(timeout);
            return ConsumerRecords.empty();
        }

        @Override
        public void enforceRebalance(String reason) {
            rebalanceReasons.add(reason);
        }
    }

    private final AtomicLong nowMillis = new AtomicLong(10_000);
    private final AssignorControl control = new AssignorControl(nowMillis::get);
    private final RecordingConsumer consumer = new RecordingConsumer();
    private final KafkaConsumerSourcePort port = new KafkaConsumerSourcePort(consumer, POLL_TIMEOUT, control);

    @Test
    void unarmedControlLeavesPollingUntouched() {
        port.poll(Duration.ofSeconds(5));

        Assertions.assertEquals(List.of(POLL_TIMEOUT), consumer.pollTimeouts);
        Assertions.assertTrue(consumer.rebalanceReasons.isEmpty());
    }

    @Test
    void pollWaitIsCappedAtTheTimeRemainingBeforeTheDeadline() {
        control.arm(nowMillis.get() + 250);

        port.poll(Duration.ofSeconds(5));

        Assertions.assertEquals(List.of(Duration.ofMillis(250)), consumer.pollTimeouts);
        Assertions.assertTrue(consumer.rebalanceReasons.isEmpty(), "not due yet");
    }

    @Test
    void dueDeadlineRequestsExactlyOneRebalanceThenPollsNormally() {
        control.arm(nowMillis.get() + 250);
        nowMillis.addAndGet(250);

        port.poll(Duration.ofSeconds(5));
        port.poll(Duration.ofSeconds(5));

        Assertions.assertEquals(List.of("quiet-window deadline"), consumer.rebalanceReasons);
        Assertions.assertEquals(List.of(POLL_TIMEOUT, POLL_TIMEOUT), consumer.pollTimeouts);
        Assertions.assertTrue(control.peekDeadline().isEmpty(), "a deadline is delivered at most once");
    }

    @Test
    void callerWaitShorterThanTheDeadlineStillWins() {
        control.arm(nowMillis.get() + 900);

        port.poll(Duration.ofMillis(100));

        Assertions.assertEquals(List.of(Duration.ofMillis(100)), consumer.pollTimeouts);
    }
}
