package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.lifecycle.IgnoringSourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.KafkaConsumerContexts;
import org.opensearch.migrations.replay.traffic.expiration.ScopedConnectionIdKey;
import org.opensearch.migrations.replay.traffic.source.AbsenceProof;
import org.opensearch.migrations.replay.traffic.source.FollowUpRequirement;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.SourceControlEvent;
import org.opensearch.migrations.replay.traffic.source.SourceInput;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.ProxyNoMoreWrites;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class KafkaStructuralExpirationTest extends InstrumentationTest {
    private static final String TOPIC = "traffic";
    private static final String NODE = "proxy";
    private static final String CONNECTION = "connection";
    private static final String PLAN = "plan";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private static final class TransientMetadataFailureConsumer extends MockConsumer<String, byte[]> {
        private boolean failNextEndOffsets;

        private TransientMetadataFailureConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized Map<TopicPartition, Long> endOffsets(
            Collection<TopicPartition> partitions,
            Duration timeout
        ) {
            if (failNextEndOffsets) {
                failNextEndOffsets = false;
                throw new TimeoutException("metadata temporarily unavailable");
            }
            return super.endOffsets(partitions, timeout);
        }
    }

    private static final class ScanForbiddenConsumer extends MockConsumer<String, byte[]> {
        private ScanForbiddenConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized Map<TopicPartition, Long> endOffsets(
            Collection<TopicPartition> partitions,
            Duration timeout
        ) {
            throw new AssertionError("Scanner-disabled replay must not request scan end offsets");
        }
    }

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void stampedPartialRequestSettlesOnlyAfterStructuralProof() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, true));
            var traffic = assertInstanceOf(
                ITrafficStreamWithKey.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var expiredStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
            var closeStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
            var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofMillis(1),
                null,
                callbacks(expiredStatus, closeStatus, new AtomicInteger()),
                true,
                source::updateScanBlocker
            );
            accumulator.accept(traffic);
            source.updateScanBlocker(traffic.getKey(), FollowUpRequirement.REQUEST_COMPLETION);

            addRecord(mockConsumer, snapshotRecord(1, 10));
            addRecord(mockConsumer, snapshotRecord(2, 11));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 3L));
            clock.advance(Duration.ofSeconds(2));
            touch(source);

            assertTrue(source.hasPendingSourceControl());
            var confirmedDead = assertInstanceOf(
                SourceControlEvent.ConfirmedDead.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            assertEquals(FollowUpRequirement.REQUEST_COMPLETION, confirmedDead.evidence().requirement());
            var proof = assertInstanceOf(
                AbsenceProof.LivenessOmission.class,
                confirmedDead.evidence().proof()
            );
            assertEquals(2, proof.omittingSnapshot().firstOffset());
            assertEquals(0, proof.lastRecordOffsetForConnection());
            accumulator.accept(confirmedDead);

            assertEquals(RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD, expiredStatus.get());
            assertEquals(RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD, closeStatus.get());
            assertEquals(1, accumulator.numberOfConnectionsExpired());
            assertLivenessScanMetrics(
                2,
                IKafkaConsumerContexts.LivenessScanVerdict.CONFIRMED_ABSENT
            );
        }
    }

    @Test
    void replayCursorManifestProofMatchesScannerDispositionWhenScanningIsDisabled() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new ScanForbiddenConsumer();
        try (var source = source(mockConsumer, clock, false)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, true));
            var traffic = assertInstanceOf(
                ITrafficStreamWithKey.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var expiredStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
            var closeStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
            var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofMillis(1),
                null,
                callbacks(expiredStatus, closeStatus, new AtomicInteger()),
                true,
                source::updateScanBlocker
            );
            accumulator.accept(traffic);

            addRecord(mockConsumer, snapshotRecord(1, 10));
            addRecord(mockConsumer, snapshotRecord(2, 11));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 3L));
            var markers = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(5, TimeUnit.SECONDS);
            assertEquals(2, markers.size());
            markers.forEach(accumulator::accept);

            var confirmedDead = assertInstanceOf(
                SourceControlEvent.ConfirmedDead.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var proof = assertInstanceOf(
                AbsenceProof.LivenessOmission.class,
                confirmedDead.evidence().proof()
            );
            assertEquals(2, proof.omittingSnapshot().firstOffset());
            assertEquals(0, proof.lastRecordOffsetForConnection());
            accumulator.accept(confirmedDead);

            assertEquals(RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD, expiredStatus.get());
            assertEquals(RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD, closeStatus.get());
            assertEquals(1, accumulator.numberOfConnectionsExpired());
            assertNoLivenessScanCycle();
        }
    }

    @Test
    void replayCursorEvidenceCanBreakFullOwnershipBackpressureWhenScanningIsDisabled() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new ScanForbiddenConsumer();
        try (var source = source(mockConsumer, clock, false, 3)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, true));
            var traffic = assertInstanceOf(
                ITrafficStreamWithKey.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            source.updateScanBlocker(traffic.getKey(), FollowUpRequirement.REQUEST_COMPLETION);

            addRecord(mockConsumer, snapshotRecord(1, 10));
            addRecord(mockConsumer, snapshotRecord(2, 11));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 3L));
            var markers = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(5, TimeUnit.SECONDS);

            assertEquals(2, markers.size());
            assertFalse(source.isReadCapacityAvailable());
            assertTrue(source.hasPendingSourceControl());
            for (var marker : markers) {
                var keyedMarker = assertInstanceOf(ITrafficStreamWithKey.class, marker);
                assertEquals(
                    ITrafficCaptureSource.CommitResult.BLOCKED_BY_OTHER_COMMITS,
                    source.commitTrafficStream(keyedMarker.getKey())
                );
            }

            var confirmedDead = assertInstanceOf(
                SourceControlEvent.ConfirmedDead.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            assertInstanceOf(AbsenceProof.LivenessOmission.class, confirmedDead.evidence().proof());
            assertFalse(source.hasPendingSourceControl());

            assertEquals(
                ITrafficCaptureSource.CommitResult.AFTER_NEXT_READ,
                source.commitTrafficStream(traffic.getKey())
            );
            touch(source);
            assertTrue(source.isReadCapacityAvailable());
            assertNoLivenessScanCycle();
        }
    }

    @Test
    void replayCursorDeclarationProofMatchesScannerDispositionWhenScanningIsDisabled() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new ScanForbiddenConsumer();
        try (var source = source(mockConsumer, clock, false)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, true));
            var traffic = assertInstanceOf(
                ITrafficStreamWithKey.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var expiredStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
            var closeStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
            var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofMillis(1),
                null,
                callbacks(expiredStatus, closeStatus, new AtomicInteger()),
                true,
                source::updateScanBlocker
            );
            accumulator.accept(traffic);

            addRecord(mockConsumer, noMoreWritesRecord(1, "survivor"));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 2L));
            var marker = assertInstanceOf(
                KafkaNoMoreWritesRecord.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            accumulator.accept(marker);

            var confirmedDead = assertInstanceOf(
                SourceControlEvent.ConfirmedDead.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var proof = assertInstanceOf(
                AbsenceProof.NoMoreWrites.class,
                confirmedDead.evidence().proof()
            );
            assertTrue(proof.peerDeclared());
            assertEquals(1, proof.declarationOffset());
            assertEquals(0, proof.lastRecordOffsetForConnection());
            accumulator.accept(confirmedDead);

            assertEquals(RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD, expiredStatus.get());
            assertEquals(RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD, closeStatus.get());
            assertEquals(1, accumulator.numberOfConnectionsExpired());
            assertNoLivenessScanCycle();
        }
    }

    @Test
    void scanAheadDoesNotChangeTheFinalDispositionOfTheDurableLog() throws Exception {
        var withScanAhead = runDispositionEquivalenceScenario(true);
        var withoutScanAhead = runDispositionEquivalenceScenario(false);

        assertEquals(withoutScanAhead, withScanAhead);
        assertEquals(
            new DispositionOutcome(
                NODE + ":0:11:after-0",
                RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD,
                RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD,
                2,
                1,
                3,
                0,
                0,
                false,
                false
            ),
            withScanAhead
        );
    }

    private DispositionOutcome runDispositionEquivalenceScenario(boolean scanAheadEnabled) throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock, scanAheadEnabled)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, true));
            var traffic = assertInstanceOf(
                ITrafficStreamWithKey.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var expiredStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
            var closeStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
            var ignored = new AtomicInteger();
            var recordsToCommit = new LinkedHashSet<ITrafficStreamKey>();
            var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofMillis(1),
                null,
                dispositionCallbacks(expiredStatus, closeStatus, ignored, recordsToCommit),
                true,
                source::updateScanBlocker
            );
            accumulator.accept(traffic);

            addRecord(mockConsumer, snapshotRecord(1, 10));
            addRecord(mockConsumer, snapshotRecord(2, 11));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 3L));

            final SourceControlEvent.ConfirmedDead confirmedDead;
            final List<SourceInput> markerRecords;
            if (scanAheadEnabled) {
                clock.advance(Duration.ofSeconds(2));
                touch(source);
                confirmedDead = readConfirmedDead(source);
                accumulator.accept(confirmedDead);
                // MockConsumer restores the seek position but does not refetch records consumed
                // by the scan poll. Re-offer the same broker offsets to model the replay cursor.
                addRecord(mockConsumer, snapshotRecord(1, 10));
                addRecord(mockConsumer, snapshotRecord(2, 11));
                markerRecords = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS);
                markerRecords.forEach(accumulator::accept);
            } else {
                markerRecords = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS);
                markerRecords.forEach(accumulator::accept);
                confirmedDead = readConfirmedDead(source);
                accumulator.accept(confirmedDead);
            }

            assertEquals(2, markerRecords.size());
            assertTrue(markerRecords.stream().allMatch(KafkaLivenessSnapshotRecord.class::isInstance));
            assertEquals(3, recordsToCommit.size());
            source.onConnectionAccumulationComplete(traffic.getKey());
            touch(source);

            var acknowledgements = new ArrayList<CompletableFuture<Void>>();
            for (var key : recordsToCommit) {
                key.getTrafficStreamsContext().close();
                acknowledgements.add(source.commitTrafficStreamAsync(key).toCompletableFuture());
            }
            CompletableFuture.allOf(acknowledgements.toArray(CompletableFuture[]::new))
                .get(5, TimeUnit.SECONDS);

            var committed = mockConsumer.committed(Set.of(PARTITION)).get(PARTITION);
            assertNotNull(committed);
            var ownership = source.trackingKafkaConsumer.ownershipBudgetSnapshot();
            return new DispositionOutcome(
                confirmedDead.evidence().proof().proofId(),
                expiredStatus.get(),
                closeStatus.get(),
                ignored.get(),
                accumulator.numberOfConnectionsExpired(),
                committed.offset(),
                ownership.records(),
                ownership.bytes(),
                source.hasPendingSourceControl(),
                source.partitionToActiveConnections.containsKey(PARTITION.partition())
            );
        }
    }

    private SourceControlEvent.ConfirmedDead readConfirmedDead(
        KafkaTrafficCaptureSource source
    ) throws Exception {
        return assertInstanceOf(
            SourceControlEvent.ConfirmedDead.class,
            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(5, TimeUnit.SECONDS)
                .get(0)
        );
    }

    @Test
    void futureTrafficIsReportedAsALivenessFollowUp() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, true));
            var traffic = assertInstanceOf(
                ITrafficStreamWithKey.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );

            addRecord(mockConsumer, trafficRecord(1, true));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 2L));
            clock.advance(Duration.ofSeconds(2));
            touch(source);

            assertFalse(source.hasPendingSourceControl());
            assertLivenessScanMetrics(
                1,
                IKafkaConsumerContexts.LivenessScanVerdict.FOLLOW_UP_FOUND
            );

            source.onConnectionAccumulationComplete(traffic.getKey());
            traffic.getKey().getTrafficStreamsContext().close();
            source.releaseTrafficStreamWithoutCommit(traffic.getKey());
        }
    }

    @Test
    void transientMetadataFailureRemainsInconclusiveAndTheNextScanCanConfirmAbsence() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new TransientMetadataFailureConsumer();
        try (var source = source(mockConsumer, clock)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, true));
            var traffic = assertInstanceOf(
                ITrafficStreamWithKey.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );

            mockConsumer.failNextEndOffsets = true;
            clock.advance(Duration.ofSeconds(2));
            touch(source);

            assertFalse(source.hasPendingSourceControl());

            addRecord(mockConsumer, snapshotRecord(1, 10));
            addRecord(mockConsumer, snapshotRecord(2, 11));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 3L));
            clock.advance(Duration.ofSeconds(2));
            touch(source);

            var confirmedDead = assertInstanceOf(
                SourceControlEvent.ConfirmedDead.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            assertEquals(traffic.getKey().getSourceGeneration(), confirmedDead.evidence().partition().sourceGeneration());

            source.onConnectionAccumulationComplete(traffic.getKey());
            traffic.getKey().getTrafficStreamsContext().close();
            source.releaseTrafficStreamWithoutCommit(traffic.getKey());
        }
    }

    @Test
    void mismatchedGenerationCompletionCannotEraseCurrentStructuralExpirationState() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, true));
            var traffic = assertInstanceOf(
                ITrafficStreamWithKey.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var currentKey = traffic.getKey();
            var staleKey = mock(
                ITrafficStreamKey.class,
                withSettings().extraInterfaces(KafkaCommitOffsetData.class)
            );
            when(staleKey.getNodeId()).thenReturn(NODE);
            when(staleKey.getConnectionId()).thenReturn(CONNECTION);
            when(((KafkaCommitOffsetData) staleKey).getPartition()).thenReturn(PARTITION.partition());
            when(((KafkaCommitOffsetData) staleKey).getGeneration())
                .thenReturn(currentKey.getSourceGeneration() - 1);

            source.onConnectionAccumulationComplete(staleKey);

            assertTrue(
                source.partitionToActiveConnections.get(PARTITION.partition())
                    .contains(new ScopedConnectionIdKey(NODE, CONNECTION)),
                "another generation must not remove the current connection"
            );

            addRecord(mockConsumer, snapshotRecord(1, 10));
            addRecord(mockConsumer, snapshotRecord(2, 11));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 3L));
            clock.advance(Duration.ofSeconds(2));
            touch(source);

            assertTrue(source.hasPendingSourceControl());
            var confirmedDead = assertInstanceOf(
                SourceControlEvent.ConfirmedDead.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            assertEquals(currentKey.getSourceGeneration(), confirmedDead.evidence().partition().sourceGeneration());

            source.onConnectionAccumulationComplete(currentKey);
            currentKey.getTrafficStreamsContext().close();
            source.releaseTrafficStreamWithoutCommit(currentKey);
        }
    }

    @Test
    void unstampedLegacyTrafficCannotProduceAbsenceProof() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock)) {
            scheduleFirstPoll(mockConsumer, trafficRecord(0, false));
            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(5, TimeUnit.SECONDS);

            addRecord(mockConsumer, snapshotRecord(1, 10));
            addRecord(mockConsumer, snapshotRecord(2, 11));
            mockConsumer.updateEndOffsets(Map.of(PARTITION, 3L));
            clock.advance(Duration.ofSeconds(2));
            touch(source);

            assertFalse(source.hasPendingSourceControl());
        }
    }

    @Test
    void replayCursorSettlesLivenessRecordsWithoutCreatingAccumulations() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock)) {
            scheduleFirstPoll(mockConsumer, snapshotRecord(0, 10));
            var marker = assertInstanceOf(
                KafkaLivenessSnapshotRecord.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var ignored = new AtomicInteger();
            var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(1),
                null,
                callbacks(new AtomicReference<>(), new AtomicReference<>(), ignored),
                true,
                source::updateScanBlocker
            );

            accumulator.accept(marker);

            assertEquals(1, ignored.get());
            assertEquals(0, accumulator.numberOfConnectionsCreated());
        }
    }

    @Test
    void replayCursorSettlesNoMoreWritesRecordsWithoutCreatingAccumulations() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock)) {
            scheduleFirstPoll(mockConsumer, noMoreWritesRecord(0, NODE));
            var marker = assertInstanceOf(
                KafkaNoMoreWritesRecord.class,
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
                    .get(0)
            );
            var ignored = new AtomicInteger();
            var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(1),
                null,
                callbacks(new AtomicReference<>(), new AtomicReference<>(), ignored),
                true,
                source::updateScanBlocker
            );

            accumulator.accept(marker);

            assertEquals(1, ignored.get());
            assertEquals(0, accumulator.numberOfConnectionsCreated());
        }
    }

    @Test
    void peerDeclarationDiscardsLaterTrafficButStillReturnsACommitBearingRecord() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock)) {
            mockConsumer.schedulePollTask(() -> {
                mockConsumer.rebalance(Collections.singletonList(PARTITION));
                addRecord(mockConsumer, noMoreWritesRecord(0, "survivor"));
                addRecord(mockConsumer, trafficRecord(1, true));
                mockConsumer.updateEndOffsets(Map.of(PARTITION, 2L));
            });

            var records = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(5, TimeUnit.SECONDS);

            assertEquals(2, records.size());
            assertInstanceOf(KafkaNoMoreWritesRecord.class, records.get(0));
            var discarded = assertInstanceOf(KafkaSupersededTrafficRecord.class, records.get(1));
            assertEquals(0, discarded.getDeclarationOffset());
            assertFalse(source.partitionToActiveConnections.containsKey(PARTITION.partition()));
            assertMetricValue(
                IKafkaConsumerContexts.MetricNames.SUPERSEDED_TRAFFIC_RECORDS_DISCARDED,
                1
            );
        }
    }

    @Test
    void selfDeclarationDoesNotDiscardTrafficAfterReassignment() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(1));
        var mockConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        try (var source = source(mockConsumer, clock)) {
            mockConsumer.schedulePollTask(() -> {
                mockConsumer.rebalance(Collections.singletonList(PARTITION));
                addRecord(mockConsumer, noMoreWritesRecord(0, NODE));
                addRecord(mockConsumer, trafficRecord(1, true));
                mockConsumer.updateEndOffsets(Map.of(PARTITION, 2L));
            });

            var records = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(5, TimeUnit.SECONDS);

            assertEquals(2, records.size());
            assertInstanceOf(KafkaNoMoreWritesRecord.class, records.get(0));
            assertInstanceOf(ITrafficStreamWithKey.class, records.get(1));
            assertFalse(records.get(1) instanceof KafkaSupersededTrafficRecord);
            assertTrue(source.partitionToActiveConnections.get(PARTITION.partition())
                .contains(new ScopedConnectionIdKey(NODE, CONNECTION)));
        }
    }

    private KafkaTrafficCaptureSource source(MockConsumer<String, byte[]> mockConsumer, Clock clock) {
        return source(mockConsumer, clock, true);
    }

    private KafkaTrafficCaptureSource source(
        MockConsumer<String, byte[]> mockConsumer,
        Clock clock,
        boolean livenessScanAheadEnabled
    ) {
        return source(
            mockConsumer,
            clock,
            livenessScanAheadEnabled,
            TrackingKafkaConsumer.UNBOUNDED_OWNED_RECORDS
        );
    }

    private KafkaTrafficCaptureSource source(
        MockConsumer<String, byte[]> mockConsumer,
        Clock clock,
        boolean livenessScanAheadEnabled,
        int maximumOwnedRecords
    ) {
        mockConsumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        var source = new KafkaTrafficCaptureSource(
            rootContext,
            mockConsumer,
            TOPIC,
            Duration.ofHours(1),
            clock,
            new KafkaBehavioralPolicy(),
            maximumOwnedRecords,
            TrackingKafkaConsumer.UNBOUNDED_OWNED_BYTES,
            livenessScanAheadEnabled
        );
        source.setSourcePartitionLifecycleListener(
            new IgnoringSourcePartitionLifecycleListener()
        );
        return source;
    }

    private void assertNoLivenessScanCycle() {
        assertFalse(
            rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
                .stream()
                .anyMatch(metric -> metric.getName().equals(
                    IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_COUNT
                ))
        );
    }

    private void assertLivenessScanMetrics(
        long recordsScanned,
        IKafkaConsumerContexts.LivenessScanVerdict terminalVerdict
    ) {
        var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        var scanCount = metrics.stream()
            .filter(metric -> metric.getName().equals(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_COUNT))
            .findFirst()
            .orElseThrow();
        assertEquals(
            1,
            scanCount.getLongSumData().getPoints().stream().findFirst().orElseThrow().getValue()
        );
        var distance = metrics.stream()
            .filter(metric -> metric.getName().equals(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_DISTANCE))
            .findFirst()
            .orElseThrow();
        var distancePoint = distance.getHistogramData().getPoints().stream().findFirst().orElseThrow();
        assertEquals(1, distancePoint.getCount());
        assertEquals(recordsScanned, distancePoint.getSum());
        var latency = metrics.stream()
            .filter(metric -> metric.getName().equals(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_LATENCY))
            .findFirst()
            .orElseThrow();
        assertEquals(1, latency.getHistogramData().getPoints().stream().findFirst().orElseThrow().getCount());
        var discardedBytes = metrics.stream()
            .filter(metric -> metric.getName().equals(
                IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_BYTES_DISCARDED
            ))
            .findFirst()
            .orElseThrow();
        assertTrue(discardedBytes.getLongSumData().getPoints().stream().findFirst().orElseThrow().getValue() > 0);
        var verdictMetric = metrics.stream()
            .filter(metric -> metric.getName().equals(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_VERDICT_COUNT))
            .findFirst()
            .orElseThrow();
        assertEquals(1, verdictMetric.getLongSumData().getPoints().size());
        assertVerdictCount(verdictMetric, terminalVerdict, 1);
    }

    private void assertMetricValue(String metricName, long expectedValue) {
        var metric = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(candidate -> candidate.getName().equals(metricName))
            .findFirst()
            .orElseThrow();
        assertEquals(
            expectedValue,
            metric.getLongSumData().getPoints().stream().findFirst().orElseThrow().getValue()
        );
    }

    private static void assertVerdictCount(
        io.opentelemetry.sdk.metrics.data.MetricData verdictMetric,
        IKafkaConsumerContexts.LivenessScanVerdict verdict,
        long expectedCount
    ) {
        var verdictPoint = verdictMetric.getLongSumData()
            .getPoints()
            .stream()
            .filter(point -> verdict.metricLabel().equals(
                point.getAttributes().get(KafkaConsumerContexts.LivenessScanContext.VERDICT_ATTRIBUTE)
            ))
            .findFirst()
            .orElse(null);
        assertNotNull(verdictPoint);
        assertEquals(expectedCount, verdictPoint.getValue());
    }

    private void scheduleFirstPoll(
        MockConsumer<String, byte[]> mockConsumer,
        ConsumerRecord<String, byte[]> record
    ) {
        mockConsumer.schedulePollTask(() -> {
            mockConsumer.rebalance(Collections.singletonList(PARTITION));
            addRecord(mockConsumer, record);
            mockConsumer.updateEndOffsets(Map.of(PARTITION, record.offset() + 1));
        });
    }

    private void touch(KafkaTrafficCaptureSource source) {
        try (var readContext = rootContext.createReadChunkContext();
             var backPressureContext = readContext.createBackPressureContext()) {
            source.touch(backPressureContext);
        }
    }

    private static ConsumerRecord<String, byte[]> trafficRecord(long offset, boolean stamped) {
        var stream = TrafficStream.newBuilder()
            .setNodeId(NODE)
            .setConnectionId(CONNECTION)
            .addSubStream(TrafficObservation.newBuilder()
                .setTs(Timestamp.newBuilder().setSeconds(1))
                .setRead(ReadObservation.newBuilder().setData(ByteString.copyFromUtf8("GET /")))
                .build());
        if (stamped) {
            stream.setPartition(PARTITION.partition()).setRoutingPlanId(PLAN);
        }
        return new ConsumerRecord<>(TOPIC, PARTITION.partition(), offset, "traffic", stream.build().toByteArray());
    }

    private static ConsumerRecord<String, byte[]> snapshotRecord(long offset, long sequence) {
        var snapshot = ProxyLivenessSnapshotChunk.newBuilder()
            .setNodeId(NODE)
            .setPartition(PARTITION.partition())
            .setRoutingPlanId(PLAN)
            .setSnapshotSequence(sequence)
            .setChunkIndex(0)
            .setChunkCount(1)
            .setEmittedAtMillis(sequence * 1_000)
            .build();
        var record = new ConsumerRecord<String, byte[]>(
            TOPIC,
            PARTITION.partition(),
            offset,
            "snapshot",
            snapshot.toByteArray()
        );
        record.headers().add(
            CaptureRecordTypes.RECORD_TYPE_HEADER,
            CaptureRecordTypes.LIVENESS_RECORD_TYPE.getBytes(StandardCharsets.UTF_8)
        );
        return record;
    }

    private static ConsumerRecord<String, byte[]> noMoreWritesRecord(
        long offset,
        String declaredBy
    ) {
        var declaration = ProxyNoMoreWrites.newBuilder()
            .setNodeId(NODE)
            .setPartition(PARTITION.partition())
            .setDeclaredBy(declaredBy)
            .setEmittedAtMillis(1_000)
            .build();
        var record = new ConsumerRecord<String, byte[]>(
            TOPIC,
            PARTITION.partition(),
            offset,
            "no-more-writes",
            declaration.toByteArray()
        );
        record.headers().add(
            CaptureRecordTypes.RECORD_TYPE_HEADER,
            CaptureRecordTypes.NO_MORE_WRITES_RECORD_TYPE.getBytes(StandardCharsets.UTF_8)
        );
        return record;
    }

    private static void addRecord(
        MockConsumer<String, byte[]> mockConsumer,
        ConsumerRecord<String, byte[]> record
    ) {
        mockConsumer.addRecord(record);
    }

    private static AccumulationCallbacks callbacks(
        AtomicReference<RequestResponsePacketPair.ReconstructionStatus> expiredStatus,
        AtomicReference<RequestResponsePacketPair.ReconstructionStatus> closeStatus,
        AtomicInteger ignored
    ) {
        return new AccumulationCallbacks() {
            @Override
            public Consumer<RequestResponsePacketPair> onRequestReceived(
                @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                @NonNull HttpMessageAndTimestamp request,
                boolean isResumedConnection
            ) {
                return pair -> {};
            }

            @Override
            public void onTrafficStreamsExpired(
                RequestResponsePacketPair.ReconstructionStatus status,
                @NonNull IReplayContexts.IChannelKeyContext ctx,
                @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
            ) {
                expiredStatus.set(status);
            }

            @Override
            public void onConnectionClose(
                int channelInteractionNum,
                @NonNull IReplayContexts.IChannelKeyContext ctx,
                int channelSessionNumber,
                RequestResponsePacketPair.ReconstructionStatus status,
                @NonNull Instant timestamp,
                @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
            ) {
                closeStatus.set(status);
            }

            @Override
            public void onTrafficStreamIgnored(
                @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
            ) {
                ignored.incrementAndGet();
            }
        };
    }

    private static AccumulationCallbacks dispositionCallbacks(
        AtomicReference<RequestResponsePacketPair.ReconstructionStatus> expiredStatus,
        AtomicReference<RequestResponsePacketPair.ReconstructionStatus> closeStatus,
        AtomicInteger ignored,
        Set<ITrafficStreamKey> recordsToCommit
    ) {
        return new AccumulationCallbacks() {
            @Override
            public Consumer<RequestResponsePacketPair> onRequestReceived(
                @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                @NonNull HttpMessageAndTimestamp request,
                boolean isResumedConnection
            ) {
                return pair -> {};
            }

            @Override
            public void onTrafficStreamsExpired(
                RequestResponsePacketPair.ReconstructionStatus status,
                @NonNull IReplayContexts.IChannelKeyContext ctx,
                @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
            ) {
                expiredStatus.set(status);
                recordsToCommit.addAll(trafficStreamKeysBeingHeld);
            }

            @Override
            public void onConnectionClose(
                int channelInteractionNum,
                @NonNull IReplayContexts.IChannelKeyContext ctx,
                int channelSessionNumber,
                RequestResponsePacketPair.ReconstructionStatus status,
                @NonNull Instant timestamp,
                @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
            ) {
                closeStatus.set(status);
                recordsToCommit.addAll(trafficStreamKeysBeingHeld);
            }

            @Override
            public void onTrafficStreamIgnored(
                @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
            ) {
                ignored.incrementAndGet();
                recordsToCommit.add(ctx.getTrafficStreamKey());
            }
        };
    }

    private record DispositionOutcome(
        String proofId,
        RequestResponsePacketPair.ReconstructionStatus expiredStatus,
        RequestResponsePacketPair.ReconstructionStatus closeStatus,
        int ignoredRecords,
        int expiredConnections,
        long committedOffset,
        int ownedRecords,
        long ownedBytes,
        boolean pendingSourceControl,
        boolean activeConnection
    ) {}

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            now = new AtomicReference<>(initial);
        }

        private void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
