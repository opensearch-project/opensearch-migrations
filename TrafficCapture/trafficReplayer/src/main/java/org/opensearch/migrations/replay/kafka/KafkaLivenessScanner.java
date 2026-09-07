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

/**
 * Tracks what the capture proxies say is still open, and answers whether a given connection has been
 * proved dead.
 *
 * <p>Records are handed to {@link #ingest} in offset order and the evidence they carry is <em>retained</em>
 * across calls.  A complete manifest may span scan batches, so an evaluator that rebuilt its state from one
 * batch could silently lose the chunks needed to prove an omission.  Retaining state makes manifest
 * completion independent of how scan reads are chunked.
 *
 * <p>Because state is retained, {@link #ingest} must tolerate seeing the same record twice; the current
 * scan-ahead caller re-reads the same window on every cycle.  Offsets are monotonic per partition, so
 * per-partition high-water marks make re-delivery free rather than corrupting chunk reassembly.
 *
 * <p>Not thread-safe: it is driven from the single source-intake thread.
 */
final class KafkaLivenessScanner {
    private static final int MAXIMUM_CHUNKS_PER_SNAPSHOT = 100_000;
    /** Reassembly and recent-history state for one (node, partition, routing plan) snapshot stream. */
    private static final class SnapshotStream {
        private final Map<Long, PartialSnapshot> partialsBySequence = new HashMap<>();
        private final java.util.NavigableMap<Long, CompleteSnapshot> completeBySequence = new java.util.TreeMap<>();

        private void add(long offset, SnapshotKey key, ProxyLivenessSnapshotChunk chunk) {
            if (completeBySequence.containsKey(key.sequence())) {
                return;
            }
            var partial = partialsBySequence.computeIfAbsent(
                key.sequence(),
                ignored -> new PartialSnapshot(key, chunk)
            );
            partial.add(offset, chunk);
            if (partial.isComplete()) {
                partialsBySequence.remove(key.sequence());
                completeBySequence.put(key.sequence(), partial.complete());
            }
        }

        /**
         * Drops history that no candidate can still use.  Relevance requires a snapshot to begin after the
         * candidate's last replayed record, so a snapshot entirely at or below the furthest-along replay
         * position is unreachable for every candidate, present or future.  Bounding on replay progress
         * rather than on a fixed count is what keeps the retained window equal to the un-replayed working
         * set -- and it keeps the aliveness rule intact, since a "connection is listed here" sighting stays
         * visible for exactly as long as a proof that would contradict it.
         */
        private void pruneThroughReplayedOffset(long replayedThroughOffset) {
            completeBySequence.values()
                .removeIf(snapshot -> snapshot.span().firstOffset() <= replayedThroughOffset);
            partialsBySequence.values()
                .removeIf(partial -> partial.lastOffset <= replayedThroughOffset);
        }
    }

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

    /** Identity of one snapshot stream: a single proxy's snapshots for a single partition under one plan. */
    private record SnapshotStreamKey(String nodeId, int partition, String routingPlanId) {}

    /**
     * A connection's traffic scoped to where it was routed.  The partition and routing plan belong in the
     * key: traffic for the same connection under a different plan or partition says nothing about this
     * candidate, since every proof rests on offset ordering within one partition under one plan.
     */
    private record TrafficKey(SourceConnectionKey connection, int partition, String routingPlanId) {}

    private final Map<SnapshotStreamKey, SnapshotStream> snapshotStreams = new HashMap<>();
    private final Map<TrafficKey, Long> latestTrafficOffsetByConnection = new HashMap<>();
    private final Map<Integer, Long> highestIngestedOffsetByPartition = new HashMap<>();

    List<ScanEvidence> evaluate(
        Collection<Candidate> candidates,
        TrackingKafkaConsumer.ScanCycle cycle
    ) {
        if (!cycle.stableGeneration()) {
            return generationChangedVerdicts(candidates);
        }
        cycle.records().forEach(this::ingest);

        var verdicts = new ArrayList<ScanEvidence>(candidates.size());
        for (var candidate : candidates) {
            verdicts.add(evaluateCandidate(candidate));
        }
        releaseEvidenceBehindReplay(candidates);
        return List.copyOf(verdicts);
    }

    /**
     * Drops retained evidence that replay has already moved past.  Pruning happens after the verdicts so a
     * candidate is never evaluated against a window this call just trimmed.  A partition with no candidates
     * is left alone: it has nothing outstanding, so its streams are already empty or about to be released by
     * {@link #forgetPartition}.
     */
    private void releaseEvidenceBehindReplay(Collection<Candidate> candidates) {
        var replayedThroughByPartition = new HashMap<Integer, Long>();
        for (var candidate : candidates) {
            replayedThroughByPartition.merge(
                candidate.partition().partition(),
                candidate.lastReplayedOffset(),
                Math::min
            );
        }
        snapshotStreams.forEach((streamKey, stream) -> {
            var replayedThrough = replayedThroughByPartition.get(streamKey.partition());
            if (replayedThrough != null) {
                stream.pruneThroughReplayedOffset(replayedThrough);
            }
        });
    }

