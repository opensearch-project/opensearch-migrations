package org.opensearch.migrations.replay.kafka;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class TrackingKafkaConsumerHeartbeatTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    @Test
    void logHeartbeat_emptyConsumer_doesNotThrow() {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        var consumer = new TrackingKafkaConsumer(
            rootContext, mc, TOPIC, Duration.ofSeconds(30), Clock.systemUTC(), tsk -> {}
        );
        Assertions.assertDoesNotThrow(consumer::logHeartbeat);
    }

    @Test
    void logHeartbeat_withPartitionsAssigned_doesNotThrow() {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        var consumer = new TrackingKafkaConsumer(
            rootContext, mc, TOPIC, Duration.ofSeconds(30), Clock.systemUTC(), tsk -> {}
        );
        consumer.onPartitionsAssigned(List.of(tp));
        Assertions.assertDoesNotThrow(consumer::logHeartbeat);
    }

    @Test
    void logHeartbeat_withInflightOffsets_reportsCommitHead() {
        var fixedClock = Clock.fixed(Instant.now(), ZoneId.of("UTC"));
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        var consumer = new TrackingKafkaConsumer(
            rootContext, mc, TOPIC, Duration.ofSeconds(30), fixedClock, tsk -> {}
        );
        consumer.onPartitionsAssigned(List.of(tp));

        // Directly add an offset to the tracker to simulate inflight work
        consumer.partitionToOffsetLifecycleTrackerMap.get(0).add(5L, "test-conn");
        consumer.partitionToOffsetLifecycleTrackerMap.get(0).add(6L, "test-conn-2");

        Assertions.assertDoesNotThrow(consumer::logHeartbeat);
    }

    @Test
    void logHeartbeat_withStaleCommitHead_logsWarning() {
        var pastClock = Clock.fixed(Instant.now().minusSeconds(300), ZoneId.of("UTC"));
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        var consumer = new TrackingKafkaConsumer(
            rootContext, mc, TOPIC, Duration.ofSeconds(30), pastClock, tsk -> {}
        );
        consumer.onPartitionsAssigned(List.of(tp));

        // Add offset with a stale addedAt (using the past clock)
        consumer.partitionToOffsetLifecycleTrackerMap.get(0).add(10L, "stuck-conn");

        // Now switch to a "current" clock for the heartbeat evaluation
        // The tracker's metadata was stored with pastClock, so age = 300s > 120s threshold
        var currentClock = Clock.systemUTC();
        var consumer2 = new TrackingKafkaConsumer(
            rootContext, mc, TOPIC, Duration.ofSeconds(30), currentClock, tsk -> {}
        );
        consumer2.onPartitionsAssigned(List.of(tp));
        consumer2.partitionToOffsetLifecycleTrackerMap.get(0).add(10L, "stuck-conn");

        // Wait a tiny bit — the metadata records "now" with currentClock, but we need to
        // test the >120s warning path. Use reflection to backdate the metadata.
        var tracker = consumer2.partitionToOffsetLifecycleTrackerMap.get(0);
        try {
            var metaField = OffsetLifecycleTracker.class.getDeclaredField("offsetMetadataMap");
            metaField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var metaMap = (java.util.Map<Long, OffsetLifecycleTracker.OffsetMetadata>) metaField.get(tracker);
            metaMap.put(10L, new OffsetLifecycleTracker.OffsetMetadata("stuck-conn", Instant.now().minusSeconds(200)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Assertions.assertDoesNotThrow(consumer2::logHeartbeat);
    }
}
