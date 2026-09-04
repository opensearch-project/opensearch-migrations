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

    record Candidate(
        @NonNull SourcePartitionKey partition,
        @NonNull SourceConnectionKey connection,
        @NonNull String routingPlanId,
        long lastReplayedOffset,
        @NonNull FollowUpRequirement requirement
    ) {}

    private record SnapshotKey(String nodeId, int partition, String routingPlanId, long sequence) {}

    private record CompleteSnapshot(
        SnapshotKey key,
        CompleteSnapshotSpan span,
        Set<String> openConnections
    ) {}

    List<ScanEvidence> evaluate(
        Collection<Candidate> candidates,
        TrackingKafkaConsumer.ScanCycle cycle
    ) {
        if (!cycle.stableGeneration()) {
            return candidates.stream()
                .map(candidate -> new ScanEvidence.Inconclusive(
                    candidate.partition(),
                    candidate.connection(),
                    "Kafka assignment or generation changed during scan"
                ))
                .map(ScanEvidence.class::cast)
                .toList();
        }
        var candidateByConnection = new HashMap<SourceConnectionKey, Candidate>();
        candidates.forEach(candidate -> candidateByConnection.put(candidate.connection(), candidate));
        var followUps = new HashMap<SourceConnectionKey, Long>();
        var partialSnapshots = new HashMap<SnapshotKey, PartialSnapshot>();

        for (var record : cycle.records()) {
            if (isLivenessRecord(record)) {
                addSnapshotRecord(record, partialSnapshots);
            } else {
                addTrafficFollowUp(record, candidateByConnection, followUps);
            }
        }

        var completeSnapshots = partialSnapshots.values()
            .stream()
            .filter(PartialSnapshot::isComplete)
            .map(PartialSnapshot::complete)
            .sorted(Comparator.comparingLong(snapshot -> snapshot.span().firstOffset()))
            .toList();

        var verdicts = new ArrayList<ScanEvidence>(candidates.size());
        for (var candidate : candidates) {
            var followUpOffset = followUps.get(candidate.connection());
            if (followUpOffset != null) {
                verdicts.add(new ScanEvidence.FollowUpPresent(
                    candidate.partition(),
                    candidate.connection(),
                    followUpOffset
                ));
                continue;
            }
            var relevantSnapshots = completeSnapshots.stream()
                .filter(snapshot -> snapshot.key().nodeId().equals(candidate.connection().nodeId()))
                .filter(snapshot -> snapshot.key().partition() == candidate.partition().partition())
                .filter(snapshot -> snapshot.key().routingPlanId().equals(candidate.routingPlanId()))
                .filter(snapshot -> snapshot.span().firstOffset() > candidate.lastReplayedOffset())
                .toList();
            var containing = relevantSnapshots.stream()
                .filter(snapshot -> snapshot.openConnections().contains(candidate.connection().connectionId()))
                .findFirst();
            if (containing.isPresent()) {
                verdicts.add(new ScanEvidence.FollowUpPresent(
                    candidate.partition(),
                    candidate.connection(),
                    containing.get().span().lastOffset()
                ));
                continue;
            }
            var proof = findOmissionProof(candidate, relevantSnapshots);
            if (proof != null) {
                verdicts.add(new ScanEvidence.ConfirmedAbsent(
                    candidate.partition(),
                    candidate.connection(),
                    candidate.requirement(),
                    proof
                ));
            } else {
                verdicts.add(new ScanEvidence.Inconclusive(
                    candidate.partition(),
                    candidate.connection(),
                    cycle.exhaustedBudget()
                        ? "Scan budget ended before two complete omission snapshots"
                        : "Two complete consecutive omission snapshots were not available"
                ));
            }
        }
        return List.copyOf(verdicts);
    }

    private AbsenceProof.LivenessOmission findOmissionProof(
        Candidate candidate,
        List<CompleteSnapshot> snapshots
    ) {
        for (int i = 1; i < snapshots.size(); ++i) {
            var first = snapshots.get(i - 1);
            var second = snapshots.get(i);
            if (second.key().sequence() != first.key().sequence() + 1) {
                continue;
            }
            if (first.openConnections().contains(candidate.connection().connectionId())
                || second.openConnections().contains(candidate.connection().connectionId())) {
                continue;
            }
            if (first.span().lastOffset() >= second.span().firstOffset()) {
                continue;
            }
            return new AbsenceProof.LivenessOmission(
                candidate.connection().nodeId(),
                candidate.partition().partition(),
                first.span(),
                second.span(),
                candidate.lastReplayedOffset()
            );
        }
        return null;
    }

    private void addTrafficFollowUp(
        ConsumerRecord<String, byte[]> record,
        Map<SourceConnectionKey, Candidate> candidateByConnection,
        Map<SourceConnectionKey, Long> followUps
    ) {
        final TrafficStream stream;
        try {
            stream = TrafficStream.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            return;
        }
        if (!stream.hasPartition() || !stream.hasRoutingPlanId()) {
            return;
        }
        validateTrafficStamp(record, stream);
        var connection = new SourceConnectionKey(stream.getNodeId(), stream.getConnectionId());
        var candidate = candidateByConnection.get(connection);
        if (candidate != null
            && candidate.partition().partition() == record.partition()
            && candidate.routingPlanId().equals(stream.getRoutingPlanId())
            && record.offset() > candidate.lastReplayedOffset()) {
            followUps.merge(connection, record.offset(), Math::min);
        }
    }

    private void addSnapshotRecord(
        ConsumerRecord<String, byte[]> record,
        Map<SnapshotKey, PartialSnapshot> partialSnapshots
    ) {
        final ProxyLivenessSnapshotChunk chunk;
        try {
            chunk = ProxyLivenessSnapshotChunk.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            return;
        }
        if (chunk.getPartition() != record.partition()) {
            throw new IllegalStateException(
                "Liveness snapshot partition stamp "
                    + chunk.getPartition()
                    + " does not match consumed partition "
                    + record.partition()
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
            .add(record.offset(), chunk);
    }

    static void validateTrafficStamp(ConsumerRecord<String, byte[]> record, TrafficStream stream) {
        if (stream.getPartition() != record.partition()) {
            throw new IllegalStateException(
                "Traffic partition stamp "
                    + stream.getPartition()
                    + " does not match consumed partition "
                    + record.partition()
            );
        }
        if (stream.getRoutingPlanId().isBlank()) {
            throw new IllegalStateException("Stamped traffic record is missing its routing-plan identity");
        }
    }

    static boolean isLivenessRecord(ConsumerRecord<String, byte[]> record) {
        for (var header : record.headers()) {
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
