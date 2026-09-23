/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;
import org.opensearch.migrations.replay.kafkasource.KafkaSourcePort;

import org.apache.kafka.common.TopicPartition;

/**
 * A {@link KafkaSourcePort} that returns only what a test scripted, and records every call the owner made.
 *
 * <p>The owner drives this, not the other way round: a test scripts poll results, calls
 * {@code KafkaSourceOwner.runOnce()}, and asserts on {@link #observations()}. Nothing here decides anything,
 * so an assertion about pause ordering or commit positions is an assertion about the owner.
 *
 * <p>No broker, no threads, no sleeps. Timing-dependent behaviour — that a wakeup shortens a real blocking
 * poll — cannot be shown here and is covered against a real broker instead; everything else is deterministic
 * and belongs here.
 */
public final class PumpedKafkaSource implements KafkaSourcePort {

    /** Every call the owner made, in order. Ordering between them is the point of most assertions. */
    public sealed interface Observation permits
        PartitionPaused,
        PartitionResumed,
        Polled,
        CommitSubmittedAsync,
        CommitAttempted {}

    public record PartitionPaused(TopicPartition topicPartition) implements Observation {}

    public record PartitionResumed(TopicPartition topicPartition) implements Observation {}

    /** One poll and the partitions it returned records for, so an empty poll is distinguishable. */
    public record Polled(Set<TopicPartition> partitionsReturned) implements Observation {}

    /** An asynchronous submission from the ordinary loop; its outcome arrives at a later {@link #poll()}. */
    public record CommitSubmittedAsync(Map<TopicPartition, Long> nextPositions) implements Observation {}

    /**
     * A bounded synchronous submission from inside a rebalance callback.
     *
     * @param bound what the owner allowed it, which is the observable form of "cannot hold the callback past
     *              the grace deadline" — assert on this rather than on elapsed time
     */
    public record CommitAttempted(Map<TopicPartition, Long> nextPositions, Duration bound)
        implements Observation {}

    private final Set<TopicPartition> assignment = new LinkedHashSet<>();
    private final Set<TopicPartition> paused = new LinkedHashSet<>();
    private final Map<TopicPartition, Long> committedPositions = new LinkedHashMap<>();
    private final Deque<Map<TopicPartition, List<ApplicationKafkaRecord>>> scriptedPolls = new ArrayDeque<>();
    private final List<Observation> observations = new ArrayList<>();
    private final Deque<ThrowingRunnable> scriptedRebalances = new ArrayDeque<>();
    /** Async commits awaiting a poll to resolve them, exactly as the real client defers its callbacks. */
    private final Deque<Runnable> pendingAsyncCommits = new ArrayDeque<>();
    private final LongConsumer clockAdvance;
    private CommitOutcome nextCommitOutcome = CommitOutcome.ACKNOWLEDGED;
    private RuntimeException pollFailure;
    private Duration commitDuration;

    /** A rebalance callback can throw, because {@code onPartitionsRevoked} waits and can be interrupted. */
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public PumpedKafkaSource(Collection<TopicPartition> assignedPartitions) {
        this(assignedPartitions, nanos -> {});
    }

    /**
     * @param clockAdvance advances the same injected clock the owner reads, so a scripted commit duration is
     *                     observable to the owner's deadline arithmetic. Without it a grace-interval test could
     *                     only reach its deadline by sleeping
     */
    public PumpedKafkaSource(Collection<TopicPartition> assignedPartitions, LongConsumer clockAdvance) {
        assignment.addAll(assignedPartitions);
        this.clockAdvance = Objects.requireNonNull(clockAdvance, "clockAdvance");
    }

    // ---------------------------------------------------------------- scripting

    /** Scripts one poll result. An absent or empty entry is how an empty poll is expressed. */
    public void scriptPoll(Map<TopicPartition, List<ApplicationKafkaRecord>> result) {
        scriptedPolls.add(Map.copyOf(result));
    }

    public void scriptEmptyPoll() {
        scriptedPolls.add(Map.of());
    }

    /**
     * Fires {@code callback} from inside the next {@link #poll()}, which is where Kafka delivers rebalance
     * callbacks. Calling the owner's {@code onPartitionsAssigned} or {@code onPartitionsRevoked} directly
     * would exercise a state that cannot occur: the wakeup controller requires a poll to be in progress,
     * precisely because a callback always runs within one.
     */
    public void scriptRebalanceDuringNextPoll(ThrowingRunnable callback) {
        scriptedRebalances.add(Objects.requireNonNull(callback));
    }

    /** Makes the next poll throw. A poll failure is fatal and must not become an empty success. */
    public void scriptPollFailure(RuntimeException failure) {
        pollFailure = Objects.requireNonNull(failure);
    }

