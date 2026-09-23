/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.

// REBUILD-LIMBO-START(G11)
/*

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.tracing.InstrumentationTest;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrackingKafkaConsumerTest extends InstrumentationTest {
    private static final String TOPIC = "test-topic";
    private static final SourcePartitionLifecycleListener NOOP_LIFECYCLE_LISTENER =
        new SourcePartitionLifecycleListener() {
            @Override
            public void onAssigned(java.util.Collection<SourcePartitionKey> partitions) {}

            @Override
            public void onRevoked(java.util.Collection<SourcePartitionKey> partitions) {}

            @Override
            public void onRetired(java.util.Collection<SourcePartitionKey> partitions) {}
        };

    @Test
    void onlyRecordProcessingFinishedAdvancesTheObservedPrefix() {
        var fixture = fixture();
        fixture.consumer.addRecord(record(10));
        fixture.consumer.addRecord(record(12));

        var accepted = poll(fixture.tracking);
        Assertions.assertEquals(
            List.of(10L, 12L),
            accepted.stream().map(KafkaCommitOffsetData::getOffset).toList()
        );

        fixture.tracking.recordProcessingFinished(recordId(12, 1));
        Assertions.assertTrue(fixture.tracking.nextSetOfCommitsMap.isEmpty());

        fixture.tracking.recordProcessingFinished(recordId(10, 1));
        Assertions.assertEquals(
            13L,
            fixture.tracking.nextSetOfCommitsMap.get(fixture.partition).offset()
        );
    }

    @Test
    void duplicateCompletionIsAnInvariantFailureForTheActiveGeneration() {
        var fixture = fixture();
        fixture.consumer.addRecord(record(3));
        poll(fixture.tracking);

        var id = recordId(3, 1);
        fixture.tracking.recordProcessingFinished(id);
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> fixture.tracking.recordProcessingFinished(id)
        );
    }

    @Test
    void aLateCompletionFromARevokedGenerationCannotAdvanceTheSuccessor() {
        var fixture = fixture();
        fixture.consumer.addRecord(record(4));
        poll(fixture.tracking);
        fixture.tracking.onPartitionsLost(List.of(fixture.partition));
        fixture.tracking.onPartitionsAssigned(List.of(fixture.partition));

        fixture.tracking.recordProcessingFinished(recordId(4, 1));

        Assertions.assertTrue(fixture.tracking.nextSetOfCommitsMap.isEmpty());
        Assertions.assertEquals(2, fixture.tracking.getConsumerConnectionGeneration());
    }

    @Test
    void partitionLossDiscardsStagedOffsetsAndObservedRecords() {
        var fixture = fixture();
        fixture.consumer.addRecord(record(7));
        poll(fixture.tracking);
        fixture.tracking.recordProcessingFinished(recordId(7, 1));
        Assertions.assertFalse(fixture.tracking.nextSetOfCommitsMap.isEmpty());

        fixture.tracking.onPartitionsLost(List.of(fixture.partition));

        Assertions.assertFalse(
            fixture.tracking.partitionToObservedRecordQueueMap.containsKey(fixture.partition.partition())
        );
        Assertions.assertTrue(fixture.tracking.nextSetOfCommitsMap.isEmpty());
        Assertions.assertEquals(0, fixture.tracking.ownershipBudgetSnapshot().records());
    }

    @Test
    void stagedCommitRequiresAnImmediateTouchEvenAfterTheQueueDrains() {
        var fixture = fixture();
        fixture.consumer.addRecord(record(9));
        poll(fixture.tracking);
        fixture.tracking.recordProcessingFinished(recordId(9, 1));

        Assertions.assertTrue(fixture.tracking.getNextRequiredTouch().isPresent());
    }

    @Test
    void completionOfAnUnregisteredActiveRecordFails() {
        var fixture = fixture();
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> fixture.tracking.recordProcessingFinished(recordId(99, 1))
        );
    }

    @Test
    void monitoringReadsUseImmutableSnapshotsOffTheKafkaOwnerThread() throws Exception {
        var fixture = fixture();
        fixture.consumer.addRecord(record(14));
        poll(fixture.tracking);

        CompletableFuture.runAsync(() -> {
            Assertions.assertTrue(fixture.tracking.getNextRequiredTouch().isPresent());
            fixture.tracking.logHeartbeat();
        }).get(5, TimeUnit.SECONDS);
    }

    private Fixture fixture() {
        var partition = new TopicPartition(TOPIC, 0);
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        consumer.assign(List.of(partition));
        consumer.updateBeginningOffsets(new HashMap<>(Map.of(partition, 0L)));
        var tracking = new TrackingKafkaConsumer(
            rootContext,
            consumer,
            TOPIC,
            Duration.ofSeconds(30),
            Clock.systemUTC()
        );
        tracking.setSourcePartitionLifecycleListener(NOOP_LIFECYCLE_LISTENER);
        tracking.onPartitionsAssigned(List.of(partition));
        return new Fixture(consumer, tracking, partition);
    }

    private List<KafkaCommitOffsetData> poll(TrackingKafkaConsumer tracking) {
        try (var context = rootContext.createReadChunkContext()) {
            return tracking.getNextBatchOfRecords(context, (offset, record) -> offset).toList();
        }
    }

    private static ConsumerRecord<String, byte[]> record(long offset) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "key-" + offset, new byte[] { 1 });
    }

    private static KafkaRecordId recordId(long offset, int generation) {
        return new KafkaRecordId(TOPIC, 0, offset, generation);
    }

    private record Fixture(
        MockConsumer<String, byte[]> consumer,
        TrackingKafkaConsumer tracking,
        TopicPartition partition
    ) {}
}

*/
// REBUILD-LIMBO-END(G11)