package org.opensearch.migrations.replay.kafka;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionPartitionGenerationKey;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for attributable synthetic-close termination obligations.
 */
class TrafficSourceReaderInterruptedCloseAccountingTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    /**
     * The source obligation is keyed by source connection and generation, so the actual session
     * number discovered by the accumulator cannot cause a missed acknowledgement.
     */
    @Test
    void terminationAcknowledgementMatchesANonZeroSessionNumber() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            var obligationKey = obligationKey("node1", "conn1", 5);
            source.pendingSessionTerminationObligations.put(
                obligationKey,
                new KafkaTrafficCaptureSource.SessionTerminationObligation(0)
            );

            source.acknowledgeSessionTermination(session("node1", "conn1", 2, 5))
                .toCompletableFuture()
                .get();

            Assertions.assertFalse(source.pendingSessionTerminationObligations.containsKey(obligationKey));
        }
    }

    @Test
    void terminationAcknowledgementIsIdempotentWhenRegularCloseArrivesFirst() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            var obligationKey = obligationKey("node1", "conn1", 3);
            var obligation = new KafkaTrafficCaptureSource.SessionTerminationObligation(0);
            source.pendingSessionTerminationObligations.put(obligationKey, obligation);

            source.acknowledgeSessionTermination(session("node1", "conn1", 0, 3))
                .toCompletableFuture()
                .get();
            source.acknowledgeSessionTermination(session("node1", "conn1", 0, 3))
                .toCompletableFuture()
                .get();

            Assertions.assertTrue(source.pendingSessionTerminationObligations.isEmpty());
            Assertions.assertTrue(obligation.completion().toCompletableFuture().isDone());
        }
    }

    @Test
    void acknowledgementForAnotherGenerationCannotSettleTheObligation() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            var obligationKey = obligationKey("node1", "conn1", 3);
            source.pendingSessionTerminationObligations.put(
                obligationKey,
                new KafkaTrafficCaptureSource.SessionTerminationObligation(0)
            );

            source.acknowledgeSessionTermination(session("node1", "conn1", 0, 4))
                .toCompletableFuture()
                .get();

            Assertions.assertTrue(source.pendingSessionTerminationObligations.containsKey(obligationKey));
        }
    }

    @Test
    void oneSessionAcknowledgementSettlesEveryPartitionScopedObligation() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            source.pendingSessionTerminationObligations.put(
                obligationKey("node1", "conn1", 0, 3),
                new KafkaTrafficCaptureSource.SessionTerminationObligation(0)
            );
            source.pendingSessionTerminationObligations.put(
                obligationKey("node1", "conn1", 2, 3),
                new KafkaTrafficCaptureSource.SessionTerminationObligation(2)
            );

            source.acknowledgeSessionTermination(session("node1", "conn1", 4, 3))
                .toCompletableFuture()
                .get();

            Assertions.assertTrue(source.pendingSessionTerminationObligations.isEmpty());
        }
    }

    @Test
    void realReadsResumeOnlyAfterEveryTerminationObligationSettles() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            int N = 3;
            for (int i = 0; i < N; i++) {
                source.pendingSessionTerminationObligations.put(
                    obligationKey("node", "conn" + i, 1),
                    new KafkaTrafficCaptureSource.SessionTerminationObligation(0)
                );
            }

            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.singletonList(tp));
                addRecord(mc, tp, 0);
            });
            var emptyResult = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertTrue(emptyResult.isEmpty(),
                "real records must stay gated while termination obligations remain");

            for (int i = 0; i < N; i++) {
                source.acknowledgeSessionTermination(session("node", "conn" + i, i + 2, 1))
                    .toCompletableFuture()
                    .get();
            }
            Assertions.assertTrue(source.pendingSessionTerminationObligations.isEmpty());

            var realResult = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertFalse(realResult.isEmpty(),
                "real records must resume after all obligations settle");
        }
    }

    private static SourceConnectionPartitionGenerationKey obligationKey(
        String nodeId,
        String connectionId,
        int generation
    ) {
        return obligationKey(nodeId, connectionId, 0, generation);
    }

    private static SourceConnectionPartitionGenerationKey obligationKey(
        String nodeId,
        String connectionId,
        int partition,
        int generation
    ) {
        return new SourceConnectionPartitionGenerationKey(
            new SourceConnectionKey(nodeId, connectionId),
            partition,
            generation
        );
    }

    private static ConnectionSessionKey session(
        String nodeId,
        String connectionId,
        int sessionNumber,
        int generation
    ) {
        return new ConnectionSessionKey(
            new SourceConnectionKey(nodeId, connectionId),
            sessionNumber,
            generation
        );
    }

    private static void addRecord(MockConsumer<String, byte[]> mc, TopicPartition tp, long offset) {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder()
                .setTs(Timestamp.newBuilder().setSeconds(1).build())
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\n\r\n", StandardCharsets.UTF_8))
                    .build()).build())
            .build();
        try (var baos = new ByteArrayOutputStream()) {
            ts.writeTo(baos);
            mc.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), offset, "k", baos.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
