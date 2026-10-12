/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourcePort.CommitPoint;

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
        register(queue, first);
        register(queue, second);
        register(queue, third);

        var blocked = queue.recordProcessingFinished(second);
        Assertions.assertEquals(List.of(), blocked.newlyContiguousRecords());
        Assertions.assertEquals(Optional.empty(), blocked.commitPoint());

        var throughSecond = queue.recordProcessingFinished(first);
        Assertions.assertEquals(List.of(first, second), throughSecond.newlyContiguousRecords());
        Assertions.assertEquals(
            Optional.of(new CommitPoint(13, timestampFor(second))),
            throughSecond.commitPoint(),
            "the watermark belongs to the last contiguous record, not the record whose callback arrived last"
        );
        Assertions.assertEquals(java.util.OptionalLong.of(20), queue.headOffset());

        var throughThird = queue.recordProcessingFinished(third);
        Assertions.assertEquals(List.of(third), throughThird.newlyContiguousRecords());
        Assertions.assertEquals(
            Optional.of(new CommitPoint(21, timestampFor(third))),
            throughThird.commitPoint()
        );
        Assertions.assertTrue(queue.isEmpty());
    }

    @Test
    void duplicateRegistrationAndCompletionAreInvariantFailures() {
        var duplicateRegistration = new ObservedRecordCommitQueue(GENERATION);
        register(duplicateRegistration, record(10));
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> register(duplicateRegistration, record(10))
        );

        var duplicateCompletion = new ObservedRecordCommitQueue(GENERATION);
        register(duplicateCompletion, record(10));
        register(duplicateCompletion, record(12));
        duplicateCompletion.recordProcessingFinished(record(12));
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> duplicateCompletion.recordProcessingFinished(record(12))
        );
    }

    @Test
    void unknownRecordAndWrongGenerationAreRejected() {
        var queue = new ObservedRecordCommitQueue(GENERATION);
        register(queue, record(10));

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> queue.recordProcessingFinished(record(11))
        );
        var laterGenerationOfTheSamePartition =
            new PartitionGenerationId(GENERATION.topicPartition(), GENERATION.localSequence() + 1);
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> queue.register(new KafkaRecordId(laterGenerationOfTheSamePartition, 12), 1_012L)
        );
    }

    @Test
    void commitIneligibleHeadBlocksLaterCompletionAndCannotComplete() {
        var queue = new ObservedRecordCommitQueue(GENERATION);
        var head = record(10);
        var later = record(12);
        register(queue, head);
        register(queue, later);

        queue.markCommitIneligible(head);
        var blocked = queue.recordProcessingFinished(later);

        Assertions.assertEquals(List.of(), blocked.newlyContiguousRecords());
        Assertions.assertEquals(Optional.empty(), blocked.commitPoint());
        Assertions.assertEquals(java.util.OptionalLong.of(10), queue.headOffset());
        Assertions.assertEquals(1, queue.commitIneligibleCount());
        Assertions.assertEquals(0, queue.unfinishedCount());
        Assertions.assertEquals(1, queue.completedBehindHeadCount());
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> queue.recordProcessingFinished(head)
        );
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> queue.markCommitIneligible(head)
        );
    }

    private static KafkaRecordId record(long offset) {
        return new KafkaRecordId(GENERATION, offset);
    }

    private static void register(ObservedRecordCommitQueue queue, KafkaRecordId recordId) {
        queue.register(recordId, timestampFor(recordId));
    }

    private static long timestampFor(KafkaRecordId recordId) {
        return 1_000L + recordId.offset();
    }
}
