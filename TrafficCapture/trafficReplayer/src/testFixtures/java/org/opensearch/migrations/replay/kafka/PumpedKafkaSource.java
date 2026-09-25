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
import java.util.function.LongConsumer;

import org.opensearch.migrations.replay.kafkasource.PolledKafkaRecord;
import org.opensearch.migrations.replay.kafkasource.KafkaSourcePort;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

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
    private final Deque<Map<TopicPartition, List<PolledKafkaRecord>>> scriptedPolls = new ArrayDeque<>();
    private final List<Observation> observations = new ArrayList<>();
    private final Deque<ThrowingRunnable> scriptedRebalances = new ArrayDeque<>();
    /** Async commits awaiting a poll to resolve them, exactly as the real client defers its callbacks. */
    private final Deque<Runnable> pendingAsyncCommits = new ArrayDeque<>();
    private final LongConsumer clockAdvance;
    private CommitOutcome nextCommitOutcome = CommitOutcome.ACKNOWLEDGED;
    private RuntimeException pollFailure;
    private Duration commitDuration;
    private boolean wakeupNextPollAfterRebalance;
    private boolean wakeupNextCommitSync;
    private boolean wakeupNextCommitAsync;
    private boolean wakeupNextCommitAsyncAfterRegistration;
    private CommitOutcome nextRejectedCommitOutcome;
    private boolean neverResolveAsyncCommits;
    private java.util.function.Consumer<String> observationListener = call -> {};

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
    public void scriptPoll(Map<TopicPartition, List<PolledKafkaRecord>> result) {
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

    /**
     * Holds asynchronous commit callbacks indefinitely, so an operation can stay unresolved.
     *
     * <p>For scenarios that need an operation to stay unresolved across several polls — reassigning a partition
     * takes one, and reading on it takes another.
     */
    public void scriptNeverResolveAsyncCommits() {
        neverResolveAsyncCommits = true;
    }

    /** Allows callbacks held by {@link #scriptNeverResolveAsyncCommits()} to run on the next poll. */
    public void scriptResolveAsyncCommits() {
        neverResolveAsyncCommits = false;
    }

    /**
     * Interrupts the next poll with {@code WakeupException} after its scripted rebalance callbacks have run.
     *
     * <p>This is what makes {@code kafkaLLD §17.4} case 18 expressible: a rebalance that has delivered
     * {@code onPartitionsRevoked} but not yet {@code onPartitionsAssigned} when a queued input wakes the poll.
     * Kafka postpones the assignment callback to a later poll rather than discarding it ({@code §5.4}), so a
     * test scripts the revoke here and the assign on the following poll, and the wakeup lands between them.
     */
    public void scriptWakeupAfterNextRebalance() {
        wakeupNextPollAfterRebalance = true;
    }

    public void scriptCommitOutcome(CommitOutcome outcome) {
        nextCommitOutcome = Objects.requireNonNull(outcome);
    }

    /**
     * Interrupts the next {@link #commitSync} with {@code WakeupException}, which is what a wakeup issued
     * before the commit began does to it.
     *
     * <p>The attempt is still recorded before the throw: the call was made, and a test needs to distinguish an
     * interrupted commit from one that never happened.
     */
    public void scriptCommitSyncWakeup() {
        wakeupNextCommitSync = true;
    }

    /**
     * Interrupts the next {@link #commitAsync} with {@code WakeupException} before it registers a callback.
     *
     * <p>An asynchronous submission is still a Kafka call, so a wakeup outstanding when it begins interrupts it
     * exactly as it interrupts a blocking one. The submission then never resolves through its callback, which is
     * what makes the owner's own resolution of it observable.
     */
    public void scriptCommitAsyncWakeup() {
        wakeupNextCommitAsync = true;
    }

    /**
     * Registers the next asynchronous callback and then throws {@link WakeupException}.
     *
     * <p>This is the operation-level race G4 must survive: the owner resolves the thrown path as unknown, then
     * a later poll delivers the callback for the same accepted submission. Exactly one may change owner state.
     */
    public void scriptCommitAsyncWakeupAfterRegistration() {
        wakeupNextCommitAsyncAfterRegistration = true;
    }

    /** Refuses the next asynchronous operation before a callback is registered. */
    public void scriptCommitAsyncRejection(CommitOutcome outcome) {
        nextRejectedCommitOutcome = Objects.requireNonNull(outcome);
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
    public Map<TopicPartition, List<PolledKafkaRecord>> poll() {
        // Rebalance callbacks run before commit callbacks are resolved. Kafka delivers both from inside poll()
        // and guarantees no order between them, so either is faithful -- but this order is the one that can
        // expose a commit resolving after its generation was revoked and reassigned, and a fixture that always
        // resolved first would make that case unreachable.
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
        if (wakeupNextPollAfterRebalance) {
            wakeupNextPollAfterRebalance = false;
            // Exactly what the real consumer does: the poll that was carrying the rebalance is interrupted, and
            // the remainder of that rebalance is delivered by a later poll.
            throw new WakeupException();
        }
        if (neverResolveAsyncCommits) {
            // Held past every poll. The real client holds a callback only until the poll its broker response
            // arrives by, so this is deliberately stricter than Kafka: it is the only way to hold an operation
            // unresolved across the several polls a reassign-then-read scenario needs.
        } else {
            while (!pendingAsyncCommits.isEmpty()) {
                pendingAsyncCommits.removeFirst().run();
            }
        }
        if (pollFailure != null) {
            var failure = pollFailure;
            pollFailure = null;
            throw failure;
        }
        var result = scriptedPolls.isEmpty()
            ? Map.<TopicPartition, List<PolledKafkaRecord>>of()
            : scriptedPolls.removeFirst();
        // Only paused partitions are withheld, which is what makes "paused before the next poll" testable:
        // a partition the owner failed to pause keeps yielding records.
        var delivered = new LinkedHashMap<TopicPartition, List<PolledKafkaRecord>>();
        result.forEach((topicPartition, records) -> {
            if (!paused.contains(topicPartition)) {
                delivered.put(topicPartition, records);
            }
        });
        record(new Polled(Set.copyOf(delivered.keySet())));
        return delivered;
    }

    @Override
    public void pause(TopicPartition topicPartition) {
        paused.add(topicPartition);
        record(new PartitionPaused(topicPartition));
    }

    @Override
    public void resume(TopicPartition topicPartition) {
        paused.remove(topicPartition);
        record(new PartitionResumed(topicPartition));
    }

    @Override
    public Optional<Long> committedPosition(TopicPartition topicPartition) {
        return Optional.ofNullable(committedPositions.get(topicPartition));
    }

    @Override
    public AsyncCommitSubmission commitAsync(
        Map<TopicPartition, Long> nextPositions,
        java.util.function.Consumer<CommitOutcome> onResolved
    ) {
        var submitted = Map.copyOf(nextPositions);
        record(new CommitSubmittedAsync(submitted));
        if (nextRejectedCommitOutcome != null) {
            var rejected = nextRejectedCommitOutcome;
            nextRejectedCommitOutcome = null;
            return AsyncCommitSubmission.rejectedBeforeAcceptance(rejected);
        }
        if (wakeupNextCommitAsync) {
            wakeupNextCommitAsync = false;
            // Interrupted before a callback is registered, so nothing here will ever resolve this submission.
            throw new WakeupException();
        }
        // Held rather than resolved here, because the real client resolves from inside a later poll(). A test
        // that asserts the loop is not blocked depends on that difference being real in the fixture too.
        pendingAsyncCommits.add(() -> onResolved.accept(nextCommitOutcome));
        if (wakeupNextCommitAsyncAfterRegistration) {
            wakeupNextCommitAsyncAfterRegistration = false;
            throw new WakeupException();
        }
        return AsyncCommitSubmission.acceptedByClient();
    }

    /**
     * Kafka guarantees a pending {@code commitAsync} callback is invoked before the following
     * {@code commitSync} returns, so an asynchronous submission is not abandoned merely by being forgotten
     * locally: its callback still arrives carrying the positions and counts it was given. Modelling that is
     * what makes double-crediting reachable in a test rather than only in production.
     *
     * <p>The injected clock advance is what lets a test drive a grace interval to its deadline without
     * sleeping: a commit that "takes" longer than the remaining grace is expressed as a clock advance.
     */
    @Override
    public CommitOutcome commitSync(Map<TopicPartition, Long> nextPositions, Duration bound) {
        record(new CommitAttempted(Map.copyOf(nextPositions), bound));
        // Pending async callbacks run first even when this call is about to be interrupted. The real client
        // invokes completed commit callbacks at the top of commitOffsetsSync, before the network poll that
        // raises WakeupException, so a callback resolving during an interrupted commit is reachable.
        while (!pendingAsyncCommits.isEmpty()) {
            pendingAsyncCommits.removeFirst().run();
        }
        if (commitDuration != null) {
            // An interrupted commit still consumed time. Advancing before the throw is what keeps a scripted
            // duration meaningful on this path -- a real commit cannot be interrupted having taken none.
            clockAdvance.accept(commitDuration.toNanos());
        }
        if (wakeupNextCommitSync) {
            wakeupNextCommitSync = false;
            throw new WakeupException();
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

    /**
     * Reports each call as it happens, rendered as {@link #history} renders it.
     *
     * <p>For assertions that must order port calls against something outside the port — a submission to replay
     * intake, say. Comparing two separate histories cannot express "A happened before B" across them, so a
     * test written that way passes with the two production statements reversed.
     */
    public void onObservation(java.util.function.Consumer<String> listener) {
        observationListener = Objects.requireNonNull(listener);
    }

    public boolean isPaused(TopicPartition topicPartition) {
        return paused.contains(topicPartition);
    }

    /** One place every call is recorded, so a listener cannot miss one that was added later. */
    private void record(Observation observation) {
        observations.add(observation);
        observationListener.accept(render(observation));
    }

    private static String render(Observation observation) {
        return switch (observation) {
            case PartitionPaused paused -> "pause(" + paused.topicPartition() + ")";
            case PartitionResumed resumed -> "resume(" + resumed.topicPartition() + ")";
            case Polled polled -> "poll->" + polled.partitionsReturned();
            case CommitSubmittedAsync commit -> "commitAsync" + commit.nextPositions();
            case CommitAttempted commit -> "commitSync" + commit.nextPositions();
        };
    }

    /** Renders the call history compactly, so a failure message shows the sequence rather than a count. */
    public List<String> history() {
        return observations.stream().map(PumpedKafkaSource::render).toList();
    }

    public void clearHistory() {
        observations.clear();
    }
}
