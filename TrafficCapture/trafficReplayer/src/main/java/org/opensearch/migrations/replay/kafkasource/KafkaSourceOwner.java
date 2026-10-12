/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.CancellationGrace;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the Kafka-side replay state machine: partition generations, demand-controlled reading, and commit
 * authority.
 *
 * <p>On its dedicated thread, the owner applies queued source events, independently evaluates each
 * partition's pause reasons, polls only readable partitions, stamps returned records with the current local
 * generation, and delivers non-empty batches to intake. Rebalance callbacks create and retire generations;
 * a retiring generation receives a bounded grace period for completions and commits, while a successor is
 * held behind cleanup so old and new ownership cannot overlap unsafely.</p>
 *
 * <p>Commit positions are derived only from the contiguous prefix of records actually observed and reported
 * finished. Ordinary operation permits one asynchronous commit at a time and tracks acceptance separately
 * from callback outcome; revocation uses a deadline-bounded synchronous commit because no later poll may
 * deliver a callback. Unknown or stale outcomes remain explicit, and a capture-protocol violation leaves its
 * record as a permanent commit barrier so restart encounters the same poison input.</p>
 */
@Slf4j
public final class KafkaSourceOwner {
    public static final Duration DEFAULT_REVOCATION_GRACE = Duration.ofSeconds(1);
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_INITIAL_COMMIT_RETRY_DELAY = Duration.ofMillis(100);
    public static final Duration DEFAULT_MAXIMUM_COMMIT_RETRY_DELAY = Duration.ofSeconds(5);
    public static final double DEFAULT_COMMIT_RETRY_JITTER_FRACTION = 0.20;
    private static final Duration MAXIMUM_REVOCATION_COMMIT_RESERVE = Duration.ofSeconds(1);
    private static final int MAX_LOGGED_PARTITIONS = 16;
    private static final Duration UNBOUNDED_POLL_WAIT = Duration.ofNanos(Long.MAX_VALUE);
    private static final Logger HEARTBEAT_LOGGER = LoggerFactory.getLogger("KafkaHeartbeat");

    public enum AbandonmentCause {
        REJECTED,
        UNKNOWN,
        UNSUBMITTED
    }

    public enum CommitResolution {
        ACKNOWLEDGED,
        RETRIABLE,
        GENERATION_STALE,
        OUTCOME_UNKNOWN,
        REJECTED,
        LATE_CALLBACK,
        DUPLICATE_IGNORED
    }

    public enum RecordDiagnosticState {
        PROCESSING,
        PROCESSING_COMPLETE_WAITING_FOR_COMMIT_HEAD,
        READY_TO_COMMIT,
        COMMIT_IN_FLIGHT,
        COMMIT_UNCERTAIN,
        COMMIT_INELIGIBLE,
        UNKNOWN
    }

    public record RecordDiagnostic(
        @NonNull KafkaRecordId recordId,
        @NonNull RecordDiagnosticState state
    ) {}

    public record PartitionDiagnostic(
        @NonNull PartitionGenerationId generation,
        boolean kafkaPaused,
        boolean readable,
        boolean assignmentBootstrapPending,
        boolean requestOutstanding,
        boolean priorGenerationCleanupPending,
        boolean lifecycleAllowsIntake,
        int observedQueueSize,
        int unfinishedRecordCount,
        @NonNull Optional<KafkaRecordId> commitHead,
        long greatestObservedOffset,
        long recordsAwaitingCommit,
        @NonNull PartitionSourceState.CommitUncertainty commitUncertainty,
        @NonNull Optional<Long> stagedCommitPosition,
        @NonNull Optional<Long> inFlightCommitPosition
    ) {}

    /**
     * What a record's diagnostic state is derived from, captured once per partition so the snapshot does not
     * materialize a diagnostic for every in-flight record when the monitor looks up only the oldest one.
     */
    public record RecordStateSource(
        @NonNull ObservedRecordCommitQueue.Snapshot queue,
        @NonNull PartitionSourceState.CommitUncertainty commitUncertainty,
        @NonNull Optional<Long> stagedCommitPosition,
        @NonNull Optional<Long> inFlightCommitPosition
    ) {}

    public record DiagnosticSnapshot(
        @NonNull Map<PartitionGenerationId, PartitionDiagnostic> partitions,
        @NonNull Map<PartitionGenerationId, RecordStateSource> recordStateSources,
        @NonNull Set<KafkaRecordId> trackedRecords
    ) {
        public static final DiagnosticSnapshot EMPTY = new DiagnosticSnapshot(Map.of(), Map.of(), Set.of());

        public DiagnosticSnapshot {
            partitions = Map.copyOf(partitions);
            recordStateSources = Map.copyOf(recordStateSources);
            // Not Set.copyOf: immutable sets probe linearly, and record ids from consecutive offsets hash
            // to consecutive values, so copying a large backlog clustered into minutes of probing.
            trackedRecords = Collections.unmodifiableSet(new HashSet<>(trackedRecords));
        }

        /** The diagnostic for one tracked record, or empty if the owner held no context for it. */
        public Optional<RecordDiagnostic> record(KafkaRecordId recordId) {
            if (!trackedRecords.contains(recordId)) {
                return Optional.empty();
            }
            return Optional.ofNullable(recordStateSources.get(recordId.generation()))
                .map(source -> new RecordDiagnostic(recordId, diagnosticState(recordId, source)));
        }

        /** Every tracked record's diagnostic; proportional to the records in flight, so for inspection only. */
        public Map<KafkaRecordId, RecordDiagnostic> records() {
            var all = new LinkedHashMap<KafkaRecordId, RecordDiagnostic>();
            trackedRecords.forEach(recordId -> record(recordId).ifPresent(diagnostic -> all.put(recordId, diagnostic)));
            return Collections.unmodifiableMap(all);
        }

        public long uncertainPartitionCount() {
            return partitions.values().stream()
                .filter(partition ->
                    partition.commitUncertainty() != PartitionSourceState.CommitUncertainty.NONE_OBSERVED
                )
                .count();
        }
    }

    public interface Metrics {
        void recordsRead(long count);
        void recordsCommitted(long count);
        void recordsCancelled(long count);
        void recordsAbandonedAtRevocation(AbandonmentCause cause, long count);
        void recordsCommitIneligible(long count);
        void recordsOutstandingChanged(long delta);
        void commitAttemptRejected();
        void commitResolved(CommitResolution resolution);
        void commitRetryAttempted(KafkaSourcePort.CommitFailureClass failureClass);
        void commitRetryDegradedChanged(long delta);
        void commitRetryRecovered();
    }

    @FunctionalInterface
    public interface CommitRetryDelayPolicy {
        Duration delayAfterFailure(int consecutiveFailureCount);
    }

    private enum CommitOperationMode {
        STILL_OWNED_RETRYABLE,
        REVOCATION_TERMINAL
    }

    @FunctionalInterface
    public interface RecordContextFactory {
        RecordContextFactory NONE = (recordId, serializedSizeBytes) -> null;

        IReplayContexts.IKafkaRecordContext create(
            KafkaRecordId recordId,
            int serializedSizeBytes
        );
    }

    @FunctionalInterface
    public interface ProtocolViolationHandler {
        void begin(KafkaSourceInput.CaptureProtocolViolationDetected violation);
    }

    private final KafkaSourcePort port;
    private final KafkaSourceInputQueue sourceInputs;
    private final ReplayIntakeInputQueue intakeInputs;
    private final WakeupController wakeupController;
    private final Duration cancellationGrace;
    private final LongSupplier monotonicNanos;
    private final GraceIntervalWait graceWait;
    private final Metrics metrics;
    private final RecordContextFactory recordContextFactory;
    private final ProtocolViolationHandler protocolViolationHandler;
    private final long heartbeatIntervalNanos;
    private final CommitRetryDelayPolicy commitRetryDelayPolicy;

    private final Map<TopicPartition, PartitionSourceState> partitions = new LinkedHashMap<>();
    private final Map<KafkaRecordId, IReplayContexts.IKafkaRecordContext> recordContexts =
        new LinkedHashMap<>();
    private final Map<KafkaRecordId, Long> recordObservedAtNanos = new LinkedHashMap<>();
    private final Map<TopicPartition, KafkaSourcePort.CommitPoint> stagedCommitPoints =
        new LinkedHashMap<>();
    /**
     * The one accepted asynchronous operation that has not resolved.
     *
     * <p>The identity and its resolution latch travel together. A callback can race a thrown
     * {@link WakeupException}, and a late callback from that operation can arrive while a newer operation is
     * active. Only resolving this exact identity may clear the slot; resolving it twice is diagnostic and
     * changes no state.
     */
    private CommitOperation inFlightCommitOperation;
    private long nextCommitOperationId;
    private int consecutiveCommitFailures;
    private long commitRetryNotBeforeNanos;
    private boolean commitRetryScheduled;
    private KafkaSourcePort.CommitFailureClass commitRetryFailureClass =
        KafkaSourcePort.CommitFailureClass.OTHER;
    private boolean commitRetryDegraded;
    /**
     * Generations revoked or lost whose cleanup replay intake has not yet reported.
     *
     * <p>A set per partition rather than a boolean, for two reasons. Map membership cannot answer the question:
     * the previous generation's state is removed before its successor is created, so "was there a prior
     * generation" is always false by the time a successor could ask. And {@code GenerationCleanupFinished} can
     * arrive <em>before</em> the successor is assigned, since cleanup is fast and reassignment waits for a
     * poll — a boolean set at assignment time would then never be cleared, pausing the partition forever.
     */
    private final Map<TopicPartition, Set<PartitionGenerationId>> cleanupOutstanding = new LinkedHashMap<>();
    private long nextGenerationSequence;
    private boolean orderlyShutdownStarted;
    private boolean orderlyShutdownClosing;
    private boolean orderlyShutdownClosed;
    private boolean protocolViolationDetected;
    private long nextHeartbeatNanos;
    private long pollsSinceLastHeartbeat;
    private long emptyPollsSinceLastHeartbeat;
    private long commitsSinceLastHeartbeat;
    private long recordsRead;
    private long recordsCommitted;
    private long recordsOutstanding;
    private volatile DiagnosticSnapshot diagnosticSnapshot = DiagnosticSnapshot.EMPTY;

