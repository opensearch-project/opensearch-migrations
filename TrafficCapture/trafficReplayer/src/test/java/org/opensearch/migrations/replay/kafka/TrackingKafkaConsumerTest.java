package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TrackingKafkaConsumer rebalance callbacks.
 */
@Slf4j
class TrackingKafkaConsumerTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    private MockConsumer<String, byte[]> buildMockConsumer() {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.assign(List.of(tp));
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));
        return mc;
    }

    private TrackingKafkaConsumer buildConsumer(MockConsumer<String, byte[]> mc) {
        return new TrackingKafkaConsumer(
            rootContext, mc, TOPIC, Duration.ofSeconds(30), java.time.Clock.systemUTC(), tsk -> {}
        );
    }

    // -------------------------------------------------------------------------
    // Phase 2: onPartitionsLost must NOT attempt commitSync
    // -------------------------------------------------------------------------

    /**
     * When onPartitionsLost fires (consumer timeout/fence), commits are not possible.
     * The implementation must skip safeCommit() entirely.
     * Before fix: onPartitionsLost delegates to onPartitionsRevoked which calls safeCommit(),
     *             which calls commitSync when there are pending commits — this will throw/fail
     *             when the consumer is fenced.
     * After fix: onPartitionsLost skips the commit and goes straight to cleanup.
     */
    @Test
    void onPartitionsLost_doesNotAttemptCommit() {
        var commitAttempted = new AtomicBoolean(false);
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public synchronized void commitSync(java.util.Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets) {
                commitAttempted.set(true);
                // Simulate a fenced consumer — commit is rejected
                throw new org.apache.kafka.common.errors.FencedInstanceIdException("fenced");
            }
        };
        var tp = new TopicPartition(TOPIC, 0);
        mc.assign(List.of(tp));
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        var consumer = buildConsumer(mc);
        consumer.onPartitionsAssigned(List.of(tp));

        // Simulate pending commits so safeCommit() would actually call commitSync
        consumer.nextSetOfCommitsMap.put(tp, new org.apache.kafka.clients.consumer.OffsetAndMetadata(5));

        // onPartitionsLost must NOT call commitSync
        consumer.onPartitionsLost(List.of(tp));

        Assertions.assertFalse(commitAttempted.get(),
            "onPartitionsLost must not attempt commitSync — commits are impossible when fenced");
    }

    /**
     * onPartitionsLost must still clean up partition state (offset trackers, commit maps).
     */
    @Test
    void onPartitionsLost_cleansUpPartitionState() {
        var mc = buildMockConsumer();
        var consumer = buildConsumer(mc);
        var tp = new TopicPartition(TOPIC, 0);

        consumer.onPartitionsAssigned(List.of(tp));
        Assertions.assertTrue(consumer.partitionToOffsetLifecycleTrackerMap.containsKey(0),
            "partition should be tracked after assignment");

        consumer.onPartitionsLost(List.of(tp));

        Assertions.assertFalse(consumer.partitionToOffsetLifecycleTrackerMap.containsKey(0),
            "partition state must be cleaned up after onPartitionsLost");
    }
}
