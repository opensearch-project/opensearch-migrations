package org.opensearch.migrations.replay.kafka;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ITrafficSourceContexts KafkaCommitOffsetData KafkaRecordOwnershipBudget SourcePartitionLifecycleListener UnconfiguredSourcePartitionLifecycleListener . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.Utils;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.lifecycle.UnconfiguredSourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.KafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.slf4j.event.Level;

*/
// REBUILD-LIMBO-END(G2)
/**
 * This is a wrapper around Kafka's Consumer class that provides tracking of partitions
 * and their current (asynchronously 'committed' by the calling contexts) offsets.  It
 * manages those offsets and the 'active' set of records that have been rendered by this
 * consumer, when to pause a poll loop(), and how to deal with consumer rebalances.
 */
// REBUILD-LIMBO-START(G2)
/*
@Slf4j
public class TrackingKafkaConsumer implements ConsumerRebalanceListener {
    public interface Metrics {
        Metrics NO_OP = new Metrics() {};

        default void unresolvedObligationsChanged(int delta) {}

        default void stagedCommitPartitionsChanged(int delta) {}

        default void pendingAcknowledgementsChanged(int generation, int delta) {}

        default void commitAcknowledged(int generation, Duration latency) {}

        default void commitHeadObserved(int partition, int generation, Duration age) {}

        default void ownedRecordCapacityChanged(int recordDelta, long byteDelta) {}

        default void ownedRecordBudgetSaturated() {}
    }

    static final class ScanCycle {
        private final List<ConsumerRecord<String, byte[]>> records;
        private final boolean stableGeneration;
        private final boolean exhaustedBudget;

        ScanCycle(
            List<ConsumerRecord<String, byte[]>> records,
            boolean stableGeneration,
            boolean exhaustedBudget
        ) {
            this.records = records;
            this.stableGeneration = stableGeneration;
            this.exhaustedBudget = exhaustedBudget;
        }

        List<ConsumerRecord<String, byte[]>> records() {
            return records;
        }

        boolean stableGeneration() {
            return stableGeneration;
        }

        boolean exhaustedBudget() {
            return exhaustedBudget;
        }
    }

    private static final class ScanBaseline {
        private final Set<TopicPartition> assignment;
        private final Map<TopicPartition, Long> replayPositions;
        private final Map<Integer, Integer> generations;

        private ScanBaseline(
            Set<TopicPartition> assignment,
            Map<TopicPartition, Long> replayPositions,
            Map<Integer, Integer> generations
        ) {
            this.assignment = assignment;
            this.replayPositions = replayPositions;
            this.generations = generations;
        }
    }

    private record ObservedRecordMetadata(String connectionId, Instant acceptedAt) {}

*/
// REBUILD-LIMBO-END(G2)
    /**
     * The keep-alive should already be set to a fraction of the max poll timeout for
     * the consumer (done outside of this class).  The keep-alive tells this class how
     * often the caller should be interacting with touch() and poll() calls.  As such,
     * we want to set up a long enough poll to not overwhelm a broker or client with
     * many empty poll() message responses.  We also don't want to poll() for so long
     * when there aren't messages that there isn't enough time to commit messages,
     * which happens after we poll() (on the same thread, as per Consumer requirements).
     */
// REBUILD-LIMBO-START(G2)
/*
    public static final int POLL_TIMEOUT_KEEP_ALIVE_DIVISOR = 4;
    static final int UNBOUNDED_OWNED_RECORDS = Integer.MAX_VALUE;
    static final long UNBOUNDED_OWNED_BYTES = Long.MAX_VALUE;

    @NonNull
    private final RootReplayerContext globalContext;
    private final Consumer<String, byte[]> kafkaConsumer;

    final String topic;
    private final Clock clock;
*/
// REBUILD-LIMBO-END(G2)
    /**
     * This collection holds the definitive list, as per the rebalance callback, of the partitions
     * that are currently assigned to this consumer.  The objects are removed when partitions are
     * revoked and new objects are only created/inserted when they're assigned.  That means that
     * the generations of each observed-record queue may be different.
     */
// REBUILD-LIMBO-START(G2)
/*
    final Map<Integer, ObservedRecordCommitQueue> partitionToObservedRecordQueueMap;
    private final AtomicReference<Map<Integer, ObservedRecordCommitQueue.Snapshot>>
        observedRecordQueueSnapshots;
    private final Object commitDataLock = new Object();
    private final Map<KafkaRecordId, ObservedRecordMetadata> observedRecordMetadata;
    // loosening visibility so that a unit test can read this
    final Map<TopicPartition, OffsetAndMetadata> nextSetOfCommitsMap;
    private final Duration keepAliveInterval;
    private final Metrics metrics;
    private final KafkaRecordOwnershipBudget ownershipBudget;
    private final AtomicReference<Instant> lastTouchTimeRef;
    private final AtomicInteger consumerConnectionGeneration;
    private final AtomicInteger kafkaRecordsLeftToCommitEventually;
    private final AtomicBoolean kafkaRecordsReadyToCommit;
    // Heartbeat counters — reset each heartbeat cycle
    private final AtomicInteger pollsSinceLastHeartbeat = new AtomicInteger();
    private final AtomicInteger emptyPollsSinceLastHeartbeat = new AtomicInteger();
    private final AtomicInteger commitsSinceLastHeartbeat = new AtomicInteger();
    private static final org.slf4j.Logger heartbeatLogger =
        org.slf4j.LoggerFactory.getLogger("KafkaHeartbeat");
*/
// REBUILD-LIMBO-END(G2)
    /** Called with revoked partition numbers so the source layer can synthesize interrupted-close
     *  events for any active connections on them. Always invoked at the OLD generation (before
     *  any subsequent onPartitionsAssigned bumps it), matching the generation stamped on the
     *  source termination obligations. */