    /**
     * Folds one record into the retained evidence.  Records must arrive in per-partition offset order, which
     * is what Kafka delivers; a record at or below the partition's high-water mark is a re-read and is
     * skipped so that repeated scans of the same window are idempotent.
     */
    void ingest(ConsumerRecord<String, byte[]> kafkaRecord) {
        var previousHighest = highestIngestedOffsetByPartition.get(kafkaRecord.partition());
        // Only the state mutation is skipped for a re-read, never the stamp validation.  Those checks guard
        // the one assumption whose silent failure would commit live data -- that a connection's records and
        // its node's snapshots share a partition -- so they must not become order-dependent.
        var alreadySeen = previousHighest != null && kafkaRecord.offset() <= previousHighest;
        if (!alreadySeen) {
            highestIngestedOffsetByPartition.put(kafkaRecord.partition(), kafkaRecord.offset());
        }
        if (isLivenessRecord(kafkaRecord)) {
            addSnapshotRecord(kafkaRecord, alreadySeen);
        } else {
            addTrafficFollowUp(kafkaRecord, alreadySeen);
        }
    }

    /**
     * Releases the evidence held for a connection that has reached a terminal decision.  Retained state is
     * otherwise unbounded in the number of connections ever seen.
     */
    void forget(@NonNull SourceConnectionKey connection) {
        latestTrafficOffsetByConnection.keySet().removeIf(key -> key.connection().equals(connection));
    }

    /**
     * Discards everything retained for a partition.  Offset ordering -- which every proof rests on -- is
     * only meaningful within one assignment of one partition, so a revoke or generation change invalidates
     * the retained evidence rather than merely interrupting it.
     */
    void forgetPartition(int partition) {
        highestIngestedOffsetByPartition.remove(partition);
        snapshotStreams.keySet().removeIf(key -> key.partition() == partition);
        latestTrafficOffsetByConnection.keySet().removeIf(key -> key.partition() == partition);
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

    private ScanEvidence evaluateCandidate(Candidate candidate) {
        var followUpOffset = latestTrafficOffsetByConnection.get(new TrafficKey(
            candidate.connection(),
            candidate.partition().partition(),
            candidate.routingPlanId()
        ));
        if (followUpOffset != null && followUpOffset > candidate.lastReplayedOffset()) {
            return followUpPresent(candidate, followUpOffset);
        }
        var relevantSnapshots = relevantSnapshots(candidate);
        if (!relevantSnapshots.isEmpty()) {
            var latest = relevantSnapshots.get(relevantSnapshots.size() - 1);
            if (latest.openConnections().contains(candidate.connection().connectionId())) {
                return followUpPresent(candidate, latest.span().lastOffset());
            }
            return new ScanEvidence.ConfirmedAbsent(
                candidate.partition(),
                candidate.connection(),
                candidate.requirement(),
                new AbsenceProof.LivenessOmission(
                    candidate.connection().nodeId(),
                    candidate.partition().partition(),
                    latest.span(),
                    candidate.lastReplayedOffset()
                )
            );
        }
        return new ScanEvidence.Inconclusive(
            candidate.partition(),
            candidate.connection(),
            "A complete liveness manifest after the connection's last record has not arrived yet"
        );
    }

    private ScanEvidence.FollowUpPresent followUpPresent(Candidate candidate, long offset) {
        return new ScanEvidence.FollowUpPresent(
            candidate.partition(),
            candidate.connection(),
            offset
        );
    }

    private List<CompleteSnapshot> relevantSnapshots(Candidate candidate) {
        var stream = snapshotStreams.get(new SnapshotStreamKey(
            candidate.connection().nodeId(),
            candidate.partition().partition(),
            candidate.routingPlanId()
        ));
        if (stream == null) {
            return List.of();
        }
        return stream.completeBySequence.values()
            .stream()
            .filter(snapshot -> snapshot.span().firstOffset() > candidate.lastReplayedOffset())
            .sorted(Comparator.comparingLong(snapshot -> snapshot.span().firstOffset()))
            .toList();
    }

    /**
     * Records that a connection was still producing traffic at this offset.  Unlike the per-cycle version
     * this cannot filter by candidate: retained state has to answer for connections that only become
     * candidates on a later cycle, by which time the record is long gone.
     */
    private void addTrafficFollowUp(ConsumerRecord<String, byte[]> kafkaRecord, boolean alreadySeen) {
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
        if (alreadySeen) {
            return;
        }
        var key = new TrafficKey(
            new SourceConnectionKey(stream.getNodeId(), stream.getConnectionId()),
            kafkaRecord.partition(),
            stream.getRoutingPlanId()
        );
        latestTrafficOffsetByConnection.merge(key, kafkaRecord.offset(), Math::max);
    }

    private void addSnapshotRecord(ConsumerRecord<String, byte[]> kafkaRecord, boolean alreadySeen) {
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
        if (alreadySeen) {
            return;
        }
        var key = new SnapshotKey(
            chunk.getNodeId(),
            chunk.getPartition(),
            chunk.getRoutingPlanId(),
            chunk.getSnapshotSequence()
        );
        snapshotStreams.computeIfAbsent(
            new SnapshotStreamKey(key.nodeId(), key.partition(), key.routingPlanId()),
            ignored -> new SnapshotStream()
        ).add(kafkaRecord.offset(), key, chunk);
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