    public void scriptCommitOutcome(CommitOutcome outcome) {
        nextCommitOutcome = Objects.requireNonNull(outcome);
    }

    public void setCommittedPosition(TopicPartition topicPartition, long position) {
        committedPositions.put(topicPartition, position);
    }

    public void addAssignedPartition(TopicPartition topicPartition) {
        assignment.add(topicPartition);
    }

    // ---------------------------------------------------------------- KafkaSourcePort

    @Override
    public Set<TopicPartition> assignment() {
        return Set.copyOf(assignment);
    }

    @Override
    public Map<TopicPartition, List<ApplicationKafkaRecord>> poll() {
        // Async commit callbacks are delivered from inside poll, which is where the real client delivers them.
        while (!pendingAsyncCommits.isEmpty()) {
            pendingAsyncCommits.removeFirst().run();
        }
        while (!scriptedRebalances.isEmpty()) {
            var rebalance = scriptedRebalances.removeFirst();
            try {
                rebalance.run();
            } catch (RuntimeException runtimeFailure) {
                throw runtimeFailure;
            } catch (Exception checkedFailure) {
                throw new IllegalStateException("scripted rebalance callback failed", checkedFailure);
            }
        }
        if (pollFailure != null) {
            var failure = pollFailure;
            pollFailure = null;
            throw failure;
        }
        var result = scriptedPolls.isEmpty()
            ? Map.<TopicPartition, List<ApplicationKafkaRecord>>of()
            : scriptedPolls.removeFirst();
        // Only paused partitions are withheld, which is what makes "paused before the next poll" testable:
        // a partition the owner failed to pause keeps yielding records.
        var delivered = new LinkedHashMap<TopicPartition, List<ApplicationKafkaRecord>>();
        result.forEach((topicPartition, records) -> {
            if (!paused.contains(topicPartition)) {
                delivered.put(topicPartition, records);
            }
        });
        observations.add(new Polled(Set.copyOf(delivered.keySet())));
        return delivered;
    }

    @Override
    public void pause(TopicPartition topicPartition) {
        paused.add(topicPartition);
        observations.add(new PartitionPaused(topicPartition));
    }

    @Override
    public void resume(TopicPartition topicPartition) {
        paused.remove(topicPartition);
        observations.add(new PartitionResumed(topicPartition));
    }

    @Override
    public Optional<Long> committedPosition(TopicPartition topicPartition) {
        return Optional.ofNullable(committedPositions.get(topicPartition));
    }

    @Override
    public void commitAsync(
        Map<TopicPartition, Long> nextPositions,
        BiConsumer<Map<TopicPartition, Long>, CommitOutcome> onResolved
    ) {
        var submitted = Map.copyOf(nextPositions);
        observations.add(new CommitSubmittedAsync(submitted));
        // Held rather than resolved here, because the real client resolves from inside a later poll(). A test
        // that asserts the loop is not blocked depends on that difference being real in the fixture too.
        pendingAsyncCommits.add(() -> onResolved.accept(submitted, nextCommitOutcome));
    }

    @Override
    public CommitOutcome commitSync(Map<TopicPartition, Long> nextPositions, Duration bound) {
        observations.add(new CommitAttempted(Map.copyOf(nextPositions), bound));
        // Advances the injected clock, which is what lets a test drive a grace interval to its deadline without
        // sleeping: a commit that "takes" longer than the remaining grace is expressed as a clock advance.
        if (commitDuration != null) {
            clockAdvance.accept(commitDuration.toNanos());
        }
        return nextCommitOutcome;
    }

    /**
     * Makes {@link #commitSync} advance the injected clock by {@code duration}, modelling a commit that takes
     * time. Scripting the duration rather than sleeping is what keeps a grace-interval test deterministic.
     */
    public void scriptCommitDuration(Duration duration) {
        this.commitDuration = Objects.requireNonNull(duration);
    }

    // ---------------------------------------------------------------- observation

    public List<Observation> observations() {
        return List.copyOf(observations);
    }

    public boolean isPaused(TopicPartition topicPartition) {
        return paused.contains(topicPartition);
    }

    /** Renders the call history compactly, so a failure message shows the sequence rather than a count. */
    public List<String> history() {
        return observations.stream().map(observation -> switch (observation) {
            case PartitionPaused paused -> "pause(" + paused.topicPartition() + ")";
            case PartitionResumed resumed -> "resume(" + resumed.topicPartition() + ")";
            case Polled polled -> "poll->" + polled.partitionsReturned();
            case CommitSubmittedAsync commit -> "commitAsync" + commit.nextPositions();
            case CommitAttempted commit -> "commitSync" + commit.nextPositions();
        }).toList();
    }

    public void clearHistory() {
        observations.clear();
    }
}
