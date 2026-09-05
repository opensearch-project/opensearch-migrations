package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.traffic.source.AbsenceProof;
import org.opensearch.migrations.replay.traffic.source.CompleteSnapshotSpan;
import org.opensearch.migrations.replay.traffic.source.FollowUpRequirement;
import org.opensearch.migrations.replay.traffic.source.ScanEvidence;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.NonNull;
import org.apache.kafka.clients.consumer.ConsumerRecord;

final class KafkaLivenessScanner {
    private static final int MAXIMUM_CHUNKS_PER_SNAPSHOT = 100_000;

    static final class Candidate {
        private final SourcePartitionKey partition;
        private final SourceConnectionKey connection;
        private final String routingPlanId;
        private final long lastReplayedOffset;
        private final FollowUpRequirement requirement;

        Candidate(
            @NonNull SourcePartitionKey partition,
            @NonNull SourceConnectionKey connection,
            @NonNull String routingPlanId,
            long lastReplayedOffset,
            @NonNull FollowUpRequirement requirement
        ) {
            this.partition = partition;
            this.connection = connection;
            this.routingPlanId = routingPlanId;
            this.lastReplayedOffset = lastReplayedOffset;
            this.requirement = requirement;
        }

        SourcePartitionKey partition() {
            return partition;
        }

        SourceConnectionKey connection() {
            return connection;
        }

        String routingPlanId() {
            return routingPlanId;
        }

        long lastReplayedOffset() {
            return lastReplayedOffset;
        }

        FollowUpRequirement requirement() {
            return requirement;
        }
    }

    private static final class SnapshotKey {
        private final String nodeId;
        private final int partition;
        private final String routingPlanId;
        private final long sequence;

        private SnapshotKey(String nodeId, int partition, String routingPlanId, long sequence) {
            this.nodeId = nodeId;
            this.partition = partition;
            this.routingPlanId = routingPlanId;
            this.sequence = sequence;
        }

        private String nodeId() {
            return nodeId;
        }

        private int partition() {
            return partition;
        }

        private String routingPlanId() {
            return routingPlanId;
        }