// REBUILD-LIMBO-START(G2)
/*
    private java.util.function.Consumer<Collection<SourcePartitionKey>> onPartitionsTrulyLostCallback =
        ignored -> {};
    private SourcePartitionLifecycleListener sourcePartitionLifecycleListener =
        new UnconfiguredSourcePartitionLifecycleListener();
*/
// REBUILD-LIMBO-END(G2)
    /** Set true by {@link #cleanupRevokedPartitions} when a rebalance callback fires inline
     *  during {@code kafkaConsumer.poll()}; cleared at the top of each poll. The post-poll
     *  recovery in {@link #safePollWithSwallowedRuntimeExceptions} reads this to decide
     *  whether to drop records and rewind. */
// REBUILD-LIMBO-START(G2)
/*
    private final AtomicBoolean rebalanceDuringPoll = new AtomicBoolean();

    public TrackingKafkaConsumer(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        String topic,
        Duration keepAliveInterval,
        Clock c
    ) {
        this(
            globalContext,
            kafkaConsumer,
            topic,
            keepAliveInterval,
            c,
            globalContext.getKafkaCommitStateMetrics(),
            UNBOUNDED_OWNED_RECORDS,
            UNBOUNDED_OWNED_BYTES
        );
    }

    TrackingKafkaConsumer(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        String topic,
        Duration keepAliveInterval,
        Clock c,
        @NonNull Metrics metrics
    ) {
        this(
            globalContext,
            kafkaConsumer,
            topic,
            keepAliveInterval,
            c,
            metrics,
            UNBOUNDED_OWNED_RECORDS,
            UNBOUNDED_OWNED_BYTES
        );
    }

    TrackingKafkaConsumer(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        String topic,
        Duration keepAliveInterval,
        Clock c,
        @NonNull Metrics metrics,
        int maximumOwnedRecords,
        long maximumOwnedBytes
    ) {
        this.globalContext = globalContext;
        this.kafkaConsumer = kafkaConsumer;
        this.topic = topic;
        this.clock = c;
        this.partitionToObservedRecordQueueMap = new HashMap<>();
        this.observedRecordQueueSnapshots = new AtomicReference<>(Map.of());
        this.observedRecordMetadata = new HashMap<>();
        this.nextSetOfCommitsMap = new HashMap<>();
        this.lastTouchTimeRef = new AtomicReference<>(Instant.EPOCH);
        consumerConnectionGeneration = new AtomicInteger();
        kafkaRecordsLeftToCommitEventually = new AtomicInteger();
        kafkaRecordsReadyToCommit = new AtomicBoolean();
        this.keepAliveInterval = keepAliveInterval;
        this.metrics = metrics;
        ownershipBudget = new KafkaRecordOwnershipBudget(
            maximumOwnedRecords,
            maximumOwnedBytes,
            metrics
        );
    }

    public int getConsumerConnectionGeneration() {
        return consumerConnectionGeneration.get();
    }

    public void setOnPartitionsTrulyLostCallback(
        java.util.function.Consumer<Collection<SourcePartitionKey>> callback
    ) {
        this.onPartitionsTrulyLostCallback = callback;
    }

    public void setSourcePartitionLifecycleListener(SourcePartitionLifecycleListener listener) {
        this.sourcePartitionLifecycleListener = listener;
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        // Fence/timeout: commits are impossible. Same cleanup as a revocation, just no commit attempt.
*/
// REBUILD-LIMBO-END(G2)
// REBUILD-LIMBO-ESCAPED-LINE(G2):         cleanupRevokedPartitions(partitions, /*attemptCommit=*/ false);
// REBUILD-LIMBO-START(G2)
/*
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
*/
// REBUILD-LIMBO-END(G2)
// REBUILD-LIMBO-ESCAPED-LINE(G2):         cleanupRevokedPartitions(partitions, /*attemptCommit=*/ true);
// REBUILD-LIMBO-START(G2)
/*
    }

*/
// REBUILD-LIMBO-END(G2)
    /**
     * Tear down per-partition state and fire {@link #onPartitionsTrulyLostCallback} at the OLD
     * generation (before any subsequent onPartitionsAssigned bumps it), so synthesized close
     * events match the {@code session.generation} stamped on channels opened during this gen.
     * The truly-lost callback runs OUTSIDE {@code commitDataLock} so it can freely touch the
     * source's concurrent collections.
     */
