package org.opensearch.migrations.replay.kafka;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for TrackingKafkaConsumer rebalance callbacks.
 */
@Slf4j
class TrackingKafkaConsumerTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class RecordingCommitMetrics implements TrackingKafkaConsumer.Metrics {
        int unresolvedObligations;
        int stagedCommitPartitions;
        final Map<Integer, Integer> pendingAcknowledgements = new HashMap<>();
        final List<Duration> commitLatencies = new ArrayList<>();
        final List<String> observedHeads = new ArrayList<>();

        @Override
        public void unresolvedObligationsChanged(int delta) {
            unresolvedObligations += delta;
        }

        @Override
        public void stagedCommitPartitionsChanged(int delta) {
            stagedCommitPartitions += delta;
        }

        @Override
        public void pendingAcknowledgementsChanged(int generation, int delta) {
            pendingAcknowledgements.merge(generation, delta, Integer::sum);
        }

        @Override
        public void commitAcknowledged(int generation, Duration latency) {
            commitLatencies.add(latency);
        }

        @Override
        public void commitHeadObserved(int partition, int generation, Duration age) {
            observedHeads.add(partition + ":" + generation + ":" + age.toMillis());
        }
    }

    private static class BlockingOffsetLifecycleTracker extends OffsetLifecycleTracker {
        private final CountDownLatch removeEntered = new CountDownLatch(1);
        private final CountDownLatch allowRemoval = new CountDownLatch(1);

        BlockingOffsetLifecycleTracker(int generation) {
            super(generation);
        }

        @Override
        Optional<Long> removeAndReturnNewHead(long offsetToRemove) {
            removeEntered.countDown();
            try {
                if (!allowRemoval.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to retire the offset");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to retire the offset", e);
            }
            return super.removeAndReturnNewHead(offsetToRemove);
        }
    }

    private MockConsumer<String, byte[]> buildMockConsumer() {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.assign(List.of(tp));
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));
        return mc;
    }

    private TrackingKafkaConsumer buildConsumer(MockConsumer<String, byte[]> mc) {
        return new TrackingKafkaConsumer(
            rootContext, mc, TOPIC, Duration.ofSeconds(30), Clock.systemUTC(), tsk -> {}
        );
    }

    @Test
    void commitMetricsFollowAcceptanceAcknowledgementAndGenerationLoss() {
        var mockConsumer = buildMockConsumer();
        var clock = new MutableClock();
        var metrics = new RecordingCommitMetrics();
        var committedKeys = new ArrayList<ITrafficStreamKey>();
        var consumer = new TrackingKafkaConsumer(
            rootContext,
            mockConsumer,
            TOPIC,
            Duration.ofSeconds(30),
            clock,
            committedKeys::add,
            metrics
        );
        var partition = new TopicPartition(TOPIC, 0);
        consumer.onPartitionsAssigned(List.of(partition));

        var firstKey = Mockito.mock(ITrafficStreamKey.class);
        var secondKey = Mockito.mock(ITrafficStreamKey.class);
        mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "first", new byte[] { 0 }));
        mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 1, "second", new byte[] { 1 }));
        List<KafkaCommitOffsetData> offsets;
        try (var context = rootContext.createReadChunkContext()) {
            offsets = consumer.getNextBatchOfRecords(context, (offset, record) -> offset).toList();
        }
        Assertions.assertEquals(2, metrics.unresolvedObligations);

        clock.advance(Duration.ofSeconds(5));
        consumer.logHeartbeat();
        Assertions.assertEquals(List.of("0:1:5000"), metrics.observedHeads);

        Assertions.assertEquals(
            ITrafficCaptureSource.CommitResult.BLOCKED_BY_OTHER_COMMITS,
            consumer.commitKafkaKey(secondKey, offsets.get(1))
        );
        Assertions.assertEquals(
            ITrafficCaptureSource.CommitResult.AFTER_NEXT_READ,
            consumer.commitKafkaKey(firstKey, offsets.get(0))
        );
        Assertions.assertEquals(0, metrics.unresolvedObligations);
        Assertions.assertEquals(1, metrics.stagedCommitPartitions);
        Assertions.assertEquals(2, metrics.pendingAcknowledgements.get(1));

        clock.advance(Duration.ofSeconds(2));
        try (var context = rootContext.createReadChunkContext()) {
            consumer.getNextBatchOfRecords(context, (offset, record) -> offset).toList();
        }
        Assertions.assertEquals(List.of(firstKey, secondKey), committedKeys);
        Assertions.assertEquals(0, metrics.stagedCommitPartitions);
        Assertions.assertEquals(0, metrics.pendingAcknowledgements.get(1));
        Assertions.assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(2)), metrics.commitLatencies);
        Assertions.assertFalse(consumer.nextSetOfKeysContextsBeingCommitted.containsKey(partition));

        var thirdKey = Mockito.mock(ITrafficStreamKey.class);
        mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 2, "third", new byte[] { 2 }));
        KafkaCommitOffsetData thirdOffset;
        try (var context = rootContext.createReadChunkContext()) {
            thirdOffset = consumer.getNextBatchOfRecords(context, (offset, record) -> offset)
                .findFirst()
                .orElseThrow();
        }
        Assertions.assertEquals(1, metrics.unresolvedObligations);
        Assertions.assertEquals(
            ITrafficCaptureSource.CommitResult.AFTER_NEXT_READ,
            consumer.commitKafkaKey(thirdKey, thirdOffset)
        );
        Assertions.assertEquals(1, metrics.stagedCommitPartitions);
        Assertions.assertEquals(1, metrics.pendingAcknowledgements.get(1));

        consumer.onPartitionsLost(List.of(partition));

        Assertions.assertEquals(0, metrics.unresolvedObligations);
        Assertions.assertEquals(0, metrics.stagedCommitPartitions);
        Assertions.assertEquals(0, metrics.pendingAcknowledgements.get(1));
        Assertions.assertEquals(List.of(firstKey, secondKey), committedKeys);
    }

    @Test
    void closeClearsCommitMetricsWithoutARebalanceCallback() {
        var mockConsumer = buildMockConsumer();
        var metrics = new RecordingCommitMetrics();
        var consumer = new TrackingKafkaConsumer(
            rootContext,
            mockConsumer,
            TOPIC,
            Duration.ofSeconds(30),
            Clock.systemUTC(),
            ignored -> {},
            metrics
        );
        var partition = new TopicPartition(TOPIC, 0);
        consumer.onPartitionsAssigned(List.of(partition));

        mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "first", new byte[] { 0 }));
        mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 1, "second", new byte[] { 1 }));
        List<KafkaCommitOffsetData> offsets;
        try (var context = rootContext.createReadChunkContext()) {
            offsets = consumer.getNextBatchOfRecords(context, (commitOffset, record) -> commitOffset).toList();
        }
        Assertions.assertEquals(
            ITrafficCaptureSource.CommitResult.AFTER_NEXT_READ,
            consumer.commitKafkaKey(Mockito.mock(ITrafficStreamKey.class), offsets.get(0))
        );
        Assertions.assertEquals(1, metrics.unresolvedObligations);
        Assertions.assertEquals(1, metrics.stagedCommitPartitions);
        Assertions.assertEquals(1, metrics.pendingAcknowledgements.get(1));

        consumer.close();

        Assertions.assertEquals(0, metrics.unresolvedObligations);
        Assertions.assertEquals(0, metrics.stagedCommitPartitions);
        Assertions.assertEquals(0, metrics.pendingAcknowledgements.get(1));
        Assertions.assertTrue(consumer.partitionToOffsetLifecycleTrackerMap.isEmpty());
        Assertions.assertTrue(consumer.nextSetOfCommitsMap.isEmpty());
        Assertions.assertTrue(consumer.nextSetOfKeysContextsBeingCommitted.isEmpty());
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
            public synchronized void commitSync(Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets) {
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

    // -------------------------------------------------------------------------
    // Phase A: onPartitionsLost triggers synthetic close enqueue
    // -------------------------------------------------------------------------

    /**
     * Test #6: onPartitionsLost with active connections must trigger the
     * onPartitionsTrulyLostCallback, which enqueues synthetic closes.
     * Before fix: onPartitionsLost did not call onPartitionsTrulyLostCallback.
     */
    @Test
    void reassignedClose_onPartitionsLost_path() {
        var mc = buildMockConsumer();
        var consumer = buildConsumer(mc);
        var tp = new TopicPartition(TOPIC, 0);

        consumer.onPartitionsAssigned(List.of(tp));

        var trulyLostPartitions = new ArrayList<SourcePartitionKey>();
        consumer.setOnPartitionsTrulyLostCallback(trulyLostPartitions::addAll);

        consumer.onPartitionsLost(List.of(tp));

        Assertions.assertEquals(List.of(
            new SourcePartitionKey(TOPIC, 0, 1)
        ), trulyLostPartitions,
            "onPartitionsLost must call onPartitionsTrulyLostCallback with the lost partition numbers");
    }

    // -------------------------------------------------------------------------
    // onPartitionsRevoked fires the truly-lost callback at the OLD generation
    // -------------------------------------------------------------------------

    /**
     * Revocation must fire the truly-lost callback NOW, before any subsequent
     * onPartitionsAssigned bumps the generation. The session keys built from this
     * callback must reference the OLD generation so that channel sessions opened on
     * that generation can be matched and closed.
     */
    @Test
    void onPartitionsRevoked_firesTrulyLostCallbackAtOldGeneration() {
        var mc = buildMockConsumer();
        var consumer = buildConsumer(mc);
        var tp = new TopicPartition(TOPIC, 0);

        consumer.onPartitionsAssigned(List.of(tp));
        int generationAtAssign = consumer.getConsumerConnectionGeneration();

        var observedGenerations = new ArrayList<Integer>();
        var trulyLostPartitions = new ArrayList<SourcePartitionKey>();
        consumer.setOnPartitionsTrulyLostCallback(parts -> {
            observedGenerations.add(consumer.getConsumerConnectionGeneration());
            trulyLostPartitions.addAll(parts);
        });

        consumer.onPartitionsRevoked(List.of(tp));

        Assertions.assertEquals(List.of(
            new SourcePartitionKey(
                TOPIC,
                0,
                generationAtAssign
            )
        ), trulyLostPartitions,
            "onPartitionsRevoked must fire truly-lost callback for the revoked partition immediately");
        Assertions.assertEquals(List.of(generationAtAssign), observedGenerations,
            "callback must fire at the OLD generation (before any subsequent onPartitionsAssigned bump)");

        // A subsequent onPartitionsAssigned bumps the generation; the callback must NOT fire again.
        consumer.onPartitionsAssigned(List.of(tp));
        Assertions.assertEquals(List.of(
            new SourcePartitionKey(
                TOPIC,
                0,
                generationAtAssign
            )
        ), trulyLostPartitions,
            "truly-lost callback must not fire a second time on subsequent assignment");
        Assertions.assertTrue(consumer.getConsumerConnectionGeneration() > generationAtAssign,
            "subsequent onPartitionsAssigned must bump the generation");
    }

    @Test
    void cooperativeAssignmentsReportEachPartitionsActualGeneration() {
        var consumer = buildConsumer(buildMockConsumer());
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);
        var assigned = new ArrayList<SourcePartitionKey>();
        var revoked = new ArrayList<SourcePartitionKey>();
        consumer.setSourcePartitionLifecycleListener(new SourcePartitionLifecycleListener() {
            @Override
            public void onAssigned(java.util.Collection<SourcePartitionKey> partitions) {
                assigned.addAll(partitions);
            }

            @Override
            public void onRevoked(java.util.Collection<SourcePartitionKey> partitions) {
                revoked.addAll(partitions);
            }
        });

        consumer.onPartitionsAssigned(List.of(partition0));
        consumer.onPartitionsAssigned(List.of(partition1));
        consumer.onPartitionsRevoked(List.of(partition0, partition1));

        Assertions.assertEquals(
            List.of(
                new SourcePartitionKey(TOPIC, 0, 1),
                new SourcePartitionKey(TOPIC, 1, 2)
            ),
            assigned
        );
        Assertions.assertEquals(assigned, revoked);
    }

    @Test
    void runwayRevocationPrecedesSyntheticCloseGeneration() {
        var consumer = buildConsumer(buildMockConsumer());
        var partition = new TopicPartition(TOPIC, 0);
        var callbackOrder = new ArrayList<String>();
        consumer.setSourcePartitionLifecycleListener(new SourcePartitionLifecycleListener() {
            @Override
            public void onAssigned(java.util.Collection<SourcePartitionKey> partitions) {}

            @Override
            public void onRevoked(java.util.Collection<SourcePartitionKey> partitions) {
                callbackOrder.add("runway-revoked");
            }
        });
        consumer.setOnPartitionsTrulyLostCallback(ignored -> callbackOrder.add("synthetic-close"));
        consumer.onPartitionsAssigned(List.of(partition));

        consumer.onPartitionsRevoked(List.of(partition));

        Assertions.assertEquals(List.of("runway-revoked", "synthetic-close"), callbackOrder);
    }

    @Test
    void backwardOffsetFencesTheOldGenerationBeforeRedelivery() {
        var mockConsumer = buildMockConsumer();
        var consumer = buildConsumer(mockConsumer);
        var partition = new TopicPartition(TOPIC, 0);
        var assigned = new ArrayList<SourcePartitionKey>();
        var revoked = new ArrayList<SourcePartitionKey>();
        var trulyLost = new ArrayList<SourcePartitionKey>();
        consumer.setSourcePartitionLifecycleListener(new SourcePartitionLifecycleListener() {
            @Override
            public void onAssigned(java.util.Collection<SourcePartitionKey> partitions) {
                assigned.addAll(partitions);
            }

            @Override
            public void onRevoked(java.util.Collection<SourcePartitionKey> partitions) {
                revoked.addAll(partitions);
            }
        });
        consumer.setOnPartitionsTrulyLostCallback(trulyLost::addAll);
        consumer.onPartitionsAssigned(List.of(partition));

        mockConsumer.seek(partition, 5);
        mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 5, "old", new byte[] { 5 }));
        List<KafkaCommitOffsetData> oldRecords;
        try (var context = rootContext.createReadChunkContext()) {
            oldRecords = consumer.getNextBatchOfRecords(context, (offset, record) -> offset).toList();
        }
        Assertions.assertEquals(1, oldRecords.size());
        Assertions.assertEquals(1, oldRecords.get(0).getGeneration());

        mockConsumer.seek(partition, 0);
        mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "new", new byte[] { 0 }));
        List<KafkaCommitOffsetData> resetBatch;
        try (var context = rootContext.createReadChunkContext()) {
            resetBatch = consumer.getNextBatchOfRecords(context, (offset, record) -> offset).toList();
        }

        Assertions.assertTrue(resetBatch.isEmpty());
        Assertions.assertEquals(List.of(new SourcePartitionKey(TOPIC, 0, 1)), revoked);
        Assertions.assertEquals(revoked, trulyLost);
        Assertions.assertEquals(
            List.of(
                new SourcePartitionKey(TOPIC, 0, 1),
                new SourcePartitionKey(TOPIC, 0, 2)
            ),
            assigned
        );
        Assertions.assertEquals(0, mockConsumer.position(partition));

        mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "new", new byte[] { 0 }));
        List<KafkaCommitOffsetData> newRecords;
        try (var context = rootContext.createReadChunkContext()) {
            newRecords = consumer.getNextBatchOfRecords(context, (offset, record) -> offset).toList();
        }
        Assertions.assertEquals(1, newRecords.size());
        Assertions.assertEquals(2, newRecords.get(0).getGeneration());
    }

    @Test
    void sourceGenerationCleanupCannotRaceWithCommitEnqueue() throws Exception {
        var consumer = buildConsumer(buildMockConsumer());
        var partition = new TopicPartition(TOPIC, 0);
        consumer.onPartitionsAssigned(List.of(partition));

        var tracker = new BlockingOffsetLifecycleTracker(1);
        tracker.add(0);
        consumer.partitionToOffsetLifecycleTrackerMap.put(partition.partition(), tracker);

        var commitResult = new AtomicReference<ITrafficCaptureSource.CommitResult>();
        var commitFailure = new AtomicReference<Throwable>();
        var commitThread = new Thread(() -> {
            try {
                commitResult.set(consumer.commitKafkaKey(
                    Mockito.mock(ITrafficStreamKey.class),
                    new PojoKafkaCommitOffsetData(1, partition.partition(), 0)
                ));
            } catch (Throwable t) {
                commitFailure.set(t);
            }
        });
        var revokeFailure = new AtomicReference<Throwable>();
        var revokeStarted = new CountDownLatch(1);
        var revokeThread = new Thread(() -> {
            revokeStarted.countDown();
            try {
                consumer.onPartitionsLost(List.of(partition));
            } catch (Throwable t) {
                revokeFailure.set(t);
            }
        });

        commitThread.start();
        Assertions.assertTrue(tracker.removeEntered.await(5, TimeUnit.SECONDS));
        revokeThread.start();
        Assertions.assertTrue(revokeStarted.await(5, TimeUnit.SECONDS));
        try {
            revokeThread.join(100);
            Assertions.assertTrue(
                revokeThread.isAlive(),
                "partition cleanup must wait until commit validation and enqueueing are complete"
            );
        } finally {
            tracker.allowRemoval.countDown();
            commitThread.join(5_000);
            revokeThread.join(5_000);
        }

        Assertions.assertFalse(commitThread.isAlive());
        Assertions.assertFalse(revokeThread.isAlive());
        Assertions.assertNull(commitFailure.get());
        Assertions.assertNull(revokeFailure.get());
        Assertions.assertEquals(ITrafficCaptureSource.CommitResult.AFTER_NEXT_READ, commitResult.get());
        Assertions.assertFalse(consumer.partitionToOffsetLifecycleTrackerMap.containsKey(partition.partition()));
        Assertions.assertFalse(consumer.nextSetOfCommitsMap.containsKey(partition));
        Assertions.assertFalse(consumer.nextSetOfKeysContextsBeingCommitted.containsKey(partition));
    }

    @Test
    void scanAheadRestoresEveryReplayPosition() {
        var mc = buildMockConsumer();
        var consumer = buildConsumer(mc);
        var partition = new TopicPartition(TOPIC, 0);
        consumer.onPartitionsAssigned(List.of(partition));
        mc.seek(partition, 2);
        mc.updateEndOffsets(Map.of(partition, 4L));
        mc.addRecord(new ConsumerRecord<>(TOPIC, 0, 2, "key-2", new byte[] { 2 }));
        mc.addRecord(new ConsumerRecord<>(TOPIC, 0, 3, "key-3", new byte[] { 3 }));

        var cycle = consumer.scanAhead(10, Duration.ofSeconds(1));

        Assertions.assertTrue(cycle.stableGeneration());
        Assertions.assertFalse(cycle.exhaustedBudget());
        Assertions.assertEquals(List.of(2L, 3L), cycle.records().stream()
            .map(ConsumerRecord::offset)
            .toList());
        Assertions.assertEquals(2, mc.position(partition));
    }

    @Test
    void scanAheadDiscardsResultsWhenOwnershipChangesDuringPoll() {
        var mc = buildMockConsumer();
        var consumer = buildConsumer(mc);
        var partition = new TopicPartition(TOPIC, 0);
        consumer.onPartitionsAssigned(List.of(partition));
        mc.seek(partition, 0);
        mc.updateEndOffsets(Map.of(partition, 1L));
        mc.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "key", new byte[] { 1 }));
        mc.schedulePollTask(() -> {
            consumer.onPartitionsRevoked(List.of(partition));
            mc.assign(List.of());
        });

        var cycle = consumer.scanAhead(10, Duration.ofSeconds(1));

        Assertions.assertFalse(cycle.stableGeneration());
        Assertions.assertTrue(cycle.records().isEmpty());
    }

    @Test
    void scanAheadBoundsEveryMetadataRequestByItsBudget() {
        var mc = Mockito.spy(buildMockConsumer());
        var consumer = buildConsumer(mc);
        var partition = new TopicPartition(TOPIC, 0);
        consumer.onPartitionsAssigned(List.of(partition));
        mc.seek(partition, 0);
        mc.updateEndOffsets(Map.of(partition, 0L));
        var budget = Duration.ofSeconds(1);

        consumer.scanAhead(10, budget);

        var positionTimeout = ArgumentCaptor.forClass(Duration.class);
        Mockito.verify(mc, Mockito.atLeastOnce()).position(
            Mockito.eq(partition),
            positionTimeout.capture()
        );
        var endOffsetsTimeout = ArgumentCaptor.forClass(Duration.class);
        Mockito.verify(mc).endOffsets(
            Mockito.eq(Set.of(partition)),
            endOffsetsTimeout.capture()
        );
        positionTimeout.getAllValues().forEach(timeout -> {
            Assertions.assertTrue(timeout.isPositive());
            Assertions.assertTrue(timeout.compareTo(budget) <= 0);
        });
        Assertions.assertTrue(endOffsetsTimeout.getValue().isPositive());
        Assertions.assertTrue(endOffsetsTimeout.getValue().compareTo(budget) <= 0);
    }

    @Test
    void scanAheadSurfacesTransientMetadataFailureForTheSourceToRetry() {
        var mc = Mockito.spy(buildMockConsumer());
        var consumer = buildConsumer(mc);
        var partition = new TopicPartition(TOPIC, 0);
        consumer.onPartitionsAssigned(List.of(partition));
        mc.seek(partition, 0);
        Mockito.doThrow(new TimeoutException("metadata unavailable"))
            .when(mc)
            .endOffsets(Mockito.eq(Set.of(partition)), Mockito.any(Duration.class));

        Assertions.assertThrows(
            TimeoutException.class,
            () -> consumer.scanAhead(10, Duration.ofMillis(50))
        );
    }
}