    public KafkaSourceOwner(
        KafkaSourcePort port,
        KafkaSourceInputQueue sourceInputs,
        ReplayIntakeInputQueue intakeInputs,
        WakeupController wakeupController,
        Duration cancellationGrace,
        LongSupplier monotonicNanos,
        GraceIntervalWait graceWait,
        Metrics metrics
    ) {
        this(
            port,
            sourceInputs,
            intakeInputs,
            wakeupController,
            cancellationGrace,
            monotonicNanos,
            graceWait,
            metrics,
            RecordContextFactory.NONE,
            violation -> {
                throw new CaptureProtocolViolation(violation);
            }
        );
    }

    public KafkaSourceOwner(
        KafkaSourcePort port,
        KafkaSourceInputQueue sourceInputs,
        ReplayIntakeInputQueue intakeInputs,
        WakeupController wakeupController,
        Duration cancellationGrace,
        LongSupplier monotonicNanos,
        GraceIntervalWait graceWait,
        Metrics metrics,
        RecordContextFactory recordContextFactory
    ) {
        this(
            port,
            sourceInputs,
            intakeInputs,
            wakeupController,
            cancellationGrace,
            monotonicNanos,
            graceWait,
            metrics,
            recordContextFactory,
            violation -> {
                throw new CaptureProtocolViolation(violation);
            }
        );
    }

    public KafkaSourceOwner(
        KafkaSourcePort port,
        KafkaSourceInputQueue sourceInputs,
        ReplayIntakeInputQueue intakeInputs,
        WakeupController wakeupController,
        Duration cancellationGrace,
        LongSupplier monotonicNanos,
        GraceIntervalWait graceWait,
        Metrics metrics,
        RecordContextFactory recordContextFactory,
        ProtocolViolationHandler protocolViolationHandler
    ) {
        this(
            port,
            sourceInputs,
            intakeInputs,
            wakeupController,
            cancellationGrace,
            monotonicNanos,
            graceWait,
            metrics,
            recordContextFactory,
            protocolViolationHandler,
            DEFAULT_HEARTBEAT_INTERVAL,
            defaultCommitRetryDelayPolicy()
        );
    }

    KafkaSourceOwner(
        @NonNull KafkaSourcePort port,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull ReplayIntakeInputQueue intakeInputs,
        @NonNull WakeupController wakeupController,
        @NonNull Duration cancellationGrace,
        @NonNull LongSupplier monotonicNanos,
        @NonNull GraceIntervalWait graceWait,
        @NonNull Metrics metrics,
        @NonNull RecordContextFactory recordContextFactory,
        @NonNull ProtocolViolationHandler protocolViolationHandler,
        @NonNull Duration heartbeatInterval
    ) {
        this(
            port,
            sourceInputs,
            intakeInputs,
            wakeupController,
            cancellationGrace,
            monotonicNanos,
            graceWait,
            metrics,
            recordContextFactory,
            protocolViolationHandler,
            heartbeatInterval,
            defaultCommitRetryDelayPolicy()
        );
    }

    KafkaSourceOwner(
        @NonNull KafkaSourcePort port,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull ReplayIntakeInputQueue intakeInputs,
        @NonNull WakeupController wakeupController,
        @NonNull Duration cancellationGrace,
        @NonNull LongSupplier monotonicNanos,
        @NonNull GraceIntervalWait graceWait,
        @NonNull Metrics metrics,
        @NonNull RecordContextFactory recordContextFactory,
        @NonNull ProtocolViolationHandler protocolViolationHandler,
        @NonNull Duration heartbeatInterval,
        @NonNull CommitRetryDelayPolicy commitRetryDelayPolicy
    ) {
        this.port = port;
        this.sourceInputs = sourceInputs;
        this.intakeInputs = intakeInputs;
        this.wakeupController = wakeupController;
        this.cancellationGrace = cancellationGrace;
        this.monotonicNanos = monotonicNanos;
        this.graceWait = graceWait;
        this.metrics = metrics;
        this.recordContextFactory = recordContextFactory;
        this.protocolViolationHandler = protocolViolationHandler;
        this.commitRetryDelayPolicy = commitRetryDelayPolicy;
        if (cancellationGrace.isNegative()) {
            throw new IllegalArgumentException("cancellationGrace must not be negative");
        }
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        heartbeatIntervalNanos = heartbeatInterval.toNanos();
        nextHeartbeatNanos = monotonicNanos.getAsLong() + heartbeatIntervalNanos;
    }

    private static CommitRetryDelayPolicy defaultCommitRetryDelayPolicy() {
        return randomizedExponentialCommitRetryDelay(
            DEFAULT_INITIAL_COMMIT_RETRY_DELAY,
            DEFAULT_MAXIMUM_COMMIT_RETRY_DELAY,
            DEFAULT_COMMIT_RETRY_JITTER_FRACTION,
            () -> ThreadLocalRandom.current().nextDouble()
        );
    }

