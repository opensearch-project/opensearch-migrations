/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.lang.reflect.Method;
import java.util.Map;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaTopicDumperTest {

    @Test
    void isAtEndReturnsFalseWhenAnyPartitionHasNotReachedEndOffset() throws Exception {
        var first = new TopicPartition("traffic", 0);
        var second = new TopicPartition("traffic", 1);
        var consumer = mockConsumer();
        when(consumer.position(first)).thenReturn(10L);
        when(consumer.position(second)).thenReturn(4L);

        var result = invokeIsAtEnd(consumer, Map.of(first, 10L, second, 5L));

        Assertions.assertFalse(result);
    }

    @Test
    void isAtEndReturnsTrueWhenAllPartitionPositionsReachEndOffsets() throws Exception {
        var first = new TopicPartition("traffic", 0);
        var second = new TopicPartition("traffic", 1);
        var consumer = mockConsumer();
        when(consumer.position(first)).thenReturn(10L);
        when(consumer.position(second)).thenReturn(5L);

        var result = invokeIsAtEnd(consumer, Map.of(first, 10L, second, 5L));

        Assertions.assertTrue(result);
    }

    @SuppressWarnings("unchecked")
    private static KafkaConsumer<String, byte[]> mockConsumer() {
        return mock(KafkaConsumer.class);
    }

    private static boolean invokeIsAtEnd(KafkaConsumer<String, byte[]> consumer,
                                         Map<TopicPartition, Long> endOffsets) throws Exception {
        Method method = KafkaTopicDumper.class.getDeclaredMethod("isAtEnd", KafkaConsumer.class, Map.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, consumer, endOffsets);
    }
}