// REBUILD-LIMBO-START(G2)
/*
    private void cleanupRevokedPartitions(Collection<TopicPartition> partitions, boolean attemptCommit) {
        if (partitions.isEmpty()) {
            log.atDebug().setMessage("{} revoked/lost no partitions.").addArgument(this).log();
            return;
        }
        // If we're inside poll(), flag the post-poll recovery so it can drop records and rewind.
        rebalanceDuringPoll.set(true);
        new KafkaConsumerContexts.AsyncListeningContext(globalContext).onPartitionsRevoked(partitions);
        var revokedPartitions = new ArrayList<SourcePartitionKey>(partitions.size());
        synchronized (commitDataLock) {
            if (attemptCommit) {
                safeCommit(globalContext::createCommitContext);
            }
            partitions.forEach(p -> {
                var tp = new TopicPartition(topic, p.partition());
                var queue = partitionToObservedRecordQueueMap.get(p.partition());
                if (queue != null) {
                    metrics.unresolvedObligationsChanged(-queue.unfinishedCount());
                    ownershipBudget.releasePartition(
                        p.partition(),
                        Math.toIntExact(queue.generation().localSequence())
                    );
                    revokedPartitions.add(
                        new SourcePartitionKey(
                            topic,
                            p.partition(),
                            Math.toIntExact(queue.generation().localSequence())
                        )
                    );
                }
                if (nextSetOfCommitsMap.remove(tp) != null) {
                    metrics.stagedCommitPartitionsChanged(-1);
                }
                removeObservedMetadataForPartition(p.partition(), queue);
                partitionToObservedRecordQueueMap.remove(p.partition());
            });
            publishObservedRecordQueueSnapshots();
            recomputeRecordsLeftToCommit();
            kafkaRecordsReadyToCommit.set(!nextSetOfCommitsMap.values().isEmpty());
            log.atWarn().setMessage("{} partitions {} for {}")
                .addArgument(this)
                .addArgument(attemptCommit ? "revoked" : "lost (no commit attempted)")
                .addArgument(() -> partitions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .log();
        }
        var immutableRevokedPartitions = List.copyOf(revokedPartitions);
        sourcePartitionLifecycleListener.onRevoked(immutableRevokedPartitions);
        onPartitionsTrulyLostCallback.accept(immutableRevokedPartitions);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> newPartitions) {
        if (newPartitions.isEmpty()) {
            log.atInfo().setMessage("{} assigned no new partitions.").addArgument(this).log();
            return;
        }
        // We deliberately do NOT touch partitionsTouchedDuringPoll here. A pure assignment
        // doesn't invalidate any pre-rebalance buffer; a round-trip is already tagged on the
        // revoke side.
        new KafkaConsumerContexts.AsyncListeningContext(globalContext).onPartitionsAssigned(newPartitions);
        List<SourcePartitionKey> assignedPartitions;
        synchronized (commitDataLock) {
            consumerConnectionGeneration.incrementAndGet();
            newPartitions.forEach(
                p -> partitionToObservedRecordQueueMap.computeIfAbsent(
                    p.partition(),
                    ignored -> new ObservedRecordCommitQueue(
                        new PartitionGenerationId(
                            new TopicPartition(topic, p.partition()),
                            consumerConnectionGeneration.get()
                        )
                    )
                )
            );
            assignedPartitions = newPartitions.stream()
                .map(p -> {
                    var queue = partitionToObservedRecordQueueMap.get(p.partition());
                    return new SourcePartitionKey(
                        topic,
                        p.partition(),
                        Math.toIntExact(queue.generation().localSequence())
                    );
                })
                .toList();
            publishObservedRecordQueueSnapshots();
            log.atInfo()
                .setMessage("{} partitions added for {}")
                .addArgument(this)
                .addArgument(() -> newPartitions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .log();
        }
        sourcePartitionLifecycleListener.onAssigned(assignedPartitions);
    }

    public void close() {
        log.atInfo()
            .setMessage("Kafka consumer closing.  Committing (implicitly by Kafka's consumer): {}")
            .addArgument(this::nextCommitsToString)
            .log();
        try {
            kafkaConsumer.close();
        } finally {
            clearCommitTrackingState();
        }
    }

    private void clearCommitTrackingState() {
        synchronized (commitDataLock) {
            var unresolvedObligations = partitionToObservedRecordQueueMap.values()
                .stream()
                .mapToInt(ObservedRecordCommitQueue::size)
                .sum();
            if (unresolvedObligations != 0) {
                metrics.unresolvedObligationsChanged(-unresolvedObligations);
            }
            if (!nextSetOfCommitsMap.isEmpty()) {
                metrics.stagedCommitPartitionsChanged(-nextSetOfCommitsMap.size());
            }
            partitionToObservedRecordQueueMap.clear();
            observedRecordQueueSnapshots.set(Map.of());
            observedRecordMetadata.clear();
            nextSetOfCommitsMap.clear();
            ownershipBudget.clear();
            kafkaRecordsLeftToCommitEventually.set(0);
            kafkaRecordsReadyToCommit.set(false);
        }
    }

    public Optional<Instant> getNextRequiredTouch() {
        var lastTouchTime = lastTouchTimeRef.get();
        Optional<Instant> r;
        synchronized (commitDataLock) {
            if (!nextSetOfCommitsMap.isEmpty()) {
                r = Optional.of(clock.instant());
            } else if (observedRecordQueueSnapshots.get().values().stream().allMatch(
                snapshot -> snapshot.size() == 0
            )) {
                r = Optional.empty();
            } else {
                r = Optional.of(lastTouchTime.plus(keepAliveInterval));
            }
        }
        log.atTrace().setMessage("returning next required touch at {} from a lastTouchTime of {}")
            .addArgument(() -> r.map(Instant::toString).orElse("N/A"))
            .addArgument(lastTouchTime)
            .log();
        return r;
    }

*/
// REBUILD-LIMBO-END(G2)
    /**
     * Flushes staged offset commits without a driving read or back-pressure block. Once intake
     * stops (end of stream or shutdown), no poll cycle ever runs again, so commits staged by late
     * dispositions would otherwise sit unflushed and their acknowledgements would never complete.
     * Must run on the same executor that owns every other consumer interaction.
     */