    static CommitRetryDelayPolicy randomizedExponentialCommitRetryDelay(
        @NonNull Duration initialDelay,
        @NonNull Duration maximumDelay,
        double jitterFraction,
        @NonNull DoubleSupplier processRandomFraction
    ) {
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                "maximumDelay must be greater than or equal to initialDelay"
            );
        }
        if (!(jitterFraction >= 0.0 && jitterFraction <= 1.0)) {
            throw new IllegalArgumentException("jitterFraction must be in [0.0, 1.0]");
        }
        var randomFraction = processRandomFraction.getAsDouble();
        if (!(randomFraction >= 0.0 && randomFraction < 1.0)) {
            throw new IllegalStateException(
                "processRandomFraction must return a value in [0.0, 1.0)"
            );
        }
        var processJitterMultiplier = 1.0 - jitterFraction * randomFraction;
        var initialNanos = initialDelay.toNanos();
        var maximumNanos = maximumDelay.toNanos();
        return consecutiveFailureCount -> {
            if (consecutiveFailureCount < 1) {
                throw new IllegalArgumentException(
                    "consecutiveFailureCount must be positive"
                );
            }
            var unjitteredNanos = initialNanos;
            for (var failure = 1;
                 failure < consecutiveFailureCount && unjitteredNanos < maximumNanos;
                 failure++) {
                unjitteredNanos = unjitteredNanos > maximumNanos / 2
                    ? maximumNanos
                    : unjitteredNanos * 2;
            }
            return Duration.ofNanos((long) (unjitteredNanos * processJitterMultiplier));
        };
    }

    /**
     * One iteration of the {@code kafkaLLD §3} loop. The order is the design's and is not interchangeable:
     * inputs are applied before new demand is acted on, commits are submitted before a poll that a wakeup
     * might interrupt, and resume happens last so it reflects every state change this iteration made.
     */
    public void runOnce() {
        try {
            applyQueuedInputs();
            submitLoopCommitIfEligible();
            applyPauseAndResumeDecisions();
            pollAndDeliver();
        } finally {
            logHeartbeatIfDue();
        }
    }

    /**
     * Begins host-bounded orderly shutdown while retaining Kafka ownership long enough to commit drained work.
     */
    public void beginOrderlyShutdown() {
        if (orderlyShutdownStarted) {
            return;
        }
        orderlyShutdownStarted = true;
        for (var state : partitions.values()) {
            state.endIntake();
            cleanupOutstanding
                .computeIfAbsent(state.topicPartition(), ignored -> new LinkedHashSet<>())
                .add(state.generation());
            submitRequired(new ReplayIntakeInput.GracefulGenerationCancellation(
                state.generation(),
                CancellationGrace.Shutdown.INSTANCE
            ));
        }
        applyPauseAndResumeDecisions();
    }

    public boolean isOrderlyShutdownReadyToClose() {
        return orderlyShutdownStarted
            && cleanupOutstanding.isEmpty()
            && sourceInputs.isEmpty()
            && stagedCommitPoints.isEmpty()
            && inFlightCommitOperation == null;
    }

    public void closeAfterOrderlyShutdown() {
        if (orderlyShutdownClosed) {
            return;
        }
        if (!isOrderlyShutdownReadyToClose()) {
            throw new IllegalStateException("orderly shutdown has not drained");
        }
        orderlyShutdownClosing = true;
        for (var topicPartition : List.copyOf(partitions.keySet())) {
            retireGeneration(topicPartition, false);
        }
        port.close();
        orderlyShutdownClosed = true;
    }

    // ---------------------------------------------------------------- queued source inputs

    private void applyQueuedInputs() {
        for (var input : sourceInputs.drain()) {
            apply(input);
        }
    }

    /**
     * Exhaustive over {@link KafkaSourceInput} with no {@code default}, so adding a variant breaks here
     * rather than being silently ignored ({@code kafkaLLD §4.2}).
     */
    private void apply(KafkaSourceInput input) {
        switch (input) {
            case KafkaSourceInput.RequestNextPartitionBatch request -> applyBatchRequest(request);
            case KafkaSourceInput.RecordProcessingFinished finished -> applyRecordFinished(finished);
            case KafkaSourceInput.GenerationCleanupFinished cleanup -> applyCleanupFinished(cleanup);
            case KafkaSourceInput.CaptureProtocolViolationDetected violation ->
                applyProtocolViolation(violation);
        }
    }

    /**
     * Accepts a batch request only under the three conditions {@code kafkaLLD §5.3} names: the generation is
     * current, no earlier request for it is outstanding, and lifecycle state has not permanently ended intake.
     *
     * <p>The third is checked here and not left to {@link PartitionSourceState#isReadable()}. That would keep
     * the partition paused and so happen to be harmless, but it rests the whole "no reading past the permanent
     * boundary" invariant on one check where the design specifies two — and it records a request against a
     * generation that can never satisfy it. Prior-generation cleanup is deliberately <em>not</em> in this list:
     * §5.3 has it delay reading without rejecting the request.
     */
    private void applyBatchRequest(KafkaSourceInput.RequestNextPartitionBatch request) {
        var state = currentStateFor(request.generation());
        if (state.isEmpty()) {
            // A stale-generation request is dropped rather than applied to a successor assignment, which is
            // why every input carries its generation (kafkaLLD §4.2).
            log.atDebug().setMessage("Ignoring batch request for inactive generation {}")
                .addArgument(request::generation).log();
            return;
        }
        if (!state.get().lifecycleAllowsIntake()) {
            log.atDebug().setMessage("Ignoring batch request for {}: intake has permanently ended")
                .addArgument(request::generation).log();
            return;
        }
        state.get().requestBatch(request.requestId());
    }

    private void applyRecordFinished(KafkaSourceInput.RecordProcessingFinished finished) {
        var state = partitions.get(finished.generation().topicPartition());
        if (state == null || !state.generation().equals(finished.generation())) {
            // Late completion for a cleaned-up generation is diagnostic only (kafkaLLD §5.6).
            log.atDebug().setMessage("Ignoring completion for inactive generation {}")
                .addArgument(finished::generation).log();
            return;
        }
        var completion = state.commitQueue().recordProcessingFinished(finished.recordId());
        completion.newlyContiguousRecords().forEach(recordObservedAtNanos::remove);
        // Counted as records, not as an offset delta: physical offset gaps exist and §17.1 requires that they
        // not block advancement, so offset arithmetic would over-count by every gap.
        state.countRecordsAwaitingCommit(completion.newlyContiguousRecords().size());
        completion.commitPoint().ifPresent(commitPoint ->
            stageCommitPoint(finished.generation().topicPartition(), commitPoint)
        );
    }

    /**
     * Clears the cleanup gate for the generation that finished, and only for it.
     *
     * <p>The message names the <em>old</em> generation while the gate lives on its successor, so the two are
     * matched through {@link #cleanupOutstanding} rather than by topic-partition alone. Clearing by partition
     * would let a cleanup for generation N−2 release a successor still waiting on N−1.
     *
     * <p>Clears only the cleanup reason. A successor stays paused if it has no request outstanding or if
     * lifecycle state still prohibits intake — the reasons do not alias ({@code kafkaLLD §5.1}).
     */
    private void applyCleanupFinished(KafkaSourceInput.GenerationCleanupFinished cleanup) {
        var topicPartition = cleanup.generation().topicPartition();
        var outstanding = cleanupOutstanding.get(topicPartition);
        if (outstanding == null || !outstanding.remove(cleanup.generation())) {
            log.atDebug()
                .setMessage(
                    "event=kafka_generation_cleanup_decided topic={} partition={} generation={} "
                        + "matched=false remaining_generations=0"
                )
                .addArgument(topicPartition::topic)
                .addArgument(topicPartition::partition)
                .addArgument(() -> cleanup.generation().localSequence())
                .log();
            return;
        }
        if (outstanding.isEmpty()) {
            cleanupOutstanding.remove(topicPartition);
            var successor = partitions.get(topicPartition);
            if (successor != null) {
                successor.setPriorGenerationCleanupPending(false);
            }
        }
        log.atDebug()
            .setMessage(
                "event=kafka_generation_cleanup_decided topic={} partition={} generation={} "
                    + "matched=true remaining_generations={}"
            )
            .addArgument(topicPartition::topic)
            .addArgument(topicPartition::partition)
            .addArgument(() -> cleanup.generation().localSequence())
            .addArgument(outstanding::size)
            .log();
    }

    private void applyProtocolViolation(KafkaSourceInput.CaptureProtocolViolationDetected violation) {
        protocolViolationDetected = true;
        partitions.values().forEach(PartitionSourceState::endIntake);
        applyPauseAndResumeDecisions();
        // Blocks commits at and past the violating offset so a restart stops at the same record rather than
        // skipping it. Ending intake is what stops later records being admitted.
        currentStateFor(violation.generation()).ifPresent(state -> {
            state.endIntake();
            state.commitQueue().markCommitIneligible(violation.recordId());
            completeRecordContext(
                violation.recordId(),
                IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE
            );
            metrics.recordsCommitIneligible(1);
            changeRecordsOutstanding(-1);
            // A position already staged is necessarily before the violating record, because only
            // RecordProcessingFinished advances the prefix. Preserve that valid progress; the ineligible entry
            // remains in the deque and blocks every position at or beyond itself.
        });
        // An already-retired generation has no commit state left to classify. It still begins the same bounded
        // protocol-fatal path without mutating a successor generation's accounting.
        protocolViolationHandler.begin(violation);
    }

    private void stageCommitPoint(
        TopicPartition topicPartition,
        KafkaSourcePort.CommitPoint commitPoint
    ) {
        var stagedPoint = stagedCommitPoints.merge(
            topicPartition,
            commitPoint,
            KafkaSourceOwner::laterCommitPoint
        );
        log.atDebug()
            .setMessage(
                "event=kafka_commit_staged topic={} partition={} next_position={} "
                    + "replayed_through_log_append_time_ms={}"
            )
            .addArgument(topicPartition::topic)
            .addArgument(topicPartition::partition)
            .addArgument(stagedPoint::nextPosition)
            .addArgument(stagedPoint::replayedThroughLogAppendTimeMillis)
            .log();
    }

    private static KafkaSourcePort.CommitPoint laterCommitPoint(
        KafkaSourcePort.CommitPoint first,
        KafkaSourcePort.CommitPoint second
    ) {
        if (first.nextPosition() == second.nextPosition()) {
            if (first.replayedThroughLogAppendTimeMillis()
                != second.replayedThroughLogAppendTimeMillis()) {
                throw new IllegalStateException(
                    "one Kafka position cannot have two replay-through timestamps: "
                        + first
                        + " and "
                        + second
                );
            }
            return first;
        }
        return first.nextPosition() > second.nextPosition() ? first : second;
    }

    // ---------------------------------------------------------------- commit

    /**
     * Submits one asynchronous commit for every staged commit point, if none is already in flight.
     *
     * <p>Asynchronous because a synchronous commit here blocks for as long as the client retries internally —
     * up to {@code default.api.timeout.ms} — while this owner does not poll, which can exceed
     * {@code max.poll.interval.ms} and provoke a rebalance. That is {@code D5}'s mechanism in the ordinary
     * path rather than the revocation path, and {@code kafkaLLD §5.7} resolves it by making the loop's
     * submission asynchronous. The loop polls every iteration and a poll is what delivers the callback.
     *
     * <p>One operation at a time plus owner-local retry backoff bounds broker pressure
     * ({@code procCommit §9.4}); a partition that advances mid-flight stages the newer position rather than
     * racing the old one.
     */
    private void submitLoopCommitIfEligible() {
        if (stagedCommitPoints.isEmpty() || inFlightCommitOperation != null) {
            return;
        }
        var nowNanos = monotonicNanos.getAsLong();
        if (commitRetryScheduled && commitRetryNotBeforeNanos - nowNanos > 0) {
            return;
        }
        recordRetryAttemptIfDue();
        var submitted = detachForSubmission();
        var operation = new CommitOperation(nextCommitOperationId++, submitted);
        inFlightCommitOperation = operation;
        log.atDebug()
            .setMessage(
                "event=kafka_commit_submitted mode=async operation={} partition_count={} partitions={}"
            )
            .addArgument(() -> operation.operationId)
            .addArgument(submitted::size)
            .addArgument(() -> summarizePartitions(submitted.keySet()))
            .log();
        wakeupController.enterProtectedOperation();
        try {
            var submission = port.commitAsync(
                commitPointsOf(submitted),
                result -> resolveCommitOperation(
                    operation,
                    result,
                    false,
                    CommitOperationMode.STILL_OWNED_RETRYABLE
                )
            );
            if (!submission.accepted()) {
                resolveCommitOperation(
                    operation,
                    submission.rejection(),
                    true,
                    CommitOperationMode.STILL_OWNED_RETRYABLE
                );
            }
        } catch (WakeupException absorbedByTheSubmission) {
            // §5.4 leaves this owner as the only interpreter of a wakeup, which is why the adapter propagates it
            // rather than classifying it. Resolving it here is what releases the one-in-flight slot §5.7 permits
            // only one of; a propagating exception would strand that slot for the life of the process, and a
            // submission interrupted before it registered a callback has nothing else that can resolve it.
            //
            // The outcome is the conservative reading rather than a stated rule: §5.7 names a wakeup as an
            // unknown outcome for a *bounded synchronous* commit, and says nothing about an interrupted
            // asynchronous submission. Unknown is right either way — the operation may already have reached the
            // broker — and it keeps a still-owned partition's position and re-offers it.
            wakeupController.onWakeupAbsorbedByProtectedOperation();
            resolveCommitOperation(
                operation,
                KafkaSourcePort.CommitResult.failed(
                    KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN,
                    KafkaSourcePort.CommitFailureClass.WAKEUP
                ),
                false,
                CommitOperationMode.STILL_OWNED_RETRYABLE
            );
        } finally {
            wakeupController.leaveProtectedOperation();
        }
    }

    /**
     * Removes the staged commit points and records, per partition, the generation they belong to and how many
     * records they cover.
     *
     * <p>The generation is what makes a callback resolvable. Without it a callback that arrives after the
     * partition was revoked and reassigned would be indistinguishable from one for the current generation, and
     * would credit or re-stage against a successor that never submitted it.
     */
    private Map<TopicPartition, SubmittedCommitPoint> detachForSubmission() {
        return detachForSubmission(new LinkedHashSet<>(stagedCommitPoints.keySet()));
    }

    /**
     * Detaches only the selected partitions.
     *
     * <p>The revocation-terminal path uses this to keep retained partitions staged under their ordinary
     * retry deadline while giving only the generations Kafka is revoking their final bounded opportunity.
     */
    private Map<TopicPartition, SubmittedCommitPoint> detachForSubmission(
        Set<TopicPartition> selectedPartitions
    ) {
        var submitted = new LinkedHashMap<TopicPartition, SubmittedCommitPoint>();
        for (var topicPartition : selectedPartitions) {
            var commitPoint = stagedCommitPoints.get(topicPartition);
            if (commitPoint == null) {
                continue;
            }
            var state = partitions.get(topicPartition);
            if (state == null) {
                continue;
            }
            submitted.put(
                topicPartition,
                new SubmittedCommitPoint(
                    state.generation(),
                    commitPoint,
                    state.takeRecordsAwaitingCommit()
                )
            );
        }
        submitted.keySet().forEach(stagedCommitPoints::remove);
        return submitted;
    }

    private static Map<TopicPartition, KafkaSourcePort.CommitPoint> commitPointsOf(
        Map<TopicPartition, SubmittedCommitPoint> submitted
    ) {
        var commitPoints = new LinkedHashMap<TopicPartition, KafkaSourcePort.CommitPoint>();
        submitted.forEach((topicPartition, detail) ->
            commitPoints.put(topicPartition, detail.commitPoint())
        );
        return commitPoints;
    }

    /**
     * One partition's point in a submitted commit, with the generation that submitted it and how many
     * records it covers.
     */
    private record SubmittedCommitPoint(
        PartitionGenerationId generation,
        KafkaSourcePort.CommitPoint commitPoint,
        PartitionSourceState.CommitCoverage recordsCovered
    ) {
        long nextPosition() {
            return commitPoint.nextPosition();
        }
    }

    /**
     * One operation-level identity and latch.
     *
     * <p>Owner-thread confinement makes an atomic unnecessary. The identity is still essential: a callback for
     * operation N may arrive after a wakeup resolved N and operation N+1 was submitted. Only N may observe its
     * latch, and it can never clear N+1's in-flight slot.
     */
    private static final class CommitOperation {
        private final long operationId;
        private final Map<TopicPartition, SubmittedCommitPoint> submitted;
        private boolean resolved;

        private CommitOperation(long operationId, Map<TopicPartition, SubmittedCommitPoint> submitted) {
            this.operationId = operationId;
            this.submitted = Map.copyOf(submitted);
        }

        private boolean claimResolution() {
            if (resolved) {
                return false;
            }
            resolved = true;
            return true;
        }
    }

    /**
     * Resolves a submitted commit. Runs on the Kafka thread, delivered from inside a later {@code poll()}.
     *
     * <p>Staged positions are credited on acknowledgement, never on attempt. An unresolved failure re-stages
     * every partition this consumer still owns under the same generation and drops the rest: committing a
     * position that did in fact commit is idempotent, which is what makes re-offering sound while a batch's
     * partial application stays unobservable, whereas abandoning it would hold that partition's progress until
     * its prefix next advances — which work blocked behind an unfinished head can delay without bound.
     */
    private void resolveCommitOperation(
        CommitOperation operation,
        KafkaSourcePort.CommitResult result,
        boolean rejectedWithoutBrokerMovement,
        CommitOperationMode mode
    ) {
        var outcome = result.outcome();
        if (!operation.claimResolution()) {
            metrics.commitResolved(CommitResolution.DUPLICATE_IGNORED);
            log.atDebug().setMessage("Ignoring duplicate resolution for commit operation {}: {}")
                .addArgument(operation.operationId).addArgument(outcome).log();
            return;
        }
        if (inFlightCommitOperation == operation) {
            inFlightCommitOperation = null;
        }
        if (rejectedWithoutBrokerMovement) {
            metrics.commitAttemptRejected();
            metrics.commitResolved(CommitResolution.REJECTED);
        } else {
            metrics.commitResolved(resolutionOf(outcome));
            if (outcome == KafkaSourcePort.CommitOutcome.ACKNOWLEDGED) {
                commitsSinceLastHeartbeat++;
            }
        }
        log.atDebug()
            .setMessage(
                "event=kafka_commit_resolved operation={} outcome={} rejected_before_acceptance={} "
                    + "failure_class={} partition_count={}"
            )
            .addArgument(() -> operation.operationId)
            .addArgument(outcome)
            .addArgument(rejectedWithoutBrokerMovement)
            .addArgument(result::failureClass)
            .addArgument(() -> operation.submitted.size())
            .log();
        if (outcome != KafkaSourcePort.CommitOutcome.ACKNOWLEDGED) {
            log.atDebug()
                .setMessage(
                    "event=kafka_commit_uncertain operation={} outcome={} "
                        + "rejected_before_acceptance={} failure_class={} partition_count={} partitions={}"
                )
                .addArgument(() -> operation.operationId)
                .addArgument(outcome)
                .addArgument(rejectedWithoutBrokerMovement)
                .addArgument(result::failureClass)
                .addArgument(() -> operation.submitted.size())
                .addArgument(() -> summarizePartitions(operation.submitted.keySet()))
                .log();
        }
        operation.submitted.forEach((topicPartition, detail) -> {
            var state = partitions.get(topicPartition);
            var currentGeneration = state != null && state.generation().equals(detail.generation());

            if (!currentGeneration) {
                // kafkaLLD §5.7's LATE_CALLBACK: diagnostic only. It changes no state -- crediting a successor
                // for an operation the revoked generation submitted would inflate its retirement count, and
                // re-staging would offer a successor a position it never derived.
                wakeupController.countLateCommitCallback();
                metrics.commitResolved(CommitResolution.LATE_CALLBACK);
                log.atDebug()
                    .setMessage("Late commit resolution for retired generation {} at {}: {}; ignoring")
                    .addArgument(detail::generation).addArgument(detail::nextPosition).addArgument(outcome)
                    .log();
                return;
            }

            if (outcome == KafkaSourcePort.CommitOutcome.ACKNOWLEDGED) {
                state.creditRecordsCommitted(detail.recordsCovered());
                completeCommittedRecordContexts(detail.generation(), detail.nextPosition());
                metrics.recordsCommitted(detail.recordsCovered().total());
                recordsCommitted = Math.addExact(recordsCommitted, detail.recordsCovered().total());
                changeRecordsOutstanding(-detail.recordsCovered().total());
                // A still-owned partition restores records after an unknown outcome. An acknowledgement of the
                // re-offered position covers those restored records, so the earlier uncertainty no longer
                // explains its retirement count.
                state.clearCommitUncertainty();
                return;
            }

            // Nothing is reported to intake, which finished its record-processing decision before sending
            // RecordProcessingFinished (kafkaLLD §5.7).
            if (rejectedWithoutBrokerMovement) {
                state.restoreRejectedRecords(detail.recordsCovered());
            } else {
                state.restoreUncertainRecords(detail.recordsCovered());
            }
            if (stillOwnsForCommit(topicPartition)) {
                stageCommitPoint(topicPartition, detail.commitPoint());
            } else {
                log.atDebug().setMessage("Dropping commit position {} for {}: {} and no longer committable")
                    .addArgument(detail::nextPosition).addArgument(topicPartition).addArgument(outcome).log();
            }
        });
        if (mode == CommitOperationMode.STILL_OWNED_RETRYABLE) {
            if (outcome == KafkaSourcePort.CommitOutcome.ACKNOWLEDGED) {
                recoverCommitRetryIfNeeded();
            } else if (!stagedCommitPoints.isEmpty()) {
                scheduleCommitRetry(operation, result, rejectedWithoutBrokerMovement);
            } else {
                clearCommitRetryWithoutRecovery();
            }
        } else if (stagedCommitPoints.isEmpty() && inFlightCommitOperation == null) {
            if (outcome == KafkaSourcePort.CommitOutcome.ACKNOWLEDGED) {
                recoverCommitRetryIfNeeded();
            } else {
                clearCommitRetryWithoutRecovery();
            }
        }
    }

    private void scheduleCommitRetry(
        CommitOperation operation,
        KafkaSourcePort.CommitResult result,
        boolean rejectedWithoutBrokerMovement
    ) {
        if (consecutiveCommitFailures < Integer.MAX_VALUE) {
            consecutiveCommitFailures++;
        }
        var delay = commitRetryDelayPolicy.delayAfterFailure(consecutiveCommitFailures);
        if (delay.isNegative()) {
            throw new IllegalStateException("commit retry delay must not be negative");
        }
        commitRetryNotBeforeNanos = monotonicNanos.getAsLong() + delay.toNanos();
        commitRetryScheduled = true;
        commitRetryFailureClass = result.failureClass();
        if (!commitRetryDegraded) {
            commitRetryDegraded = true;
            metrics.commitRetryDegradedChanged(1);
            log.atWarn()
                .setMessage(
                    "event=kafka_commit_retry_degraded outcome={} rejected_before_acceptance={} "
                        + "failure_class={} retry_delay_ms={} partition_count={} partitions={}"
                )
                .addArgument(result::outcome)
                .addArgument(rejectedWithoutBrokerMovement)
                .addArgument(result::failureClass)
                .addArgument(delay::toMillis)
                .addArgument(() -> operation.submitted.size())
                .addArgument(() -> summarizePartitions(operation.submitted.keySet()))
                .log();
        }
        log.atDebug()
            .setMessage(
                "event=kafka_commit_retry_backoff operation={} consecutive_failures={} outcome={} "
                    + "rejected_before_acceptance={} failure_class={} retry_delay_ms={} "
                    + "staged_partition_count={}"
            )
            .addArgument(() -> operation.operationId)
            .addArgument(consecutiveCommitFailures)
            .addArgument(result::outcome)
            .addArgument(rejectedWithoutBrokerMovement)
            .addArgument(result::failureClass)
            .addArgument(delay::toMillis)
            .addArgument(stagedCommitPoints::size)
            .log();
    }

    private void recordRetryAttemptIfDue() {
        if (!commitRetryScheduled) {
            return;
        }
        commitRetryScheduled = false;
        metrics.commitRetryAttempted(commitRetryFailureClass);
    }

    private void recoverCommitRetryIfNeeded() {
        if (!commitRetryDegraded) {
            consecutiveCommitFailures = 0;
            commitRetryScheduled = false;
            return;
        }
        var failedAttempts = consecutiveCommitFailures;
        consecutiveCommitFailures = 0;
        commitRetryScheduled = false;
        commitRetryDegraded = false;
        metrics.commitRetryDegradedChanged(-1);
        metrics.commitRetryRecovered();
        log.atInfo()
            .setMessage("event=kafka_commit_retry_recovered failed_attempts={}")
            .addArgument(failedAttempts)
            .log();
    }

    private void clearCommitRetryWithoutRecovery() {
        consecutiveCommitFailures = 0;
        commitRetryScheduled = false;
        if (commitRetryDegraded) {
            commitRetryDegraded = false;
            metrics.commitRetryDegradedChanged(-1);
        }
    }

    private static CommitResolution resolutionOf(KafkaSourcePort.CommitOutcome outcome) {
        return switch (outcome) {
            case ACKNOWLEDGED -> CommitResolution.ACKNOWLEDGED;
            case RETRIABLE -> CommitResolution.RETRIABLE;
            case GENERATION_STALE -> CommitResolution.GENERATION_STALE;
            case OUTCOME_UNKNOWN -> CommitResolution.OUTCOME_UNKNOWN;
            case LATE_CALLBACK -> CommitResolution.LATE_CALLBACK;
        };
    }

    /**
     * Whether this partition may still be offered a commit position.
     *
     * <p>Map membership alone is not enough: during {@code onPartitionsRevoked} the entry is still present but
     * the generation is already revoked, and {@code kafkaLLD §5.7} has revocation beginning when the callback
     * is entered rather than when it returns. Re-offering there cannot succeed — a stale generation's commit is
     * rejected identically however often it is sent — and it consumes the interval force cancellation needs.
     */
    private boolean stillOwnsForCommit(TopicPartition topicPartition) {
        var state = partitions.get(topicPartition);
        return state != null && (state.lifecycleAllowsIntake() || orderlyShutdownStarted);
    }

    // ---------------------------------------------------------------- pause and resume

    private void applyPauseAndResumeDecisions() {
        for (var state : partitions.values()) {
            var shouldBePaused = !state.isReadable();
            if (shouldBePaused == state.isKafkaPaused()) {
                continue;
            }
            if (shouldBePaused) {
                port.pause(state.topicPartition());
            } else {
                port.resume(state.topicPartition());
            }
            state.setKafkaPaused(shouldBePaused);
        }
    }

    // ---------------------------------------------------------------- poll and deliver

    /**
     * Polls once and delivers what it returned.
     *
     * <p>{@code WakeupException} is caught here and only here. It is not a failure: it is the answer to a
     * wakeup this owner's own queue asked for, and {@code kafkaLLD §5.4} makes this loop the one controlled
     * boundary that may read it as "stop waiting and inspect the source-input queue". The adapter deliberately
     * propagates it rather than converting it to an empty result, because an empty result is indistinguishable
     * from a partition with nothing to read — so the boundary has to exist here or a routine queued input
     * unwinds the iteration.
     */
    private void pollAndDeliver() {
        Map<TopicPartition, List<PolledKafkaRecord>> polled;
        wakeupController.enterPoll(!sourceInputs.isEmpty());
        try {
            polled = port.poll(commitRetryPollWait());
        } catch (WakeupException wokenToInspectTheQueue) {
            log.atTrace().setMessage("Poll woken to inspect the source-input queue").log();
            return;
        } finally {
            // Consumed whether poll returned records, threw WakeupException, or failed, so an unconsumed wakeup
            // cannot land on the next Kafka operation.
            wakeupController.leavePollAndConsumeWakeup();
        }
        pollsSinceLastHeartbeat++;
        if (polled.values().stream().allMatch(List::isEmpty)) {
            emptyPollsSinceLastHeartbeat++;
        }
        var recordCount = polled.values().stream().mapToInt(List::size).sum();
        if (recordCount > 0) {
            log.atDebug()
                .setMessage("event=kafka_batch_polled partition_count={} record_count={}")
                .addArgument(() -> polled.values().stream().filter(records -> !records.isEmpty()).count())
                .addArgument(recordCount)
                .log();
        }
        polled.forEach(this::deliver);
    }

    private Duration commitRetryPollWait() {
        if (!commitRetryScheduled) {
            return UNBOUNDED_POLL_WAIT;
        }
        var remainingNanos = commitRetryNotBeforeNanos - monotonicNanos.getAsLong();
        return remainingNanos <= 0
            ? Duration.ZERO
            : Duration.ofNanos(remainingNanos);
    }

    private void deliver(TopicPartition topicPartition, List<PolledKafkaRecord> records) {
        if (records.isEmpty()) {
            // An empty poll completes no request; the partition stays resumed and the request outstanding.
            return;
        }
        var state = partitions.get(topicPartition);
        if (state == null) {
            throw new IllegalStateException("poll returned records for unassigned partition " + topicPartition);
        }
        // Paused before the batch reaches intake, so another poll cannot fetch more for this partition while
        // the current batch is still being applied (kafkaLLD §5.3).
        if (!state.isKafkaPaused()) {
            port.pause(topicPartition);
            state.setKafkaPaused(true);
        }
        var requestId = state.completeNextBatchEntitlement();
        // Stamped here, by the only component that knows the generation. The adapter returns raw records
        // precisely so it needs no generation map of its own to keep in step (kafkaLLD §5).
        var stamped = records.stream().map(raw -> {
            var recordId = new KafkaRecordId(state.generation(), raw.offset());
            var context = recordContextFactory.create(recordId, raw.serializedSizeBytes());
            if (context != null && recordContexts.putIfAbsent(recordId, context) != null) {
                context.complete(IReplayContexts.RecordDisposition.GENERATION_ENDED_UNCOMMITTED);
                throw new IllegalStateException("record context already exists for " + recordId);
            }
            return new ApplicationKafkaRecord(
                recordId,
                raw.logAppendTimeMillis(),
                raw.serializedSizeBytes(),
                raw.encodedEnvelope(),
                context
            );
        }).toList();
        var observedAtNanos = monotonicNanos.getAsLong();
        stamped.forEach(record -> {
            state.commitQueue().register(record.recordId(), record.logAppendTimeMillis());
            recordObservedAtNanos.put(record.recordId(), observedAtNanos);
        });
        state.countRecordsRead(stamped.size());
        metrics.recordsRead(stamped.size());
        recordsRead = Math.addExact(recordsRead, stamped.size());
        changeRecordsOutstanding(stamped.size());
        submitRequired(new ReplayIntakeInput.PartitionRecordBatch(requestId, stamped));
        log.atDebug()
            .setMessage(
                "event=kafka_input_offered topic={} partition={} generation={} batch={} "
                    + "record_count={} first_offset={} last_offset={}"
            )
            .addArgument(topicPartition::topic)
            .addArgument(topicPartition::partition)
            .addArgument(() -> state.generation().localSequence())
            .addArgument(requestId::localSequence)
            .addArgument(stamped::size)
            .addArgument(() -> stamped.getFirst().recordId().offset())
            .addArgument(() -> stamped.getLast().recordId().offset())
            .log();
    }

    // ---------------------------------------------------------------- rebalance callbacks

    /**
     * Pauses the whole resulting assignment before Kafka may fetch from it, because Kafka does not preserve
     * pause state across an assignment change ({@code kafkaLLD §5.2}). Partitions are resumed later, by
     * {@link #runOnce()}, and only those with an outstanding request and no other reason to stay paused.
     */
    public void onPartitionsAssigned(Collection<TopicPartition> assigned) {
        if (orderlyShutdownClosing || orderlyShutdownClosed) {
            return;
        }
        wakeupController.enterRebalanceCallback();
        try {
            for (var topicPartition : port.assignment()) {
                port.pause(topicPartition);
                var existing = partitions.get(topicPartition);
                if (existing != null) {
                    existing.setKafkaPaused(true);
                }
            }
            for (var topicPartition : assigned) {
                if (partitions.containsKey(topicPartition)) {
                    // Retained continuously: same generation, but its pause state was just reapplied.
                    continue;
                }
                beginGeneration(topicPartition);
            }
            log.atInfo()
                .setMessage(
                    "event=kafka_assignment_changed action=assigned changed_count={} "
                        + "assignment_count={} partitions={}"
                )
                .addArgument(assigned::size)
                .addArgument(() -> port.assignment().size())
                .addArgument(() -> summarizePartitions(assigned))
                .log();
        } finally {
            wakeupController.leaveRebalanceCallback();
        }
    }

    private void beginGeneration(TopicPartition topicPartition) {
        var generation = new PartitionGenerationId(topicPartition, nextGenerationSequence++);
        var state = new PartitionSourceState(generation);
        if (protocolViolationDetected || orderlyShutdownStarted) {
            state.endIntake();
        }
        // A newly assigned generation cannot reuse mutable state from the previous one, so both the state and
        // the commit queue are replaced rather than reset (kafkaLLD §5.2).
        partitions.put(topicPartition, state);
        stagedCommitPoints.remove(topicPartition);
        clearCommitRetryIfNoWork();
        // Gated on whether any earlier generation of this partition is still cleaning up, which map membership
        // cannot answer: the predecessor's state was removed when it was revoked, before this point.
        if (cleanupOutstanding.containsKey(topicPartition)) {
            state.setPriorGenerationCleanupPending(true);
        }
        submitRequired(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
        if (orderlyShutdownStarted) {
            cleanupOutstanding
                .computeIfAbsent(topicPartition, ignored -> new LinkedHashSet<>())
                .add(generation);
            submitRequired(new ReplayIntakeInput.GracefulGenerationCancellation(
                generation,
                CancellationGrace.Shutdown.INSTANCE
            ));
        }
    }

    /**
     * The bounded-quiescence sequence from {@code kafkaLLD §15.1} and {@code procCommit §9.2}.
     *
     * <p>What this deliberately does <strong>not</strong> do is issue a blocking commit before any
     * cancellation has been delivered. That specific shape is defect {@code D5}: it can exceed
     * {@code max.poll.interval.ms} and trigger a rebalance from inside a rebalance. Commits still happen
     * here — they arrive as inputs while the callback waits, and are submitted from that wait.
     *
     * <p>The callback returns when force cancellation is <em>accepted</em> by replay intake, which proves
     * intake will observe it unless the process fails. It does not wait for cleanup, which continues
     * afterwards while the successor generation stays paused.
     */
    public void onPartitionsRevoked(Collection<TopicPartition> revoked) throws InterruptedException {
        if (orderlyShutdownClosing || orderlyShutdownClosed) {
            return;
        }
        wakeupController.enterRebalanceCallback();
        try {
            log.atInfo()
                .setMessage(
                    "event=kafka_assignment_changed action=revoked changed_count={} partitions={}"
                )
                .addArgument(revoked::size)
                .addArgument(() -> summarizePartitions(revoked))
                .log();
            if (orderlyShutdownStarted) {
                revoked.forEach(this::retireGeneration);
                return;
            }
            var generations = new ArrayList<PartitionGenerationId>();
            for (var topicPartition : revoked) {
                var state = partitions.get(topicPartition);
                if (state == null) {
                    continue;
                }
                state.endIntake();
                generations.add(state.generation());
            }
            if (generations.isEmpty()) {
                return;
            }

            // Registered before graceful cancellation is submitted, not after the wait. Cleanup can complete
            // *during* the grace interval -- intake sends GenerationCleanupFinished as soon as its tracker
            // empties, which for a generation with little in flight is immediately -- and a completion arriving
            // before its obligation was recorded used to be discarded as unmatched. The generation was then
            // registered anyway when it retired, so a successor waited on a cleanup already reported and never
            // sent again: a permanently paused partition.
            generations.forEach(generation -> cleanupOutstanding
                .computeIfAbsent(generation.topicPartition(), ignored -> new LinkedHashSet<>())
                .add(generation));

            var deadlineNanos = monotonicNanos.getAsLong() + cancellationGrace.toNanos();
            var deadline = new CancellationDeadline(deadlineNanos);
            generations.forEach(generation -> submitRequired(
                new ReplayIntakeInput.GracefulGenerationCancellation(
                    generation,
                    new CancellationGrace.Revocation(deadline)
                )
            ));

            wakeupController.recordGraceWaitEnded(
                awaitGraceDeadlineProcessingInputs(deadline, generations)
            );

            // §4.1: "The Kafka source therefore submits it unconditionally, including when the grace wait
            // returned early because every revoked generation had already reported cleanup." Cancelling the
            // generation's work is this source's obligation and a successor assignment of the same partition
            // waits on it, while whether anything remains to cancel is intake's to determine -- and an
            // already-cleaned delivery is inert there, so predicting that answer here would only hold up the
            // successor to save a message that changes nothing.
            generations.forEach(generation -> submitRequired(
                new ReplayIntakeInput.ForceGenerationCancellation(generation)
            ));
            revoked.forEach(this::retireGeneration);
        } finally {
            wakeupController.leaveRebalanceCallback();
        }
    }

    /**
     * Drops a generation's local state and records what it achieved, per {@code kafkaLLD §15.4}.
     *
     * <p>The measurement is taken for every retiring generation, including one that committed nothing and one
     * that never became readable. A generation missing from the measurement is indistinguishable from one that
     * committed nothing, which is precisely the case it exists to reveal ({@code procCommit §9.5}).
     */
    private void retireGeneration(TopicPartition topicPartition) {
        retireGeneration(topicPartition, true);
    }

    private void retireGeneration(TopicPartition topicPartition, boolean recordRebalanceRetirement) {
        var state = partitions.remove(topicPartition);
        // The old generation must not go on offering a commit: kafkaLLD §5.7 has it neither retrying nor
        // waiting, with the next assigned position deciding redelivery instead.
        stagedCommitPoints.remove(topicPartition);
        clearCommitRetryIfNoWork();
        if (state == null) {
            return;
        }
        var activeSubmission = inFlightCommitOperation == null
            ? null
            : inFlightCommitOperation.submitted.get(topicPartition);
        if (activeSubmission != null && !activeSubmission.generation().equals(state.generation())) {
            activeSubmission = null;
        }
        // Not registered here. Registration happens before graceful cancellation is submitted, so that a
        // completion arriving during the grace interval matches an obligation that already exists; re-adding
        // here would resurrect an obligation that completion had already discharged.
        // The diagnostic qualifies the two measurements without interpreting them. NONE_OBSERVED means only
        // that no unresolved or unknown commit outcome is known; kafkaLLD §15.4 leaves conclusions about a zero
        // count to the observer.
        if (activeSubmission != null) {
            state.markAsyncCommitUnresolved();
        }
        recordRetirementConservation(state, activeSubmission);
        completeGenerationRecordContexts(state.generation());
        recordObservedAtNanos.keySet().removeIf(
            recordId -> recordId.generation().equals(state.generation())
        );
        log.atInfo()
            .setMessage(
                "event=kafka_generation_retiring topic={} partition={} generation={} "
                    + "records_committed={} records_read={} commit_uncertainty={}"
            )
            .addArgument(() -> state.topicPartition().topic())
            .addArgument(() -> state.topicPartition().partition())
            .addArgument(() -> state.generation().localSequence())
            .addArgument(state::recordsCommitted)
            .addArgument(state::recordsRead)
            .addArgument(state::commitUncertainty)
            .log();
        if (recordRebalanceRetirement) {
            wakeupController.recordGenerationRetired(
                state.generation().toString(),
                state.recordsCommitted(),
                state.recordsRead()
            );
        } else {
            wakeupController.recordGenerationRetiredAfterOrderlyShutdown(
                state.recordsCommitted(),
                state.recordsRead()
            );
        }
    }

    private void clearCommitRetryIfNoWork() {
        if (stagedCommitPoints.isEmpty() && inFlightCommitOperation == null) {
            clearCommitRetryWithoutRecovery();
        }
    }

    private void recordRetirementConservation(
        PartitionSourceState state,
        SubmittedCommitPoint activeSubmission
    ) {
        var staged = state.recordsAwaitingCommit();
        var completedBehindHead = state.commitQueue().completedBehindHeadCount();
        var cancelled = state.commitQueue().unfinishedCount();
        var commitIneligible = state.commitQueue().commitIneligibleCount();
        var activeUnknown = activeSubmission == null ? 0 : activeSubmission.recordsCovered().total();

        var abandonedRejected = staged.rejected();
        var abandonedUnknown = Math.addExact(staged.unknown(), activeUnknown);
        var abandonedUnsubmitted = Math.addExact(staged.unsubmitted(), completedBehindHead);
        var accounted = Math.addExact(
            Math.addExact(state.recordsCommitted(), commitIneligible),
            Math.addExact(
                Math.addExact(cancelled, abandonedRejected),
                Math.addExact(abandonedUnknown, abandonedUnsubmitted)
            )
        );
        if (accounted != state.recordsRead()) {
            throw new IllegalStateException(
                "record conservation failed while retiring "
                    + state.generation()
                    + ": read="
                    + state.recordsRead()
                    + ", accounted="
                    + accounted
            );
        }

        recordAbandonment(AbandonmentCause.REJECTED, abandonedRejected);
        recordAbandonment(AbandonmentCause.UNKNOWN, abandonedUnknown);
        recordAbandonment(AbandonmentCause.UNSUBMITTED, abandonedUnsubmitted);
        if (cancelled > 0) {
            metrics.recordsCancelled(cancelled);
        }
        var newlyTerminal = Math.addExact(
            cancelled,
            Math.addExact(abandonedRejected, Math.addExact(abandonedUnknown, abandonedUnsubmitted))
        );
        if (newlyTerminal > 0) {
            changeRecordsOutstanding(-newlyTerminal);
        }
    }

    private void changeRecordsOutstanding(long delta) {
        recordsOutstanding = Math.addExact(recordsOutstanding, delta);
        if (recordsOutstanding < 0) {
            throw new IllegalStateException(
                "Kafka source records outstanding became negative: " + recordsOutstanding
            );
        }
        metrics.recordsOutstandingChanged(delta);
    }

    private void logHeartbeatIfDue() {
        var nowNanos = monotonicNanos.getAsLong();
        if (nowNanos - nextHeartbeatNanos < 0) {
            return;
        }
        nextHeartbeatNanos = nowNanos + heartbeatIntervalNanos;
        publishDiagnosticSnapshot();

        var generations = new LinkedHashMap<Integer, Long>();
        var commitHeads = new LinkedHashMap<Integer, String>();
        var commitTails = new LinkedHashMap<Integer, Long>();
        var partitionStates = new ArrayList<String>();
        var queueSize = 0;
        for (var state : partitions.values()) {
            var snapshot = state.commitQueue().snapshot();
            var partition = state.topicPartition().partition();
            generations.put(partition, state.generation().localSequence());
            queueSize = Math.addExact(queueSize, snapshot.size());
            if (snapshot.greatestObservedOffset() >= 0) {
                commitTails.put(partition, snapshot.greatestObservedOffset());
            }
            snapshot.headRecord().ifPresent(headRecord -> {
                var observedAt = recordObservedAtNanos.get(headRecord);
                var age = observedAt == null
                    ? "unknown"
                    : formatDurationNanos(Math.max(0, nowNanos - observedAt));
                commitHeads.put(
                    partition,
                    "{offset=" + headRecord.offset() + ", age=" + age + "}"
                );
            });
            partitionStates.add(
                "{partition="
                    + partition
                    + ", generation="
                    + state.generation().localSequence()
                    + ", paused="
                    + state.isKafkaPaused()
                    + ", readable="
                    + state.isReadable()
                    + ", queueSize="
                    + snapshot.size()
                    + ", unfinished="
                    + snapshot.unfinishedCount()
                    + ", awaitingCommit="
                    + state.recordsAwaitingCommit().total()
                    + ", commitUncertainty="
                    + state.commitUncertainty()
                    + "}"
            );
        }

        var pendingCommitPartitions = new LinkedHashSet<>(stagedCommitPoints.keySet());
        if (inFlightCommitOperation != null) {
            pendingCommitPartitions.addAll(inFlightCommitOperation.submitted.keySet());
        }
        var polls = pollsSinceLastHeartbeat;
        var emptyPolls = emptyPollsSinceLastHeartbeat;
        var commits = commitsSinceLastHeartbeat;
        pollsSinceLastHeartbeat = 0;
        emptyPollsSinceLastHeartbeat = 0;
        commitsSinceLastHeartbeat = 0;

        HEARTBEAT_LOGGER.atInfo()
            .setMessage(
                "generation={} partitions={} inflight={} commitHead={} commitTail={} queueSize={} "
                    + "polls={} emptyPolls={} commits={} readyToCommit={} pendingCommitPartitions={} "
                    + "recordsRead={} recordsCommitted={} partitionStates={} shutdown={}"
            )
            .addArgument(generations)
            .addArgument(() -> partitions.keySet().stream().map(TopicPartition::partition).toList())
            .addArgument(recordsOutstanding)
            .addArgument(commitHeads)
            .addArgument(commitTails)
            .addArgument(queueSize)
            .addArgument(polls)
            .addArgument(emptyPolls)
            .addArgument(commits)
            .addArgument(() -> !stagedCommitPoints.isEmpty())
            .addArgument(pendingCommitPartitions.size())
            .addArgument(recordsRead)
            .addArgument(recordsCommitted)
            .addArgument(partitionStates)
            .addArgument(this::shutdownState)
            .log();
    }

    private String shutdownState() {
        if (orderlyShutdownClosed) {
            return "closed";
        }
        if (orderlyShutdownClosing) {
            return "closing";
        }
        if (orderlyShutdownStarted) {
            return "draining";
        }
        if (protocolViolationDetected) {
            return "protocol-fatal";
        }
        return "running";
    }

    private static String formatDurationNanos(long nanos) {
        var totalSeconds = Duration.ofNanos(nanos).toSeconds();
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        if (totalSeconds < 3_600) {
            return (totalSeconds / 60) + "m" + (totalSeconds % 60) + "s";
        }
        return (totalSeconds / 3_600) + "h" + ((totalSeconds % 3_600) / 60) + "m";
    }

    private void recordAbandonment(AbandonmentCause cause, long count) {
        if (count > 0) {
            metrics.recordsAbandonedAtRevocation(cause, count);
        }
    }

    private void completeCommittedRecordContexts(
        PartitionGenerationId generation,
        long nextPosition
    ) {
        var completed = recordContexts.entrySet().stream()
            .filter(entry -> entry.getKey().generation().equals(generation))
            .filter(entry -> entry.getKey().offset() < nextPosition)
            .map(Map.Entry::getKey)
            .toList();
        completed.forEach(recordId ->
            completeRecordContext(recordId, IReplayContexts.RecordDisposition.COMMITTED)
        );
    }

    private void completeGenerationRecordContexts(PartitionGenerationId generation) {
        var unfinished = recordContexts.keySet().stream()
            .filter(recordId -> recordId.generation().equals(generation))
            .toList();
        unfinished.forEach(recordId ->
            completeRecordContext(
                recordId,
                IReplayContexts.RecordDisposition.GENERATION_ENDED_UNCOMMITTED
            )
        );
    }

    private void completeRecordContext(
        KafkaRecordId recordId,
        IReplayContexts.RecordDisposition disposition
    ) {
        var context = recordContexts.remove(recordId);
        if (context != null) {
            context.complete(disposition);
        }
    }

    /**
     * Processes commit and lifecycle inputs while waiting out the grace interval. The wait is signalled by
     * queue submission rather than by a Kafka wakeup, which this callback is protected from receiving.
     *
     * <p>No record batch is admitted here, so a batch request arriving during the wait is applied to state
     * but produces no poll.
     */
    private boolean awaitGraceDeadlineProcessingInputs(
        CancellationDeadline deadline,
        List<PartitionGenerationId> revokedGenerations
    ) throws InterruptedException {
        var terminalCommitAttempted = new LinkedHashSet<PartitionGenerationId>();
        var terminalCommitLaunchDeadline = terminalCommitLaunchDeadline(deadline);
        // The wait is injected, not called on the queue directly. kafkaLLD §15.1 requires one monotonic source
        // for the deadline, and no arrangement of durations achieves that with a raw Object.wait: waiting always
        // elapses in real time, so "the wait expired" and "the deadline passed" would be two different clocks
        // answering one question. GraceIntervalWait returns only when the deadline's own clock says so.
        //
        // Apply everything already queued after intake has ended and graceful cancellation has been accepted.
        // This gives progress completed at the revocation boundary the chance to supersede an older point that
        // was waiting in ordinary retry backoff before the generation spends its one terminal commit attempt.
        applyQueuedInputs();
        if (allCleanupReported(revokedGenerations)) {
            submitRevocationCommit(deadline, revokedGenerations, terminalCommitAttempted);
            return true;
        }

        // Keep accumulating normally completed progress until either cleanup proves that no more can arrive or
        // the final-commit launch boundary is reached. The reserved tail is still part of the original grace
        // interval; it merely makes the terminal opportunity real instead of waiting until its bound is zero.
        while (graceWait.awaitInputUntil(terminalCommitLaunchDeadline)) {
            applyQueuedInputs();
            // procCommit:1308: "If the generation has no unfinished work, the callback returns immediately."
            // Cleanup completion is how that becomes observable -- intake sends it once its tracker empties --
            // so waiting out the rest of the interval after it arrives holds the whole consumer, and the
            // group's rebalance, for nothing.
            if (allCleanupReported(revokedGenerations)) {
                submitRevocationCommit(deadline, revokedGenerations, terminalCommitAttempted);
                return true;
            }
        }

        submitRevocationCommit(deadline, revokedGenerations, terminalCommitAttempted);
        if (allCleanupReported(revokedGenerations)) {
            return true;
        }
        while (graceWait.awaitInputUntil(deadline)) {
            applyQueuedInputs();
            // Generations without an eligible point at the launch boundary may spend their one terminal
            // attempt if progress becomes eligible during the reserved tail. A generation already attempted
            // is filtered out, so this cannot become a retry loop.
            submitRevocationCommit(deadline, revokedGenerations, terminalCommitAttempted);
            if (allCleanupReported(revokedGenerations)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reserves the smaller of half the configured grace interval and one second for the terminal commit.
     *
     * <p>The other half remains available for already-started replay and tuple work to finish. Long grace
     * intervals do not turn the final Kafka call into a multi-second block, while short intervals still divide
     * their available time between useful completion and the one bounded broker opportunity.
     */
    private CancellationDeadline terminalCommitLaunchDeadline(CancellationDeadline deadline) {
        var graceNanos = cancellationGrace.toNanos();
        var halfGraceNanos = graceNanos / 2 + graceNanos % 2;
        var reserveNanos = Math.min(
            halfGraceNanos,
            MAXIMUM_REVOCATION_COMMIT_RESERVE.toNanos()
        );
        return new CancellationDeadline(deadline.monotonicDeadlineNanos() - reserveNanos);
    }

    /** True once no revoked generation is still awaiting cleanup, which is procCommit:1308's condition. */
    private boolean allCleanupReported(List<PartitionGenerationId> revokedGenerations) {
        return revokedGenerations.stream().noneMatch(generation -> {
            var outstanding = cleanupOutstanding.get(generation.topicPartition());
            return outstanding != null && outstanding.contains(generation);
        });
    }

    /**
     * Commits from inside the revocation callback, bounded by what is left of the grace interval.
     *
     * <p>Synchronous, because an asynchronous callback needs a later {@code poll()} and this generation is gone
     * before one happens. Bounded, because the callback is holding the whole consumer: nothing is polled for any
     * partition while it runs, the group's rebalance waits on it, and overrunning
     * {@code max.poll.interval.ms} — which is also the rebalance timeout — fences the member and converts this
     * graceful revocation into a lost one, discarding every staged commit point.
     *
     * <p>Attempted however little grace remains. The bound is what confines the call to the deadline, so a
     * sliver is a small budget rather than a hazard, and declining to try would discard a position a fast
     * round-trip could still have committed — a revoked generation's position is discarded anyway once the
     * callback returns, so attempting is never the worse choice.
     */
    private void submitRevocationCommit(
        CancellationDeadline deadline,
        List<PartitionGenerationId> revokedGenerations,
        Set<PartitionGenerationId> terminalCommitAttempted
    ) {
        if (stagedCommitPoints.isEmpty()) {
            return;
        }
        if (inFlightCommitOperation != null) {
            // §5.7 allows one commit operation at a time, and an asynchronous one cannot be taken back: Kafka
            // guarantees its callback runs before the next commitSync returns, so it resolves *during* any
            // commit issued here — crediting its own record count, which this one would then credit again.
            // Forgetting it locally does not cancel it. Waiting for it is not possible either, since its
            // callback needs a poll that this callback is preventing, so the only sound choice is to let it be
            // the operation that decides. Its positions are not re-offered: §5.7 has a revoked generation
            // discard rather than retry, and the next assigned position decides redelivery.
            log.atDebug().setMessage("Not attempting a revocation commit while {} is in flight")
                .addArgument(() -> inFlightCommitOperation.submitted.keySet()).log();
            return;
        }
        var terminalPartitions = new LinkedHashSet<TopicPartition>();
        for (var generation : revokedGenerations) {
            if (terminalCommitAttempted.contains(generation)) {
                continue;
            }
            var state = partitions.get(generation.topicPartition());
            if (state != null
                && state.generation().equals(generation)
                && stagedCommitPoints.containsKey(generation.topicPartition())) {
                terminalPartitions.add(generation.topicPartition());
            }
        }
        if (terminalPartitions.isEmpty()) {
            return;
        }
        var nowNanos = monotonicNanos.getAsLong();
        // Attempted however little grace is left. The bound is what keeps the call inside the deadline, so a
        // sliver of remaining time is a small budget rather than a hazard -- and skipping the attempt would
        // discard a position that a fast round-trip could still have committed, since a revoked generation's
        // position is discarded either way once the callback returns.
        var remainingNanos = deadline.remainingNanos(nowNanos);
        if (remainingNanos <= 0) {
            // §5.7: "a commit that cannot finish within the remaining grace must not be started". Zero is the
            // absence of a remainder rather than a minimum, so this does not reintroduce the floor the owner
            // ruled out -- and starting here charges every submitted partition a spurious unknown outcome,
            // including partitions that are being retained.
            return;
        }
        var submitted = detachForSubmission(terminalPartitions);
        submitted.values().forEach(detail -> terminalCommitAttempted.add(detail.generation()));
        var operation = new CommitOperation(nextCommitOperationId++, submitted);
        log.atDebug()
            .setMessage(
                "event=kafka_commit_submitted mode=sync operation={} partition_count={} "
                    + "remaining_grace_ms={} partitions={}"
            )
            .addArgument(() -> operation.operationId)
            .addArgument(submitted::size)
            .addArgument(() -> Duration.ofNanos(remainingNanos).toMillis())
            .addArgument(() -> summarizePartitions(submitted.keySet()))
            .log();
        wakeupController.enterProtectedOperation();
        KafkaSourcePort.CommitResult result;
        try {
            result = port.commitSync(commitPointsOf(submitted), Duration.ofNanos(remainingNanos));
        } catch (WakeupException absorbedByTheCommit) {
            // §5.7 classifies a wakeup-interrupted commit as an unknown outcome. Recording the absorption is
            // what keeps the callback's exit able to issue its deferred wakeup: the commit spent the one that
            // was outstanding, and a controller that still believes it is outstanding issues nothing.
            wakeupController.onWakeupAbsorbedByProtectedOperation();
            result = KafkaSourcePort.CommitResult.failed(
                KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN,
                KafkaSourcePort.CommitFailureClass.WAKEUP
            );
        } finally {
            wakeupController.leaveProtectedOperation();
        }
        // The synchronous call was accepted by the client. A failed batched operation may have applied some
        // positions, so no returned outcome proves the broker position stayed put. The common resolution path
        // restores that uncertain coverage and marks each still-live generation accordingly.
        resolveCommitOperation(
            operation,
            result,
            false,
            CommitOperationMode.REVOCATION_TERMINAL
        );
    }

    /**
     * Revocation without the chance to commit or cancel gracefully. Local state is dropped; the next assigned
     * position decides redelivery.
     */
    public void onPartitionsLost(Collection<TopicPartition> lost) {
        if (orderlyShutdownClosing || orderlyShutdownClosed) {
            return;
        }
        wakeupController.enterRebalanceCallback();
        try {
            log.atWarn()
                .setMessage(
                    "event=kafka_assignment_changed action=lost changed_count={} partitions={}"
                )
                .addArgument(lost::size)
                .addArgument(() -> summarizePartitions(lost))
                .log();
            if (orderlyShutdownStarted) {
                lost.forEach(this::retireGeneration);
                return;
            }
            for (var topicPartition : lost) {
                var state = partitions.get(topicPartition);
                if (state != null) {
                    state.endIntake();
                    // Registered before force cancellation is submitted, for the same reason the revocation
                    // path does: intake may report cleanup complete before this method returns, and a
                    // completion with no obligation recorded yet would be discarded as unmatched.
                    cleanupOutstanding
                        .computeIfAbsent(topicPartition, ignored -> new LinkedHashSet<>())
                        .add(state.generation());
                    submitRequired(
                        new ReplayIntakeInput.ForceGenerationCancellation(state.generation())
                    );
                }
                retireGeneration(topicPartition);
            }
        } finally {
            wakeupController.leaveRebalanceCallback();
        }
    }

    // ---------------------------------------------------------------- observation

    public Optional<PartitionSourceState> partitionState(TopicPartition topicPartition) {
        return Optional.ofNullable(partitions.get(topicPartition));
    }

    public Optional<Long> stagedCommitPosition(TopicPartition topicPartition) {
        return Optional.ofNullable(stagedCommitPoints.get(topicPartition))
            .map(KafkaSourcePort.CommitPoint::nextPosition);
    }

    /**
     * Returns the last immutable owner-thread publication. The monitor thread must use this copy rather than
     * reading the owner-confined maps, queues, or commit operation directly.
     */
    public DiagnosticSnapshot diagnosticSnapshot() {
        return diagnosticSnapshot;
    }

    private void publishDiagnosticSnapshot() {
        var partitionCopies = new LinkedHashMap<PartitionGenerationId, PartitionDiagnostic>();
        var recordStateSources = new LinkedHashMap<PartitionGenerationId, RecordStateSource>();
        for (var state : partitions.values()) {
            var generation = state.generation();
            var topicPartition = state.topicPartition();
            var queue = state.commitQueue().snapshot();
            var stagedPosition = Optional.ofNullable(stagedCommitPoints.get(topicPartition))
                .map(KafkaSourcePort.CommitPoint::nextPosition);
            var inFlightPosition = inFlightCommitOperation == null
                ? Optional.<Long>empty()
                : Optional.ofNullable(inFlightCommitOperation.submitted.get(topicPartition))
                    .filter(submitted -> submitted.generation().equals(generation))
                    .map(SubmittedCommitPoint::nextPosition);
            partitionCopies.put(
                generation,
                new PartitionDiagnostic(
                    generation,
                    state.isKafkaPaused(),
                    state.isReadable(),
                    state.isAssignmentBootstrapPending(),
                    state.outstandingRequest().isPresent(),
                    state.isPriorGenerationCleanupPending(),
                    state.lifecycleAllowsIntake(),
                    queue.size(),
                    queue.unfinishedCount(),
                    queue.headRecord(),
                    queue.greatestObservedOffset(),
                    state.recordsAwaitingCommit().total(),
                    state.commitUncertainty(),
                    stagedPosition,
                    inFlightPosition
                )
            );
            recordStateSources.put(
                generation,
                new RecordStateSource(queue, state.commitUncertainty(), stagedPosition, inFlightPosition)
            );
        }
        diagnosticSnapshot = new DiagnosticSnapshot(
            partitionCopies,
            recordStateSources,
            recordContexts.keySet()
        );
    }

    private static RecordDiagnosticState diagnosticState(KafkaRecordId recordId, RecordStateSource source) {
        var queueState = source.queue().recordStates().get(recordId);
        if (queueState != null) {
            return switch (queueState) {
                case PROCESSING -> RecordDiagnosticState.PROCESSING;
                case PROCESSING_COMPLETE ->
                    RecordDiagnosticState.PROCESSING_COMPLETE_WAITING_FOR_COMMIT_HEAD;
                case COMMIT_INELIGIBLE -> RecordDiagnosticState.COMMIT_INELIGIBLE;
            };
        }
        if (source.inFlightCommitPosition().filter(position -> position > recordId.offset()).isPresent()) {
            return RecordDiagnosticState.COMMIT_IN_FLIGHT;
        }
        var uncertain = source.commitUncertainty() != PartitionSourceState.CommitUncertainty.NONE_OBSERVED;
        if (source.stagedCommitPosition().filter(position -> position > recordId.offset()).isPresent()) {
            return uncertain ? RecordDiagnosticState.COMMIT_UNCERTAIN : RecordDiagnosticState.READY_TO_COMMIT;
        }
        return uncertain ? RecordDiagnosticState.COMMIT_UNCERTAIN : RecordDiagnosticState.UNKNOWN;
    }

    private Optional<PartitionSourceState> currentStateFor(PartitionGenerationId generation) {
        var state = partitions.get(generation.topicPartition());
        return state != null && state.generation().equals(generation) ? Optional.of(state) : Optional.empty();
    }

    private static String summarizePartitions(Collection<TopicPartition> partitions) {
        var listed = partitions.stream()
            .limit(MAX_LOGGED_PARTITIONS)
            .map(TopicPartition::toString)
            .toList();
        var omitted = Math.max(0, partitions.size() - listed.size());
        return omitted == 0 ? listed.toString() : listed + " omitted=" + omitted;
    }

    /**
     * Submits an input replay intake must receive, and ends the process if it is refused.
     *
     * <p>{@code kafkaLLD §4.1} is explicit: "An input submission needed for correctness must report
     * acceptance. Queue rejection or an unexpected failure to submit is process-fatal." Every submission this
     * owner makes is in that class. A dropped {@code PartitionRecordBatch} strands records already registered
     * in the commit queue, so that partition's committed position never advances again; a dropped
     * {@code ForceGenerationCancellation} is worse, because {@code §15.2} makes accepting it the thing that
     * licenses {@code onPartitionsRevoked} to return and discard the generation's state — intake would then
     * never learn the generation ended.
     *
     */
    private void submitRequired(ReplayIntakeInput input) {
        if (!intakeInputs.submit(input)) {
            throw new RequiredSubmissionRejected(input);
        }
    }

    /** A submission {@code kafkaLLD §4.1} requires to be accepted was refused. Ends the process. */
    public static final class RequiredSubmissionRejected extends IllegalStateException {
        RequiredSubmissionRejected(ReplayIntakeInput input) {
            super("replay intake refused a required input: " + input);
        }
    }

    /** Ends the process path for invalid capture input; carries the offending record for diagnostics. */
    public static final class CaptureProtocolViolation extends IllegalStateException {
        private final transient KafkaRecordId recordId;

        CaptureProtocolViolation(KafkaSourceInput.CaptureProtocolViolationDetected detected) {
            super("capture protocol violation at " + detected.recordId() + ": " + detected.diagnostic());
            this.recordId = detected.recordId();
        }

        public KafkaRecordId recordId() {
            return recordId;
        }
    }
}
