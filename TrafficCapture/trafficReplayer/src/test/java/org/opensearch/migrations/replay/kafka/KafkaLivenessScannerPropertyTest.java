package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.traffic.source.AbsenceProof;
import org.opensearch.migrations.replay.traffic.source.CompleteSnapshotSpan;
import org.opensearch.migrations.replay.traffic.source.FollowUpRequirement;
import org.opensearch.migrations.replay.traffic.source.ScanEvidence;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KafkaLivenessScannerPropertyTest {
    private static final int GENERATED_CASES = 1_024;
    private static final String TOPIC = "traffic";
    private static final String NODE = "proxy";
    private static final String CONNECTION = "connection";
    private static final String PLAN = "plan";
    private static final int PARTITION = 2;
    private static final int GENERATION = 7;
    private static final long LAST_REPLAYED_OFFSET = 10;

    private final KafkaLivenessScanner scanner = new KafkaLivenessScanner();

    @Test
    void randomizedScanHistoriesOnlyConfirmFromCompleteConsecutiveOmissions() {
        var observed = new ObservedOutcomes();

        for (int seed = 0; seed < GENERATED_CASES; ++seed) {
            var scenario = Scenario.generate(seed);
            var evidence = scanner.evaluate(
                List.of(candidate()),
                new TrackingKafkaConsumer.ScanCycle(
                    scenario.records(),
                    scenario.stableGeneration(),
                    scenario.exhaustedBudget()
                )
            ).get(0);

            assertEvidenceMatchesOracle(seed, scenario, evidence);
            observed.record(scenario, evidence);
        }

        observed.assertCoverage();
    }

    @Test
    void randomizedRoutingViolationsAlwaysHaltInsteadOfProducingEvidence() {
        for (int seed = 0; seed < 256; ++seed) {
            var random = new Random(seed);
            var offset = LAST_REPLAYED_OFFSET + 1;
            ConsumerRecord<String, byte[]> invalidRecord;
            if (random.nextBoolean()) {
                invalidRecord = snapshot(
                    offset,
                    NODE,
                    random.nextBoolean() ? PARTITION + 1 : PARTITION,
                    random.nextBoolean() ? "" : PLAN,
                    1,
                    0,
                    1,
                    1_000,
                    List.of()
                );
                if (invalidRecord.partition() == PARTITION
                    && !snapshotPayload(invalidRecord).getRoutingPlanId().isBlank()) {
                    invalidRecord = snapshot(
                        offset,
                        NODE,
                        PARTITION + 1,
                        PLAN,
                        1,
                        0,
                        1,
                        1_000,
                        List.of()
                    );
                }
            } else {
                invalidRecord = traffic(
                    offset,
                    NODE,
                    CONNECTION,
                    random.nextBoolean() ? PARTITION + 1 : PARTITION,
                    random.nextBoolean() ? "" : PLAN
                );
                if (invalidRecord.partition() == PARTITION
                    && !trafficPayload(invalidRecord).getRoutingPlanId().isBlank()) {
                    invalidRecord = traffic(offset, NODE, CONNECTION, PARTITION + 1, PLAN);
                }
            }

            var cycle = new TrackingKafkaConsumer.ScanCycle(List.of(invalidRecord), true, false);
            Assertions.assertThrows(
                IllegalStateException.class,
                () -> scanner.evaluate(List.of(candidate()), cycle),
                "seed " + seed + " accepted a routing violation"
            );
        }
    }

    private void assertEvidenceMatchesOracle(int seed, Scenario scenario, ScanEvidence evidence) {
        var expected = scenario.expectedEvidence();
        switch (expected) {
            case INCONCLUSIVE -> Assertions.assertInstanceOf(
                ScanEvidence.Inconclusive.class,
                evidence,
                "seed " + seed + " should remain inconclusive"
            );
            case FOLLOW_UP -> {
                var followUp = Assertions.assertInstanceOf(
                    ScanEvidence.FollowUpPresent.class,
                    evidence,
                    "seed " + seed + " should report a follow-up"
                );
                Assertions.assertEquals(
                    scenario.expectedFollowUpOffset(),
                    followUp.offset(),
                    "seed " + seed + " reported the wrong follow-up offset"
                );
            }
            case CONFIRMED_ABSENT -> {
                var confirmed = Assertions.assertInstanceOf(
                    ScanEvidence.ConfirmedAbsent.class,
                    evidence,
                    "seed " + seed + " should carry structural absence proof"
                );
                Assertions.assertEquals(FollowUpRequirement.RESPONSE_COMPLETION, confirmed.requirement());
                var proof = Assertions.assertInstanceOf(AbsenceProof.LivenessOmission.class, confirmed.proof());
                Assertions.assertTrue(
                    scenario.isValidProof(proof),
                    () -> "seed " + seed + " produced proof from records that do not authorize it: " + proof
                );
            }
        }
    }

    private KafkaLivenessScanner.Candidate candidate() {
        return new KafkaLivenessScanner.Candidate(
            new SourcePartitionKey(TOPIC, PARTITION, GENERATION),
            new SourceConnectionKey(NODE, CONNECTION),
            PLAN,
            LAST_REPLAYED_OFFSET,
            FollowUpRequirement.RESPONSE_COMPLETION
        );
    }

    private enum ExpectedEvidence {
        FOLLOW_UP,
        CONFIRMED_ABSENT,
        INCONCLUSIVE
    }

    private record ValidSnapshot(
        String nodeId,
        int partition,
        String routingPlanId,
        long sequence,
        long firstOffset,
        long lastOffset,
        boolean containsConnection
    ) {
        CompleteSnapshotSpan span() {
            return new CompleteSnapshotSpan(sequence, firstOffset, lastOffset, routingPlanId);
        }
    }

    private record Scenario(
        List<ConsumerRecord<String, byte[]>> records,
        List<ValidSnapshot> validSnapshots,
        List<Long> trafficFollowUps,
        boolean stableGeneration,
        boolean exhaustedBudget,
        boolean includedMalformedSnapshot
    ) {
        static Scenario generate(int seed) {
            var builder = new ScenarioBuilder(new Random(seed));
            builder.addBaseCase();
            int noiseRecords = builder.random.nextInt(9);
            for (int i = 0; i < noiseRecords; ++i) {
                builder.addNoise();
            }
            return builder.build();
        }

        ExpectedEvidence expectedEvidence() {
            if (!stableGeneration) {
                return ExpectedEvidence.INCONCLUSIVE;
            }
            if (!trafficFollowUps.isEmpty() || firstContainingSnapshot() != null) {
                return ExpectedEvidence.FOLLOW_UP;
            }
            return validProofPairs().isEmpty()
                ? ExpectedEvidence.INCONCLUSIVE
                : ExpectedEvidence.CONFIRMED_ABSENT;
        }

        long expectedFollowUpOffset() {
            if (!trafficFollowUps.isEmpty()) {
                return trafficFollowUps.stream().mapToLong(Long::longValue).min().orElseThrow();
            }
            return firstContainingSnapshot().lastOffset();
        }

        boolean isValidProof(AbsenceProof.LivenessOmission proof) {
            return proof.nodeId().equals(NODE)
                && proof.partition() == PARTITION
                && proof.lastRecordOffsetForConnection() == LAST_REPLAYED_OFFSET
                && validProofPairs().stream()
                    .anyMatch(pair ->
                        pair.first().span().equals(proof.firstOmittingSnapshot())
                            && pair.second().span().equals(proof.secondOmittingSnapshot()));
        }

        private ValidSnapshot firstContainingSnapshot() {
            return relevantSnapshots().stream()
                .filter(ValidSnapshot::containsConnection)
                .findFirst()
                .orElse(null);
        }

        private List<SnapshotPair> validProofPairs() {
            var relevant = relevantSnapshots();
            var pairs = new ArrayList<SnapshotPair>();
            for (int i = 1; i < relevant.size(); ++i) {
                var first = relevant.get(i - 1);
                var second = relevant.get(i);
                if (!first.containsConnection()
                    && !second.containsConnection()
                    && second.sequence() == first.sequence() + 1
                    && first.lastOffset() < second.firstOffset()) {
                    pairs.add(new SnapshotPair(first, second));
                }
            }
            return pairs;
        }

        private List<ValidSnapshot> relevantSnapshots() {
            return validSnapshots.stream()
                .filter(snapshot -> snapshot.nodeId().equals(NODE))
                .filter(snapshot -> snapshot.partition() == PARTITION)
                .filter(snapshot -> snapshot.routingPlanId().equals(PLAN))
                .filter(snapshot -> snapshot.firstOffset() > LAST_REPLAYED_OFFSET)
                .sorted(Comparator.comparingLong(ValidSnapshot::firstOffset))
                .toList();
        }
    }

    private record SnapshotPair(ValidSnapshot first, ValidSnapshot second) {}

    private static final class ScenarioBuilder {
        private final Random random;
        private final List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        private final List<ValidSnapshot> validSnapshots = new ArrayList<>();
        private final List<Long> trafficFollowUps = new ArrayList<>();
        private long nextOffset = LAST_REPLAYED_OFFSET + 1;
        private long nextSequence;
        private boolean includedMalformedSnapshot;

        private ScenarioBuilder(Random random) {
            this.random = random;
            nextSequence = random.nextInt(1_000);
        }

        private void addBaseCase() {
            long sequence = takeSequence();
            switch (random.nextInt(5)) {
                case 0 -> {
                    addValidSnapshot(NODE, PLAN, sequence, false);
                    addValidSnapshot(NODE, PLAN, sequence + 1, false);
                    nextSequence = Math.max(nextSequence, sequence + 2);
                }
                case 1 -> addValidSnapshot(NODE, PLAN, sequence, true);
                case 2 -> addTraffic(NODE, CONNECTION, PLAN);
                case 3 -> addInvalidSnapshot();
                default -> addValidSnapshot(NODE, PLAN, sequence, false);
            }
        }

        private void addNoise() {
            long sequence = takeSequence();
            switch (random.nextInt(9)) {
                case 0 -> addValidSnapshot(NODE, PLAN, sequence, random.nextBoolean());
                case 1 -> addValidSnapshot("other-node", PLAN, sequence, random.nextBoolean());
                case 2 -> addValidSnapshot(NODE, "other-plan", sequence, random.nextBoolean());
                case 3 -> addTraffic(NODE, CONNECTION, PLAN);
                case 4 -> addTraffic(NODE, "other-connection", PLAN);
                case 5 -> addTraffic("other-node", CONNECTION, PLAN);
                case 6, 7 -> addInvalidSnapshot();
                default -> addMalformedTraffic();
            }
        }

        private void addValidSnapshot(String nodeId, String routingPlanId, long sequence, boolean containsConnection) {
            int chunkCount = random.nextInt(3) + 1;
            int connectionChunk = containsConnection ? random.nextInt(chunkCount) : -1;
            long firstOffset = nextOffset;
            for (int chunkIndex = 0; chunkIndex < chunkCount; ++chunkIndex) {
                var connections = chunkIndex == connectionChunk
                    ? List.of(CONNECTION)
                    : List.<String>of();
                records.add(snapshot(
                    nextOffset++,
                    nodeId,
                    PARTITION,
                    routingPlanId,
                    sequence,
                    chunkIndex,
                    chunkCount,
                    sequence * 1_000,
                    connections
                ));
            }
            validSnapshots.add(new ValidSnapshot(
                nodeId,
                PARTITION,
                routingPlanId,
                sequence,
                firstOffset,
                nextOffset - 1,
                containsConnection
            ));
        }

        private void addInvalidSnapshot() {
            includedMalformedSnapshot = true;
            long sequence = takeSequence();
            int mode = random.nextInt(6);
            switch (mode) {
                case 0 -> records.add(snapshot(
                    nextOffset++,
                    NODE,
                    PARTITION,
                    PLAN,
                    sequence,
                    0,
                    2,
                    1_000,
                    List.of()
                ));
                case 1 -> {
                    records.add(snapshot(
                        nextOffset++,
                        NODE,
                        PARTITION,
                        PLAN,
                        sequence,
                        1,
                        2,
                        1_000,
                        List.of()
                    ));
                    records.add(snapshot(
                        nextOffset++,
                        NODE,
                        PARTITION,
                        PLAN,
                        sequence,
                        0,
                        2,
                        1_000,
                        List.of()
                    ));
                }
                case 2 -> {
                    records.add(snapshot(
                        nextOffset++,
                        NODE,
                        PARTITION,
                        PLAN,
                        sequence,
                        0,
                        2,
                        1_000,
                        List.of()
                    ));
                    records.add(snapshot(
                        nextOffset++,
                        NODE,
                        PARTITION,
                        PLAN,
                        sequence,
                        0,
                        2,
                        1_000,
                        List.of()
                    ));
                }
                case 3 -> {
                    records.add(snapshot(
                        nextOffset++,
                        NODE,
                        PARTITION,
                        PLAN,
                        sequence,
                        0,
                        2,
                        1_000,
                        List.of()
                    ));
                    records.add(snapshot(
                        nextOffset++,
                        NODE,
                        PARTITION,
                        PLAN,
                        sequence,
                        1,
                        2,
                        1_001,
                        List.of()
                    ));
                }
                case 4 -> records.add(snapshot(
                    nextOffset++,
                    NODE,
                    PARTITION,
                    PLAN,
                    sequence,
                    0,
                    100_001,
                    1_000,
                    List.of()
                ));
                default -> {
                    var malformed = new ConsumerRecord<String, byte[]>(
                        TOPIC,
                        PARTITION,
                        nextOffset++,
                        "snapshot",
                        new byte[] { (byte) 0x80 }
                    );
                    malformed.headers().add(
                        CaptureRecordTypes.RECORD_TYPE_HEADER,
                        CaptureRecordTypes.LIVENESS_RECORD_TYPE.getBytes(StandardCharsets.UTF_8)
                    );
                    records.add(malformed);
                }
            }
        }

        private void addTraffic(String nodeId, String connectionId, String routingPlanId) {
            long offset = nextOffset++;
            records.add(traffic(offset, nodeId, connectionId, PARTITION, routingPlanId));
            if (nodeId.equals(NODE) && connectionId.equals(CONNECTION) && routingPlanId.equals(PLAN)) {
                trafficFollowUps.add(offset);
            }
        }

        private void addMalformedTraffic() {
            records.add(new ConsumerRecord<>(
                TOPIC,
                PARTITION,
                nextOffset++,
                "traffic",
                new byte[] { (byte) 0x80 }
            ));
        }

        private Scenario build() {
            return new Scenario(
                List.copyOf(records),
                List.copyOf(validSnapshots),
                List.copyOf(trafficFollowUps),
                random.nextInt(10) != 0,
                random.nextBoolean(),
                includedMalformedSnapshot
            );
        }

        private long takeSequence() {
            long sequence = nextSequence;
            nextSequence += random.nextInt(3) + 1;
            return sequence;
        }
    }

    private static final class ObservedOutcomes {
        private int followUps;
        private int confirmedAbsent;
        private int inconclusive;
        private int malformedSnapshots;
        private int generationChanges;

        private void record(Scenario scenario, ScanEvidence evidence) {
            if (evidence instanceof ScanEvidence.FollowUpPresent) {
                followUps++;
            } else if (evidence instanceof ScanEvidence.ConfirmedAbsent) {
                confirmedAbsent++;
            } else if (evidence instanceof ScanEvidence.Inconclusive) {
                inconclusive++;
            }
            if (scenario.includedMalformedSnapshot()) {
                malformedSnapshots++;
            }
            if (!scenario.stableGeneration()) {
                generationChanges++;
            }
        }

        private void assertCoverage() {
            Assertions.assertTrue(followUps > 0, "generator did not exercise follow-up evidence");
            Assertions.assertTrue(confirmedAbsent > 0, "generator did not exercise confirmed absence");
            Assertions.assertTrue(inconclusive > 0, "generator did not exercise inconclusive evidence");
            Assertions.assertTrue(malformedSnapshots > 0, "generator did not exercise malformed snapshots");
            Assertions.assertTrue(generationChanges > 0, "generator did not exercise generation changes");
        }
    }

    private static ConsumerRecord<String, byte[]> snapshot(
        long offset,
        String nodeId,
        int partitionStamp,
        String routingPlanId,
        long sequence,
        int chunkIndex,
        int chunkCount,
        long emittedAtMillis,
        List<String> openConnections
    ) {
        var builder = ProxyLivenessSnapshotChunk.newBuilder()
            .setNodeId(nodeId)
            .setPartition(partitionStamp)
            .setRoutingPlanId(routingPlanId)
            .setSnapshotSequence(sequence)
            .setChunkIndex(chunkIndex)
            .setChunkCount(chunkCount)
            .setEmittedAtMillis(emittedAtMillis);
        openConnections.forEach(connection ->
            builder.addOpenConnections(ByteString.copyFromUtf8(connection)));
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

    private static ConsumerRecord<String, byte[]> traffic(
        long offset,
        String nodeId,
        String connectionId,
        int partitionStamp,
        String routingPlanId
    ) {
        var stream = TrafficStream.newBuilder()
            .setNodeId(nodeId)
            .setConnectionId(connectionId)
            .setPartition(partitionStamp)
            .setRoutingPlanId(routingPlanId)
            .build();
        return new ConsumerRecord<>(TOPIC, PARTITION, offset, "traffic", stream.toByteArray());
    }

    private static ProxyLivenessSnapshotChunk snapshotPayload(ConsumerRecord<String, byte[]> record) {
        try {
            return ProxyLivenessSnapshotChunk.parseFrom(record.value());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static TrafficStream trafficPayload(ConsumerRecord<String, byte[]> record) {
        try {
            return TrafficStream.parseFrom(record.value());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
