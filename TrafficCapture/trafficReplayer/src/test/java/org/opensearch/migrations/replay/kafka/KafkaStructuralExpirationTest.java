package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.expiration.ScopedConnectionIdKey;
import org.opensearch.migrations.replay.traffic.source.FollowUpRequirement;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.SourceControlEvent;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
            accumulator.accept(confirmedDead);

            assertEquals(RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD, expiredStatus.get());
            assertEquals(RequestResponsePacketPair.ReconstructionStatus.CONFIRMED_DEAD, closeStatus.get());
            assertEquals(1, accumulator.numberOfConnectionsExpired());
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

    private KafkaTrafficCaptureSource source(MockConsumer<String, byte[]> mockConsumer, Clock clock) {
        mockConsumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        return new KafkaTrafficCaptureSource(
            rootContext,
            mockConsumer,
            TOPIC,
            Duration.ofHours(1),
            clock,
            new KafkaBehavioralPolicy()
        );
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
