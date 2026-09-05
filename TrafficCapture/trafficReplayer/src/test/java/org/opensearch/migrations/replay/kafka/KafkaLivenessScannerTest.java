package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.traffic.source.AbsenceProof;
import org.opensearch.migrations.replay.traffic.source.FollowUpRequirement;
import org.opensearch.migrations.replay.traffic.source.ScanEvidence;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaLivenessScannerTest {
    private static final String TOPIC = "traffic";
    private static final String NODE = "proxy";
    private static final String CONNECTION = "connection";
    private static final String PLAN = "plan";
    private static final int PARTITION = 2;
    private static final int GENERATION = 7;

    private final KafkaLivenessScanner scanner = new KafkaLivenessScanner();

    @Test
    void twoCompleteConsecutiveOmissionsProduceStructuralProof() {
        var result = evaluate(List.of(
            snapshot(11, 4, 0, 2, 1_000, "other-a"),
            snapshot(12, 4, 1, 2, 1_000, "other-b"),
            snapshot(13, 5, 0, 1, 2_000, "other-c")
        ));

        var confirmed = assertInstanceOf(ScanEvidence.ConfirmedAbsent.class, result);
        var proof = assertInstanceOf(AbsenceProof.LivenessOmission.class, confirmed.proof());
        assertEquals(10, proof.lastRecordOffsetForConnection());
        assertEquals(11, proof.firstOmittingSnapshot().firstOffset());
        assertEquals(12, proof.firstOmittingSnapshot().lastOffset());
        assertEquals(13, proof.secondOmittingSnapshot().firstOffset());
        assertEquals(FollowUpRequirement.RESPONSE_COMPLETION, confirmed.requirement());
    }

    @Test
    void oneOmissionOrNonconsecutiveSnapshotsRemainInconclusive() {
        assertInstanceOf(ScanEvidence.Inconclusive.class, evaluate(List.of(
            snapshot(11, 4, 0, 1, 1_000),
            snapshot(12, 6, 0, 1, 2_000)
        )));
    }

    @Test
    void incompleteDuplicateReorderedAndContradictorySnapshotsAreNeverProof() {
        assertInstanceOf(ScanEvidence.Inconclusive.class, evaluate(List.of(
            snapshot(11, 4, 0, 2, 1_000)
        )));
        assertInstanceOf(ScanEvidence.Inconclusive.class, evaluate(List.of(
            snapshot(11, 4, 0, 2, 1_000),
            snapshot(12, 4, 0, 2, 1_000),
            snapshot(13, 5, 0, 1, 2_000)
        )));
        assertInstanceOf(ScanEvidence.Inconclusive.class, evaluate(List.of(
            snapshot(11, 4, 1, 2, 1_000),
            snapshot(12, 4, 0, 2, 1_000),
            snapshot(13, 5, 0, 1, 2_000)
        )));
        assertInstanceOf(ScanEvidence.Inconclusive.class, evaluate(List.of(
            snapshot(11, 4, 0, 2, 1_000),
            snapshot(12, 4, 1, 2, 1_001),
            snapshot(13, 5, 0, 1, 2_000)
        )));
    }

    @Test
    void futureTrafficOrSnapshotPresenceKeepsConnectionAlive() {
        assertInstanceOf(ScanEvidence.FollowUpPresent.class, evaluate(List.of(
            traffic(11, PARTITION, PLAN)
        )));
        assertInstanceOf(ScanEvidence.FollowUpPresent.class, evaluate(List.of(
            snapshot(11, 4, 0, 1, 1_000, CONNECTION),
            snapshot(12, 5, 0, 1, 2_000)
        )));
    }

    @Test
    void unstableGenerationDiscardsOtherwiseValidProof() {
        var cycle = new TrackingKafkaConsumer.ScanCycle(List.of(
            snapshot(11, 4, 0, 1, 1_000),
            snapshot(12, 5, 0, 1, 2_000)
        ), false, false);

        assertInstanceOf(ScanEvidence.Inconclusive.class, scanner.evaluate(List.of(candidate()), cycle).get(0));
    }

    @Test
    void silentNodeRemainsInconclusive() {
        assertInstanceOf(ScanEvidence.Inconclusive.class, evaluate(List.of()));
    }

    @Test
    void oversizedSnapshotIsUnusableRatherThanEmpty() {
        assertInstanceOf(ScanEvidence.Inconclusive.class, evaluate(List.of(
            snapshot(11, 4, 0, 100_001, 1_000),
            snapshot(12, 5, 0, 1, 2_000)
        )));
    }

    @Test
    void partitionAndRoutingMismatchesFailLoudly() {
        assertThrows(IllegalStateException.class, () -> evaluate(List.of(
            snapshot(11, 4, 0, 1, 1_000, PARTITION + 1, PLAN)
        )));
        assertThrows(IllegalStateException.class, () -> evaluate(List.of(
            traffic(11, PARTITION + 1, PLAN)
        )));
        assertThrows(IllegalStateException.class, () -> evaluate(List.of(
            traffic(11, PARTITION, "")
        )));
    }

    private ScanEvidence evaluate(List<ConsumerRecord<String, byte[]>> records) {
        return scanner.evaluate(
            List.of(candidate()),
            new TrackingKafkaConsumer.ScanCycle(records, true, false)
        ).get(0);
    }

    private KafkaLivenessScanner.Candidate candidate() {
        return new KafkaLivenessScanner.Candidate(
            new SourcePartitionKey(TOPIC, PARTITION, GENERATION),
            new SourceConnectionKey(NODE, CONNECTION),
            PLAN,
            10,
            FollowUpRequirement.RESPONSE_COMPLETION
        );
    }

    private ConsumerRecord<String, byte[]> snapshot(
        long offset,
        long sequence,
        int chunkIndex,
        int chunkCount,
        long emittedAtMillis,
        String... openConnections
    ) {
        return snapshot(
            offset,
            sequence,
            chunkIndex,
            chunkCount,
            emittedAtMillis,
            PARTITION,
            PLAN,
            openConnections
        );
    }

    private ConsumerRecord<String, byte[]> snapshot(
        long offset,
        long sequence,
        int chunkIndex,
        int chunkCount,
        long emittedAtMillis,
        int partitionStamp,
        String routingPlanId,
        String... openConnections
    ) {
        var builder = ProxyLivenessSnapshotChunk.newBuilder()
            .setNodeId(NODE)
            .setPartition(partitionStamp)
            .setRoutingPlanId(routingPlanId)
            .setSnapshotSequence(sequence)
            .setChunkIndex(chunkIndex)
            .setChunkCount(chunkCount)
            .setEmittedAtMillis(emittedAtMillis);
        for (var connection : openConnections) {
            builder.addOpenConnections(ByteString.copyFromUtf8(connection));
        }
        var record = new ConsumerRecord<String, byte[]>(
            TOPIC,
            PARTITION,
            offset,
            "snapshot",
            builder.build().toByteArray()
        );
        record.headers().add(
            CaptureRecordTypes.RECORD_TYPE_HEADER,
            CaptureRecordTypes.LIVENESS_RECORD_TYPE.getBytes(StandardCharsets.UTF_8)
        );
        return record;
    }

    private ConsumerRecord<String, byte[]> traffic(long offset, int partitionStamp, String routingPlanId) {
        var stream = TrafficStream.newBuilder()
            .setNodeId(NODE)
            .setConnectionId(CONNECTION)
            .setPartition(partitionStamp)
            .setRoutingPlanId(routingPlanId)
            .build();
        return new ConsumerRecord<>(TOPIC, PARTITION, offset, "traffic", stream.toByteArray());
    }
}
