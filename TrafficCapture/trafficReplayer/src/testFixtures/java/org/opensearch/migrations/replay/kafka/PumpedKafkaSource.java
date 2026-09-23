/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

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
        CommitAttempted {}

    public record PartitionPaused(TopicPartition topicPartition) implements Observation {}

    public record PartitionResumed(TopicPartition topicPartition) implements Observation {}

    /** One poll and the partitions it returned records for, so an empty poll is distinguishable. */
    public record Polled(Set<TopicPartition> partitionsReturned) implements Observation {}

    public record CommitAttempted(Map<TopicPartition, Long> nextPositions) implements Observation {}

    private final Set<TopicPartition> assignment = new LinkedHashSet<>();
    private final Set<TopicPartition> paused = new LinkedHashSet<>();
    private final Map<TopicPartition, Long> committedPositions = new LinkedHashMap<>();
    private final Deque<Map<TopicPartition, List<ApplicationKafkaRecord>>> scriptedPolls = new ArrayDeque<>();
    private final List<Observation> observations = new ArrayList<>();
    private final Deque<ThrowingRunnable> scriptedRebalances = new ArrayDeque<>();
    private CommitOutcome nextCommitOutcome = CommitOutcome.ACKNOWLEDGED;
    private RuntimeException pollFailure;

    /** A rebalance callback can throw, because {@code onPartitionsRevoked} waits and can be interrupted. */
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public PumpedKafkaSource(Collection<TopicPartition> assignedPartitions) {
        assignment.addAll(assignedPartitions);
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
    public CommitOutcome commit(Map<TopicPartition, Long> nextPositions) {
        observations.add(new CommitAttempted(Map.copyOf(nextPositions)));
        return nextCommitOutcome;
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
            case CommitAttempted commit -> "commit" + commit.nextPositions();
        }).toList();
    }

    public void clearHistory() {
        observations.clear();
    }
}