// REBUILD-LIMBO-START(G2)
/*
    public void commitStagedOffsets() {
        safeCommit(globalContext::createCommitContext);
    }

    public void touch(ITrafficSourceContexts.IBackPressureBlockContext context) {
        try (var touchCtx = context.createNewTouchContext()) {
            log.trace("touch() called.");
            pause();
            try (var pollCtx = touchCtx.createNewPollContext()) {
                var records = kafkaConsumer.poll(Duration.ZERO);
                if (!records.isEmpty()) {
                    throw new IllegalStateException(
                        "Expected no entries once the consumer was paused.  "
                            + "This may have happened because a new assignment slipped into the consumer AFTER pause calls."
                    );
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (RuntimeException e) {
                log.atWarn().setCause(e)
                    .setMessage("Unable to poll the topic: {} with our Kafka consumer ({}). "
                            + "Swallowing and awaiting next metadata refresh to try again.")
                    .addArgument(topic)
                    .addArgument(this)
                    .log();
            } finally {
                resume();
            }
            safeCommit(context::createCommitContext);
            lastTouchTimeRef.set(clock.instant());
        }
    }

    private void pause() {
        var activePartitions = kafkaConsumer.assignment();
        try {
            kafkaConsumer.pause(activePartitions);
        } catch (IllegalStateException e) {
            log.atError()
                .setCause(e)
                .setMessage(
                    () -> "Unable to pause the topic partitions: {}.  "
                        + "The active partitions passed here : {}.  "
                        + "The active partitions as tracked here are: {}.  "
                        + "The active partitions according to the consumer: {}")
                .addArgument(topic)
                .addArgument(() -> activePartitions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addArgument(() -> getActivePartitions().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addArgument(() -> kafkaConsumer.assignment().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .log();
        }
    }

    private void resume() {
        var activePartitions = kafkaConsumer.assignment();
        try {
            kafkaConsumer.resume(activePartitions);
        } catch (IllegalStateException e) {
            log.atError()
                .setCause(e)
                .setMessage("Unable to resume the topic partitions: {}.  "
                        + "This may not be a fatal error for the entire process as the consumer should eventually " +
                        " rejoin and rebalance.  "
                        + "The active partitions passed here : {}.  "
                        + "The active partitions as tracked here are: {}.  "
                        + "The active partitions according to the consumer: {}"
                )
                .addArgument(topic)
                .addArgument(() -> activePartitions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addArgument(() -> getActivePartitions().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addArgument(() -> kafkaConsumer.assignment().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .log();
        }
    }

    private Collection<TopicPartition> getActivePartitions() {
        return partitionToObservedRecordQueueMap.keySet()
            .stream()
            .map(p -> new TopicPartition(topic, p))
            .collect(Collectors.toList());
    }

    public <T> Stream<T> getNextBatchOfRecords(
        ITrafficSourceContexts.IReadChunkContext context,
        BiFunction<KafkaCommitOffsetData, ConsumerRecord<String, byte[]>, T> builder
    ) {
        safeCommit(context::createCommitContext);
        var records = safePollWithSwallowedRuntimeExceptions(context);
        safeCommit(context::createCommitContext);
        return applyBuilder(builder, records);
    }

    ScanCycle scanAhead(int maximumRecords, Duration maximumDuration) {
        validateScanBudget(maximumRecords, maximumDuration);
        long deadline = System.nanoTime() + maximumDuration.toNanos();
        var assignment = Set.copyOf(kafkaConsumer.assignment());
        if (assignment.isEmpty()) {
            return new ScanCycle(List.of(), true, false);
        }
        var baseline = captureScanBaseline(assignment, deadline);
        if (baseline == null) {
            return new ScanCycle(List.of(), false, false);
        }
        var endOffsets = kafkaConsumer.endOffsets(assignment, remainingScanDuration(deadline));
        rebalanceDuringPoll.set(false);
        try {
            var scanned = collectScanRecords(baseline.assignment, endOffsets, maximumRecords, deadline);
            var stableGeneration = scanGenerationIsStable(baseline.assignment, baseline.generations);
            var exhaustedBudget = stableGeneration
                && (scanned.size() >= maximumRecords || System.nanoTime() >= deadline);
            return stableGeneration
                ? new ScanCycle(List.copyOf(scanned), true, exhaustedBudget)
                : new ScanCycle(List.of(), false, exhaustedBudget);
        } finally {
            restoreReplayPositions(baseline);
            rebalanceDuringPoll.set(false);
            lastTouchTimeRef.set(clock.instant());
        }
    }

    private void validateScanBudget(int maximumRecords, Duration maximumDuration) {
        if (maximumRecords <= 0) {
            throw new IllegalArgumentException("maximumRecords must be positive");
        }
        if (maximumDuration.isZero() || maximumDuration.isNegative()) {
            throw new IllegalArgumentException("maximumDuration must be positive");
        }
    }

    private ScanBaseline captureScanBaseline(Set<TopicPartition> assignment, long deadline) {
        var replayPositions = new HashMap<TopicPartition, Long>();
        var generations = new HashMap<Integer, Integer>();
        synchronized (commitDataLock) {
            for (var topicPartition : assignment) {
                replayPositions.put(
                    topicPartition,
                    kafkaConsumer.position(topicPartition, remainingScanDuration(deadline))
                );
                var queue = partitionToObservedRecordQueueMap.get(topicPartition.partition());
                if (queue == null) {
                    return null;
                }
                generations.put(
                    topicPartition.partition(),
                    Math.toIntExact(queue.generation().localSequence())
                );
            }
        }
        return new ScanBaseline(assignment, replayPositions, generations);
    }

    private List<ConsumerRecord<String, byte[]>> collectScanRecords(
        Set<TopicPartition> assignment,
        Map<TopicPartition, Long> endOffsets,
        int maximumRecords,
        long deadline
    ) {
        var scanned = new ArrayList<ConsumerRecord<String, byte[]>>();
        while (shouldContinueScan(assignment, endOffsets, scanned.size(), maximumRecords, deadline)) {
            var records = pollForScan(assignment, deadline);
            if (records == null) {
                return scanned;
            }
            for (var kafkaRecord : records) {
                var topicPartition = new TopicPartition(kafkaRecord.topic(), kafkaRecord.partition());
                if (scanned.size() < maximumRecords && assignment.contains(topicPartition)) {
                    scanned.add(kafkaRecord);
                }
            }
        }
        return scanned;
    }

    private boolean shouldContinueScan(
        Set<TopicPartition> assignment,
        Map<TopicPartition, Long> endOffsets,
        int scannedRecords,
        int maximumRecords,
        long deadline
    ) {
        return scannedRecords < maximumRecords
            && System.nanoTime() < deadline
            && !rebalanceDuringPoll.get()
            && !atScanEnd(assignment, endOffsets, deadline);
    }

    private ConsumerRecords<String, byte[]> pollForScan(
        Set<TopicPartition> assignment,
        long deadline
    ) {
        var remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
        try {
            return kafkaConsumer.poll(
                remaining.compareTo(Duration.ofMillis(10)) > 0 ? Duration.ofMillis(10) : remaining
            );
        } catch (RuntimeException e) {
            if (rebalanceDuringPoll.get() || !kafkaConsumer.assignment().equals(assignment)) {
                return null;
            }
            throw e;
        }
    }

    private void restoreReplayPositions(ScanBaseline baseline) {
        for (var entry : baseline.replayPositions.entrySet()) {
            var topicPartition = entry.getKey();
            if (kafkaConsumer.assignment().contains(topicPartition)
                && generationMatches(topicPartition, baseline.generations.get(topicPartition.partition()))) {
                kafkaConsumer.seek(topicPartition, entry.getValue());
            }
        }
    }

    private boolean atScanEnd(
        Set<TopicPartition> assignment,
        Map<TopicPartition, Long> endOffsets,
        long deadline
    ) {
        return assignment.stream().allMatch(topicPartition ->
            kafkaConsumer.position(topicPartition, remainingScanDuration(deadline))
                >= endOffsets.getOrDefault(topicPartition, Long.MAX_VALUE)
        );
    }

    private Duration remainingScanDuration(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("Kafka liveness scan exceeded its time budget");
        }
        return Duration.ofNanos(remainingNanos);
    }

    private boolean scanGenerationIsStable(
        Set<TopicPartition> assignment,
        Map<Integer, Integer> generations
    ) {
        return !rebalanceDuringPoll.get()
            && kafkaConsumer.assignment().equals(assignment)
            && assignment.stream().allMatch(topicPartition ->
                generationMatches(topicPartition, generations.get(topicPartition.partition()))
            );
    }

    private boolean generationMatches(TopicPartition topicPartition, Integer expectedGeneration) {
        synchronized (commitDataLock) {
            var queue = partitionToObservedRecordQueueMap.get(topicPartition.partition());
            return queue != null
                && expectedGeneration != null
                && queue.generation().localSequence() == expectedGeneration;
        }
    }

    private <T> Stream<T> applyBuilder(
        BiFunction<KafkaCommitOffsetData, ConsumerRecord<String, byte[]>, T> builder,
        ConsumerRecords<String, byte[]> records
    ) {
        var accepted = new ArrayList<T>();
        var rewindOffsets = new HashMap<TopicPartition, Long>();
        boolean capacityReached = false;
        for (var kafkaRecord : records) {
            var topicPartition = new TopicPartition(kafkaRecord.topic(), kafkaRecord.partition());
            if (capacityReached) {
                rewindOffsets.merge(topicPartition, kafkaRecord.offset(), Math::min);
            } else {
                var recordQueue = partitionToObservedRecordQueueMap.get(kafkaRecord.partition());
                var offsetDetails = new PojoKafkaCommitOffsetData(
                    Math.toIntExact(recordQueue.generation().localSequence()),
                    kafkaRecord.partition(),
                    kafkaRecord.offset()
                );
                var reservationKey = reservationKey(offsetDetails);
                if (!ownershipBudget.tryReserve(reservationKey, serializedRecordSize(kafkaRecord))) {
                    capacityReached = true;
                    rewindOffsets.merge(topicPartition, kafkaRecord.offset(), Math::min);
                } else {
                    var recordId = new KafkaRecordId(
                        topic,
                        kafkaRecord.partition(),
                        kafkaRecord.offset(),
                        offsetDetails.getGeneration()
                    );
                    recordQueue.register(recordId);
                    observedRecordMetadata.put(
                        recordId,
                        new ObservedRecordMetadata(kafkaRecord.key(), clock.instant())
                    );
                    publishObservedRecordQueueSnapshots();
                    kafkaRecordsLeftToCommitEventually.incrementAndGet();
                    metrics.unresolvedObligationsChanged(1);
                    final T builtRecord;
                    try {
                        builtRecord = builder.apply(offsetDetails, kafkaRecord);
                    } catch (RuntimeException | Error e) {
                        throw e;
                    }
                    log.atTrace().setMessage("records in flight={}")
                        .addArgument(kafkaRecordsLeftToCommitEventually::get)
                        .log();
                    accepted.add(builtRecord);
                }
            }
        }
        rewindRejectedRecords(rewindOffsets);
        return accepted.stream();
    }

    private void rewindRejectedRecords(Map<TopicPartition, Long> rewindOffsets) {
        rewindOffsets.forEach((partition, offset) -> {
            if (kafkaConsumer.assignment().contains(partition)) {
                kafkaConsumer.seek(partition, offset);
            }
        });
    }

    private static long serializedRecordSize(ConsumerRecord<String, byte[]> kafkaRecord) {
        int keySize = kafkaRecord.serializedKeySize();
        if (keySize < 0 && kafkaRecord.key() != null) {
            keySize = kafkaRecord.key().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        int valueSize = kafkaRecord.serializedValueSize();
        if (valueSize < 0 && kafkaRecord.value() != null) {
            valueSize = kafkaRecord.value().length;
        }
        return (long) Math.max(0, keySize) + Math.max(0, valueSize);
    }

    private ConsumerRecords<String, byte[]> safePollWithSwallowedRuntimeExceptions(
        ITrafficSourceContexts.IReadChunkContext context
    ) {
        try {
            lastTouchTimeRef.set(clock.instant());
            // Snapshot pre-poll positions so a post-poll min(pre, current) rewind can recover
            // from an inline rebalance without over-reading partitions the rebalance didn't touch.
            // position(tp) is a local call (no broker RPC).
            var prePollPositions = new HashMap<TopicPartition, Long>();
            for (var tp : kafkaConsumer.assignment()) {
                prePollPositions.put(tp, kafkaConsumer.position(tp));
            }
            rebalanceDuringPoll.set(false);
            ConsumerRecords<String, byte[]> records;
            try (var pollContext = context.createPollContext()) {
                records = kafkaConsumer.poll(keepAliveInterval.dividedBy(POLL_TIMEOUT_KEEP_ALIVE_DIVISOR));
            }
            if (rebalanceDuringPoll.getAndSet(false)) {
                records = recoverFromInlineRebalance(records, prePollPositions);
            }
            failOnUnexpectedOffsetRewind(records);
            pollsSinceLastHeartbeat.incrementAndGet();
            if (records.isEmpty()) {
                emptyPollsSinceLastHeartbeat.incrementAndGet();
            }
            log.atLevel(records.isEmpty() ? Level.TRACE : Level.DEBUG)
                .setMessage("Kafka consumer poll has fetched {} records.  Records in flight={}")
                .addArgument(records::count)
                .addArgument(kafkaRecordsLeftToCommitEventually::get)
                .log();
            log.atTrace().setMessage("All positions: {{}}")
                .addArgument(() -> kafkaConsumer.assignment().stream()
                    .map(tp -> tp + ": " + kafkaConsumer.position(tp))
                    .collect(Collectors.joining(",")))
                .log();
            log.atTrace().setMessage("All previously COMMITTED positions: {{}}")
                .addArgument(() -> kafkaConsumer.assignment().stream()
                    .map(tp -> tp + ": " + kafkaConsumer.committed(Set.of(tp)))
                    .collect(Collectors.joining(",")))
                .log();
            return records;
        } catch (RuntimeException e) {
            if (e instanceof UnexpectedOffsetRewindException) {
                throw e;
            }
            log.atWarn().setCause(e)
                .setMessage("Unable to poll the topic: {} with our Kafka consumer ({}). "
                        + "Swallowing and awaiting next metadata refresh to try again.")
                .addArgument(topic)
                .addArgument(this)
                .log();
            return new ConsumerRecords<>(Collections.emptyMap(), Collections.emptyMap());
        }
    }

*/
// REBUILD-LIMBO-END(G2)
    /**
     * Inline-rebalance recovery: drop everything this poll() returned and seek every still-
     * assigned partition to {@code min(prePollPosition, lowestOffsetReturnedForPartition)}.
     * Why min of those two:
     * <ul>
     *   <li>For a partition the rebalance <b>touched</b>, the kafka client just reset its fetch
     *       position to the broker-authoritative committed offset (or {@code auto.offset.reset}).
     *       The lowest offset returned by this poll for that partition equals that reset value
     *       (broker re-delivers from there). It can be &lt; pre-poll, so {@code min} picks it
     *       and re-delivery covers everything past the last commit under the new generation.</li>
     *   <li>For an <b>untouched</b> partition, the lowest returned offset equals pre-poll
     *       (nothing rewound), so {@code min} picks pre-poll. Only the polled-and-dropped
     *       records re-deliver — not 1000s of records past the last commit.</li>
     *   <li>A partition with <b>no records this poll</b> drops out of the {@code minReturned}
     *       map, so we skip it entirely (no seek needed; its position didn't move).</li>
     *   <li>A partition <b>newly assigned</b> mid-poll has no pre-poll entry; we skip it and
     *       leave its position wherever the rebalance set it.</li>
     *   <li>A partition <b>revoked outright</b> (no longer in the current assignment) is
     *       skipped by the outer loop — {@code position()}/{@code seek()} throw for partitions
     *       we no longer own.</li>
     * </ul>
     * Note: we cannot use post-poll {@code position(tp)} as the "current" reference because it
     * has already advanced past records this poll returned, hiding the broker's reset value
     * for touched partitions. The lowest returned offset is the un-advanced reference.
     */