        private long sequence() {
            return sequence;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SnapshotKey)) {
                return false;
            }
            var that = (SnapshotKey) other;
            return partition == that.partition
                && sequence == that.sequence
                && nodeId.equals(that.nodeId)
                && routingPlanId.equals(that.routingPlanId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(nodeId, partition, routingPlanId, sequence);
        }
    }

    private static final class CompleteSnapshot {
        private final SnapshotKey key;
        private final CompleteSnapshotSpan span;
        private final Set<String> openConnections;

        private CompleteSnapshot(
            SnapshotKey key,
            CompleteSnapshotSpan span,
            Set<String> openConnections
        ) {
            this.key = key;
            this.span = span;
            this.openConnections = openConnections;
        }

        private SnapshotKey key() {
            return key;
        }

        private CompleteSnapshotSpan span() {
            return span;
        }

        private Set<String> openConnections() {
            return openConnections;
        }
    }

    List<ScanEvidence> evaluate(
        Collection<Candidate> candidates,
        TrackingKafkaConsumer.ScanCycle cycle
    ) {
        if (!cycle.stableGeneration()) {
            return generationChangedVerdicts(candidates);
        }
        var candidateByConnection = new HashMap<SourceConnectionKey, Candidate>();
        candidates.forEach(candidate -> candidateByConnection.put(candidate.connection(), candidate));
        var followUps = new HashMap<SourceConnectionKey, Long>();
        var partialSnapshots = new HashMap<SnapshotKey, PartialSnapshot>();

        for (var kafkaRecord : cycle.records()) {
            if (isLivenessRecord(kafkaRecord)) {
                addSnapshotRecord(kafkaRecord, partialSnapshots);
            } else {
                addTrafficFollowUp(kafkaRecord, candidateByConnection, followUps);
            }
        }

        var completeSnapshots = completeSnapshots(partialSnapshots);

        var verdicts = new ArrayList<ScanEvidence>(candidates.size());
        for (var candidate : candidates) {
            verdicts.add(evaluateCandidate(candidate, followUps, completeSnapshots, cycle.exhaustedBudget()));
        }
        return List.copyOf(verdicts);
    }

    private List<ScanEvidence> generationChangedVerdicts(Collection<Candidate> candidates) {
        return candidates.stream()
            .map(candidate -> new ScanEvidence.Inconclusive(
                candidate.partition(),
                candidate.connection(),
                "Kafka assignment or generation changed during scan"
            ))
            .map(ScanEvidence.class::cast)
            .toList();
    }

    private List<CompleteSnapshot> completeSnapshots(Map<SnapshotKey, PartialSnapshot> partialSnapshots) {
        return partialSnapshots.values()
            .stream()
            .filter(PartialSnapshot::isComplete)
            .map(PartialSnapshot::complete)
            .sorted(Comparator.comparingLong(snapshot -> snapshot.span().firstOffset()))
            .toList();
    }

    private ScanEvidence evaluateCandidate(
        Candidate candidate,
        Map<SourceConnectionKey, Long> followUps,
        List<CompleteSnapshot> completeSnapshots,
        boolean exhaustedBudget
    ) {
        var followUpOffset = followUps.get(candidate.connection());
        if (followUpOffset != null) {
            return followUpPresent(candidate, followUpOffset);
        }
        var relevantSnapshots = relevantSnapshots(candidate, completeSnapshots);
        var containing = relevantSnapshots.stream()
            .filter(snapshot -> snapshot.openConnections().contains(candidate.connection().connectionId()))
            .findFirst();
        if (containing.isPresent()) {
            return followUpPresent(candidate, containing.get().span().lastOffset());
        }
        var proof = findOmissionProof(candidate, relevantSnapshots);
        if (proof != null) {
            return new ScanEvidence.ConfirmedAbsent(
                candidate.partition(),
                candidate.connection(),
                candidate.requirement(),
                proof
            );
        }
        return new ScanEvidence.Inconclusive(
            candidate.partition(),
            candidate.connection(),
            exhaustedBudget
                ? "Scan budget ended before two complete omission snapshots"
                : "Two complete consecutive omission snapshots were not available"
        );
    }

    private ScanEvidence.FollowUpPresent followUpPresent(Candidate candidate, long offset) {
        return new ScanEvidence.FollowUpPresent(
            candidate.partition(),
            candidate.connection(),
            offset
        );
    }

    private List<CompleteSnapshot> relevantSnapshots(
        Candidate candidate,
        List<CompleteSnapshot> completeSnapshots
    ) {
        return completeSnapshots.stream()
            .filter(snapshot -> snapshot.key().nodeId().equals(candidate.connection().nodeId()))
            .filter(snapshot -> snapshot.key().partition() == candidate.partition().partition())
            .filter(snapshot -> snapshot.key().routingPlanId().equals(candidate.routingPlanId()))
            .filter(snapshot -> snapshot.span().firstOffset() > candidate.lastReplayedOffset())
            .toList();
    }

    private AbsenceProof.LivenessOmission findOmissionProof(
        Candidate candidate,
        List<CompleteSnapshot> snapshots
    ) {
        for (int i = 1; i < snapshots.size(); ++i) {
            var first = snapshots.get(i - 1);
            var second = snapshots.get(i);
            if (isOmissionProof(candidate, first, second)) {
                return new AbsenceProof.LivenessOmission(
                    candidate.connection().nodeId(),
                    candidate.partition().partition(),
                    first.span(),
                    second.span(),
                    candidate.lastReplayedOffset()
                );
            }
        }
        return null;
    }

    private boolean isOmissionProof(
        Candidate candidate,
        CompleteSnapshot first,
        CompleteSnapshot second
    ) {
        var connectionId = candidate.connection().connectionId();
        return second.key().sequence() == first.key().sequence() + 1
            && !first.openConnections().contains(connectionId)
            && !second.openConnections().contains(connectionId)
            && first.span().lastOffset() < second.span().firstOffset();
    }

    private void addTrafficFollowUp(
        ConsumerRecord<String, byte[]> kafkaRecord,
        Map<SourceConnectionKey, Candidate> candidateByConnection,
        Map<SourceConnectionKey, Long> followUps
    ) {
        final TrafficStream stream;
        try {
            stream = TrafficStream.parseFrom(kafkaRecord.value());
        } catch (InvalidProtocolBufferException e) {
            return;
        }
        if (!stream.hasPartition() || !stream.hasRoutingPlanId()) {
            return;
        }
        validateTrafficStamp(kafkaRecord, stream);
        var connection = new SourceConnectionKey(stream.getNodeId(), stream.getConnectionId());
        var candidate = candidateByConnection.get(connection);
        if (candidate != null
            && candidate.partition().partition() == kafkaRecord.partition()
            && candidate.routingPlanId().equals(stream.getRoutingPlanId())
            && kafkaRecord.offset() > candidate.lastReplayedOffset()) {
            followUps.merge(connection, kafkaRecord.offset(), Math::min);
        }
    }

    private void addSnapshotRecord(
        ConsumerRecord<String, byte[]> kafkaRecord,
        Map<SnapshotKey, PartialSnapshot> partialSnapshots
    ) {
        final ProxyLivenessSnapshotChunk chunk;
        try {
            chunk = ProxyLivenessSnapshotChunk.parseFrom(kafkaRecord.value());
        } catch (InvalidProtocolBufferException e) {
            return;
        }
        if (chunk.getPartition() != kafkaRecord.partition()) {
            throw new IllegalStateException(
                "Liveness snapshot partition stamp "
                    + chunk.getPartition()
                    + " does not match consumed partition "
                    + kafkaRecord.partition()
            );
        }
        if (chunk.getRoutingPlanId().isBlank()) {
            throw new IllegalStateException("Liveness snapshot is missing its routing-plan identity");
        }
        var key = new SnapshotKey(
            chunk.getNodeId(),
            chunk.getPartition(),
            chunk.getRoutingPlanId(),
            chunk.getSnapshotSequence()
        );
        partialSnapshots.computeIfAbsent(key, ignored -> new PartialSnapshot(key, chunk))
            .add(kafkaRecord.offset(), chunk);
    }

    static void validateTrafficStamp(ConsumerRecord<String, byte[]> kafkaRecord, TrafficStream stream) {
        if (stream.getPartition() != kafkaRecord.partition()) {
            throw new IllegalStateException(
                "Traffic partition stamp "
                    + stream.getPartition()
                    + " does not match consumed partition "
                    + kafkaRecord.partition()
            );
        }
        if (stream.getRoutingPlanId().isBlank()) {
            throw new IllegalStateException("Stamped traffic record is missing its routing-plan identity");
        }
    }

    static boolean isLivenessRecord(ConsumerRecord<String, byte[]> kafkaRecord) {
        for (var header : kafkaRecord.headers()) {
            if (CaptureRecordTypes.RECORD_TYPE_HEADER.equals(header.key())
                && CaptureRecordTypes.LIVENESS_RECORD_TYPE.equals(
                    new String(header.value(), StandardCharsets.UTF_8)
                )) {
                return true;
            }
        }
        return false;
    }

    private static final class PartialSnapshot {
        private final SnapshotKey key;
        private final int chunkCount;
        private final long emittedAtMillis;
        private final Map<Integer, ProxyLivenessSnapshotChunk> chunks = new HashMap<>();
        private long firstOffset = Long.MAX_VALUE;
        private long lastOffset = Long.MIN_VALUE;
        private int nextChunkIndex;
        private boolean invalid;

        private PartialSnapshot(SnapshotKey key, ProxyLivenessSnapshotChunk firstChunk) {
            this.key = key;
            this.chunkCount = firstChunk.getChunkCount();
            this.emittedAtMillis = firstChunk.getEmittedAtMillis();
            invalid = chunkCount <= 0 || chunkCount > MAXIMUM_CHUNKS_PER_SNAPSHOT;
        }

        private void add(long offset, ProxyLivenessSnapshotChunk chunk) {
            if (invalid
                || chunk.getChunkCount() != chunkCount
                || chunk.getEmittedAtMillis() != emittedAtMillis
                || chunk.getChunkIndex() < 0
                || chunk.getChunkIndex() >= chunkCount
                || chunk.getChunkIndex() != nextChunkIndex
                || (nextChunkIndex > 0 && offset != lastOffset + 1)
                || chunks.putIfAbsent(chunk.getChunkIndex(), chunk) != null) {
                invalid = true;
                return;
            }
            firstOffset = Math.min(firstOffset, offset);
            lastOffset = Math.max(lastOffset, offset);
            nextChunkIndex++;
        }

        private boolean isComplete() {
            return !invalid && chunks.size() == chunkCount;
        }

        private CompleteSnapshot complete() {
            var openConnections = new HashSet<String>();
            for (int i = 0; i < chunkCount; ++i) {
                var chunk = chunks.get(i);
                if (chunk == null) {
                    throw new IllegalStateException("Snapshot declared complete with a missing chunk");
                }
                chunk.getOpenConnectionsList()
                    .forEach(connection -> openConnections.add(connection.toStringUtf8()));
            }
            return new CompleteSnapshot(
                key,
                new CompleteSnapshotSpan(key.sequence(), firstOffset, lastOffset, key.routingPlanId()),
                Set.copyOf(openConnections)
            );
        }
    }
}
