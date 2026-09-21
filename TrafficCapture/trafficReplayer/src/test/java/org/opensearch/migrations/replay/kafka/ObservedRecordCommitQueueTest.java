/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.util.List;
import java.util.OptionalLong;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ObservedRecordCommitQueueTest {
    private static final PartitionGenerationId GENERATION =
        new PartitionGenerationId(new TopicPartition("traffic", 2), 4);

    @Test
    void laterCompletionWaitsForObservedHeadAndPhysicalOffsetGapsAreAllowed() {
        var queue = new ObservedRecordCommitQueue(GENERATION);
        var first = record(10);
        var second = record(12);
        var third = record(20);
        queue.register(first);
        queue.register(second);
        queue.register(third);

        var blocked = queue.recordProcessingFinished(second);
        Assertions.assertEquals(List.of(), blocked.newlyContiguousRecords());
        Assertions.assertEquals(OptionalLong.empty(), blocked.nextCommitOffset());

        var throughSecond = queue.recordProcessingFinished(first);
        Assertions.assertEquals(List.of(first, second), throughSecond.newlyContiguousRecords());
        Assertions.assertEquals(OptionalLong.of(13), throughSecond.nextCommitOffset());
        Assertions.assertEquals(OptionalLong.of(20), queue.headOffset());

        var throughThird = queue.recordProcessingFinished(third);
        Assertions.assertEquals(List.of(third), throughThird.newlyContiguousRecords());
        Assertions.assertEquals(OptionalLong.of(21), throughThird.nextCommitOffset());
        Assertions.assertTrue(queue.isEmpty());
    }

    @Test
    void duplicateRegistrationAndCompletionAreInvariantFailures() {
        var duplicateRegistration = new ObservedRecordCommitQueue(GENERATION);
        duplicateRegistration.register(record(10));
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> duplicateRegistration.register(record(10))
        );

        var duplicateCompletion = new ObservedRecordCommitQueue(GENERATION);
        duplicateCompletion.register(record(10));
        duplicateCompletion.register(record(12));
        duplicateCompletion.recordProcessingFinished(record(12));
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> duplicateCompletion.recordProcessingFinished(record(12))
        );
    }

    @Test
    void unknownRecordAndWrongGenerationAreRejected() {
        var queue = new ObservedRecordCommitQueue(GENERATION);
        queue.register(record(10));

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> queue.recordProcessingFinished(record(11))
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> queue.register(new KafkaRecordId("traffic", 2, 12, 5))
        );
    }

    private static KafkaRecordId record(long offset) {
        return new KafkaRecordId("traffic", 2, offset, 4);
    }
}