// REBUILD-LIMBO-START(G2)
/*
    private ConsumerRecords<String, byte[]> recoverFromInlineRebalance(
        ConsumerRecords<String, byte[]> polled,
        Map<TopicPartition, Long> prePollPositions
    ) {
        var minReturned = new HashMap<TopicPartition, Long>();
        for (var rec : polled) {
            var tp = new TopicPartition(rec.topic(), rec.partition());
            minReturned.merge(tp, rec.offset(), Math::min);
        }
        // Iterate the CURRENT assignment, not the pre-poll snapshot. position() and seek()
        // throw IllegalStateException for any partition that is no longer assigned, so we
        // must skip partitions we lost outright during the rebalance — they're not ours to
        // touch. Partitions newly assigned mid-poll are also iterated here but get skipped
        // by the prePollPositions lookup below.
        for (var tp : kafkaConsumer.assignment()) {
            // Skip partitions newly assigned mid-poll (no pre-poll entry — leave them alone)
            // and partitions with no records this poll (position didn't move, no rewind needed).
            var pre = prePollPositions.get(tp);
            var minRet = minReturned.get(tp);
            if (pre != null && minRet != null) {
                kafkaConsumer.seek(tp, Math.min(pre, minRet));
            }
        }
        log.atInfo().setMessage("Rebalance during poll: dropped {} record(s); rewound {} partition(s)")
            .addArgument(polled::count)
            .addArgument(minReturned::size)
            .log();
        return new ConsumerRecords<>(Collections.emptyMap(), Collections.emptyMap());
    }

    private void failOnUnexpectedOffsetRewind(
        ConsumerRecords<String, byte[]> polled
    ) {
        var minimumReturned = new HashMap<TopicPartition, Long>();
        for (var consumerRecord : polled) {
            var partition = new TopicPartition(consumerRecord.topic(), consumerRecord.partition());
            minimumReturned.merge(partition, consumerRecord.offset(), Math::min);
        }
        var resetPartitions = minimumReturned.entrySet()
            .stream()
            .filter(entry -> hasAlreadyObserved(entry.getKey(), entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
        if (resetPartitions.isEmpty()) {
            return;
        }

        throw new UnexpectedOffsetRewindException(
            "Kafka offsets moved backward for "
                + resetPartitions.stream()
                    .map(partition -> partition + "->" + minimumReturned.get(partition))
                    .collect(Collectors.joining(","))
                + ". Live traffic-topic replacement is unsupported; restart the replay with a fresh source."
        );
    }

    static final class UnexpectedOffsetRewindException extends IllegalStateException {
        private UnexpectedOffsetRewindException(String message) {
            super(message);
        }
    }

    private boolean hasAlreadyObserved(TopicPartition partition, long offset) {
        synchronized (commitDataLock) {
            var queue = partitionToObservedRecordQueueMap.get(partition.partition());
            return queue != null && queue.hasAlreadyObserved(offset);
        }
    }

    boolean isActiveGeneration(KafkaRecordId recordId) {
        synchronized (commitDataLock) {
            var queue = partitionToObservedRecordQueueMap.get(recordId.partition());
            return queue != null
                && queue.generation().localSequence() == recordId.sourceGeneration();
        }
    }

    void recordProcessingFinished(KafkaRecordId recordId) {
        synchronized (commitDataLock) {
            var queue = partitionToObservedRecordQueueMap.get(recordId.partition());
            if (queue == null || queue.generation().localSequence() != recordId.sourceGeneration()) {
                log.atWarn()
                    .setMessage(
                        () -> "Record-processing completion generation ({}) is not current ({}). "
                            + "Ignoring this late completion because the record will be redelivered. Record={}")
                    .addArgument(recordId::sourceGeneration)
                    .addArgument((Optional.ofNullable(queue)
                        .map(q -> "new generation=" + q.generation().localSequence())
                        .orElse("Partition unassigned")))
                    .addArgument(recordId)
                    .log();
                return;
            }

            var metadata = observedRecordMetadata.get(recordId);
            if (metadata == null) {
                throw new IllegalStateException("Missing observed-record metadata for " + recordId);
            }
            var completion = queue.recordProcessingFinished(recordId);
            observedRecordMetadata.remove(recordId);
            ownershipBudget.release(
                new KafkaRecordOwnershipBudget.ReservationKey(
                    recordId.partition(),
                    recordId.sourceGeneration(),
                    recordId.offset()
                )
            );
            metrics.unresolvedObligationsChanged(-1);
            var partition = queue.generation().topicPartition();
            if (completion.nextCommitOffset().isPresent()) {
                var nextOffset = new OffsetAndMetadata(completion.nextCommitOffset().getAsLong());
                log.atDebug()
                    .setMessage("Adding new commit {}->{} to map")
                    .addArgument(partition)
                    .addArgument(nextOffset)
                    .log();
                if (nextSetOfCommitsMap.put(partition, nextOffset) == null) {
                    metrics.stagedCommitPartitionsChanged(1);
                }
                kafkaRecordsReadyToCommit.set(true);
            }
            publishObservedRecordQueueSnapshots();
            recomputeRecordsLeftToCommit();
        }
    }

    private void safeCommit(Supplier<IKafkaConsumerContexts.ICommitScopeContext> commitContextSupplier) {
        HashMap<TopicPartition, OffsetAndMetadata> nextCommitsMapCopy;
        IKafkaConsumerContexts.ICommitScopeContext context = null;
        synchronized (commitDataLock) {
            if (nextSetOfCommitsMap.isEmpty()) {
                return;
            }
            context = commitContextSupplier.get();
            nextCommitsMapCopy = new HashMap<>(nextSetOfCommitsMap);
        }
        try {
            safeCommitStatic(context, kafkaConsumer, nextCommitsMapCopy);
            commitsSinceLastHeartbeat.incrementAndGet();
            synchronized (commitDataLock) {
                nextCommitsMapCopy.forEach((partition, offset) -> {
                    if (nextSetOfCommitsMap.remove(partition, offset)) {
                        metrics.stagedCommitPartitionsChanged(-1);
                    }
                });
            }
            log.atTrace().setMessage("partitionToObservedRecordQueueMap={}")
                .addArgument(partitionToObservedRecordQueueMap)
                .log();
            recomputeRecordsLeftToCommit();
            log.atDebug().setMessage("Done committing now records in flight={}")
                .addArgument(kafkaRecordsLeftToCommitEventually::get)
                .log();
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Error while committing.  "
                        + "Another consumer may already be processing messages before these commits.  "
                        + "Commits ARE NOT being discarded here, with the expectation that the revoked callback "
                        + "(onPartitionsRevoked) will be called.  "
                        + "Within that method, commits for unassigned partitions will be discarded.  "
                        + "After that, touch() or poll() will trigger another commit attempt."
                        + "Those calls will occur in the near future if assigned partitions have pending commits.{}"
                )
                .addArgument(() -> nextSetOfCommitsMap.entrySet()
                    .stream()
                    .map(kvp -> kvp.getKey() + "->" + kvp.getValue())
                    .collect(Collectors.joining(",")))
                .log();
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static void safeCommitStatic(
        IKafkaConsumerContexts.ICommitScopeContext context,
        Consumer<String, byte[]> kafkaConsumer,
        HashMap<TopicPartition, OffsetAndMetadata> nextCommitsMap
    ) {
        assert !nextCommitsMap.isEmpty();
        log.atDebug().setMessage("Committing {}").addArgument(nextCommitsMap).log();
        try (var kafkaContext = context.createNewKafkaCommitContext()) {
            kafkaConsumer.commitSync(nextCommitsMap);
        }
    }

    private void removeObservedMetadataForPartition(
        int partition,
        ObservedRecordCommitQueue queue
    ) {
        if (queue == null) {
            return;
        }
        var generation = Math.toIntExact(queue.generation().localSequence());
        observedRecordMetadata.keySet().removeIf(
            recordId ->
                recordId.partition() == partition
                    && recordId.sourceGeneration() == generation
        );
    }

    private void recomputeRecordsLeftToCommit() {
        var unresolved = partitionToObservedRecordQueueMap.values()
            .stream()
            .mapToInt(ObservedRecordCommitQueue::size)
            .sum();
        kafkaRecordsLeftToCommitEventually.set(unresolved);
    }

    private void publishObservedRecordQueueSnapshots() {
        observedRecordQueueSnapshots.set(
            partitionToObservedRecordQueueMap.entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().snapshot()
                ))
        );
    }

*/
// REBUILD-LIMBO-END(G2)
    /** Emit a periodic heartbeat log summarizing the Kafka consumer state. */
