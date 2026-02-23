package org.opensearch.migrations.replay.kafka;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Verifies that TrackingKafkaConsumer.kafkaRecordsReadyToCommit is set to true when
 * commitKafkaKey() adds entries to nextSetOfCommitsMap.
 *
 * It's only set in onPartitionsRevoked(). This means getNextRequiredTouch() always returns
 * lastTouchTime + keepAliveInterval instead of Instant.now(), delaying commits by up to 30s.
 *
 * This test verifies that kafkaRecordsReadyToCommit is set to true when commitKafkaKey()
 * adds entries to nextSetOfCommitsMap.
 */
@Slf4j
public class KafkaRecordsReadyToCommitTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    @SneakyThrows
    private AtomicBoolean getReadyToCommitFlag(TrackingKafkaConsumer consumer) {
        Field f = TrackingKafkaConsumer.class.getDeclaredField("kafkaRecordsReadyToCommit");
        f.setAccessible(true);
        return (AtomicBoolean) f.get(consumer);
    }

    @SneakyThrows
    private Object getOffsetTracker(TrackingKafkaConsumer consumer, int partition) {
        Field f = TrackingKafkaConsumer.class.getDeclaredField("partitionToOffsetLifecycleTrackerMap");
        f.setAccessible(true);
        var map = (java.util.Map<Integer, ?>) f.get(consumer);
        return map.get(partition);
    }

    @SneakyThrows
    private void addOffsetToTracker(Object tracker, long offset) {
        Method m = tracker.getClass().getDeclaredMethod("add", long.class);
        m.setAccessible(true);
        m.invoke(tracker, offset);
    }

    @SneakyThrows
    private Object callCommitKafkaKey(TrackingKafkaConsumer consumer, ITrafficStreamKey streamKey,
                                      PojoKafkaCommitOffsetData offsetData) {
        Method m = TrackingKafkaConsumer.class.getDeclaredMethod("commitKafkaKey",
            ITrafficStreamKey.class,
            org.opensearch.migrations.replay.kafka.KafkaCommitOffsetData.class);
        m.setAccessible(true);
        return m.invoke(consumer, streamKey, offsetData);
    }

    @Test
    void kafkaRecordsReadyToCommit_setToTrue_afterCommitKafkaKeyAddsToMap() {
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mockConsumer.updateBeginningOffsets(new HashMap<>() {{ put(tp, 0L); }});

        var consumer = new TrackingKafkaConsumer(
            rootContext, mockConsumer, TOPIC, Duration.ofSeconds(30), Clock.systemUTC(), tsk -> {}
        );

        // Assign partition 0
        consumer.onPartitionsAssigned(Collections.singletonList(tp));

        // Add an offset to the tracker (simulating a record being polled)
        var tracker = getOffsetTracker(consumer, 0);
        Assertions.assertNotNull(tracker, "Tracker should exist after partition assignment");
        addOffsetToTracker(tracker, 0L);

        // Commit the offset — this adds to nextSetOfCommitsMap
        var offsetData = new PojoKafkaCommitOffsetData(1, 0, 0L);
        var result = callCommitKafkaKey(consumer, mock(ITrafficStreamKey.class), offsetData);
        log.info("commitKafkaKey result: {}", result);

        // kafkaRecordsReadyToCommit is true because commitKafkaKey sets it
        // when adding to nextSetOfCommitsMap
        var readyFlag = getReadyToCommitFlag(consumer);
        Assertions.assertTrue(readyFlag.get(),
            "kafkaRecordsReadyToCommit should be true after commitKafkaKey adds to nextSetOfCommitsMap");
    }
}