// REBUILD-LIMBO-START(G2)
/*
    public void logHeartbeat() {
        int polls = pollsSinceLastHeartbeat.getAndSet(0);
        int emptyPolls = emptyPollsSinceLastHeartbeat.getAndSet(0);
        int commits = commitsSinceLastHeartbeat.getAndSet(0);
        int inflight = kafkaRecordsLeftToCommitEventually.get();
        boolean readyToCommit = kafkaRecordsReadyToCommit.get();
        int generation = consumerConnectionGeneration.get();
        var budget = ownershipBudget.snapshot();

        synchronized (commitDataLock) {
            var sb = new StringBuilder();
            sb.append("generation=").append(generation);
            sb.append(" partitions=").append(partitionToObservedRecordQueueMap.keySet());
            sb.append(" inflight=").append(inflight);

            appendCommitHeadDiagnostics(sb);

            sb.append(" polls=").append(polls);
            sb.append(" emptyPolls=").append(emptyPolls);
            sb.append(" commits=").append(commits);
            sb.append(" readyToCommit=").append(readyToCommit);
            sb.append(" pendingCommitPartitions=").append(nextSetOfCommitsMap.size());
            sb.append(" ownedRecords=").append(budget.records())
                .append("/").append(budget.maximumRecords());
            sb.append(" ownedBytes=").append(budget.bytes())
                .append("/").append(budget.maximumBytes());
            sb.append(" ownershipBudgetSaturated=").append(budget.saturated());

            heartbeatLogger.atInfo().setMessage("{}").addArgument(sb).log();
        }
    }

    boolean isReadCapacityAvailable() {
        return ownershipBudget.isCapacityAvailable();
    }

    void setReadCapacityAvailableListener(Runnable listener) {
        ownershipBudget.setCapacityAvailableListener(listener);
    }

    KafkaRecordOwnershipBudget.OwnershipSnapshot ownershipBudgetSnapshot() {
        return ownershipBudget.snapshot();
    }

    private static KafkaRecordOwnershipBudget.ReservationKey reservationKey(
        KafkaCommitOffsetData offset
    ) {
        return new KafkaRecordOwnershipBudget.ReservationKey(
            offset.getPartition(),
            offset.getGeneration(),
            offset.getOffset()
        );
    }

    private void appendCommitHeadDiagnostics(StringBuilder sb) {
        var commitHeads = new StringJoiner(", ", "[", "]");
        observedRecordQueueSnapshots.get().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                var partition = entry.getKey();
                var queue = entry.getValue();
                queue.headRecord().ifPresent(headRecord -> {
                    var headOffset = headRecord.offset();
                    var details = new StringBuilder()
                        .append("{partition=").append(partition)
                        .append(", generation=").append(queue.generation().localSequence())
                        .append(", offset=").append(headOffset);
                    Optional.ofNullable(observedRecordMetadata.get(headRecord)).ifPresent(metadata -> {
                        var age = Duration.between(metadata.acceptedAt(), clock.instant());
                        metrics.commitHeadObserved(
                            partition,
                            Math.toIntExact(queue.generation().localSequence()),
                            age
                        );
                        details.append(", conn=").append(metadata.connectionId)
                            .append(", age=").append(Utils.formatDurationInSeconds(age));
                    });
                    details.append(", tail=").append(queue.greatestObservedOffset())
                        .append(", queueSize=").append(queue.size())
                        .append("}");
                    commitHeads.add(details);
                });
            });
        sb.append(" commitHeads=").append(commitHeads);
    }


    String nextCommitsToString() {
        return "nextCommits="
            + nextSetOfCommitsMap.entrySet()
                .stream()
                .map(kvp -> kvp.getKey() + "->" + kvp.getValue())
                .collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        synchronized (commitDataLock) {
            int partitionCount = partitionToObservedRecordQueueMap.size();
            int commitsPending = nextSetOfCommitsMap.size();
            int recordsLeftToCommit = kafkaRecordsLeftToCommitEventually.get();
            boolean recordsReadyToCommit = kafkaRecordsReadyToCommit.get();
            return String.format(
                "TrackingKafkaConsumer{topic='%s', partitionCount=%d, commitsPending=%d, "
                    + "recordsLeftToCommit=%d, recordsReadyToCommit=%b}",
                topic,
                partitionCount,
                commitsPending,
                recordsLeftToCommit,
                recordsReadyToCommit
            );
        }
    }
}

*/
// REBUILD-LIMBO-END(G2)