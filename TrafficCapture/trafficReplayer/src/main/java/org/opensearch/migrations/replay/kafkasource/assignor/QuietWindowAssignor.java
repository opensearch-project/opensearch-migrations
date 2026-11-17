/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource.assignor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

/**
 * Churn-minimizing partition assignor; the design is
 * {@code docs/captureAndReplay/replayerKafkaQuietWindowAssignorDesign.md}.
 *
 * <p>Two rules. <b>Backfill</b> (every round): every member keeps every partition it validly owns;
 * unowned partitions go only to idle members, each receiving at most one fair share (the average
 * owner load, rounded up); nothing is ever revoked. <b>Full rebalance</b> (only after membership has
 * been quiet for the configured window): one cooperative sticky rebalance across
 * {@code max(window minimum member count, current owner count)} members, delegated to Kafka's
 * {@link CooperativeStickyAssignor} as the planner, which also withholds moved partitions so the
 * revoke-before-assign protocol is preserved.</p>
 *
 * <p>The quiet window is leader-local state: the member counts observed over the last quiet
 * period, plus an episode's high-water mark and deadline. The width uses the sliding minimum of
 * those counts, which is never reset. A member count above the episode's high pushes the deadline
 * out; a round that ends settled (every partition owned, no idle member) ends the episode; a
 * completed full rebalance that left standbys moves the deadline to when the sliding minimum can
 * rise past the width. Nothing is shared between members beyond what the cooperative protocol
 * already carries, so a leader change simply waits a fresh window and can never rebalance
 * early.</p>
 */
public class QuietWindowAssignor implements ConsumerPartitionAssignor, Configurable {

    public static final String ASSIGNOR_NAME = "quiet-window";

    /** Quiet window duration in milliseconds; Long/Integer/String accepted. */
    public static final String QUIET_MILLIS_CONFIG = "quietwindow.assignor.quiet.ms";
    /**
     * Safety-net delay armed while a cooperative follow-up round is owed; defaults to the quiet
     * period. Must exceed the revocation grace wait, since revoking members rejoin only after it.
     */
    public static final String FOLLOW_UP_GRACE_MILLIS_CONFIG = "quietwindow.assignor.followup.grace.ms";
    /** An {@link AssignorControl} instance shared with the Kafka owner loop. */
    public static final String CONTROL_CONFIG = "quietwindow.assignor.control.instance";
    /** A {@link LongSupplier} of monotonic milliseconds; defaults to the control's clock. */
    public static final String CLOCK_CONFIG = "quietwindow.assignor.monotonic.millis.supplier";
    /** A {@code java.util.function.Consumer<RoundTrace>} observing each produced round. */
    public static final String ROUND_LISTENER_CONFIG = "quietwindow.assignor.round.listener";

    public static final long DEFAULT_QUIET_MILLIS = 60_000;

    public enum Mode {
        BACKFILL,
        FULL_REBALANCE
    }

    public enum WindowEvent {
        /** No usable window existed (fresh leader, discarded window); created with a fresh deadline. */
        CREATED,
        /** Member count exceeded the window high; min/max/deadline all restarted. */
        BUMPED,
        /** Count at or below the window high; may lower the window minimum, moves no deadline. */
        OBSERVED
    }

    /**
     * Everything one {@code assign()} round decided, for telemetry and for the scenario tests that
     * classify rounds against the design's exhaustive pattern space.
     */
    public record RoundTrace(
        Mode mode,
        boolean continuingLeader,
        WindowEvent windowEvent,
        int windowMinAtDecision,
        int windowMaxAtDecision,
        long quietAtMillisAtDecision,
        int memberCount,
        int ownerCountAtEntry,
        int unownedAtEntry,
        int idleCountAtEntry,
        int sharePerIdle,
        int backfilledCount,
        int width,
        int withheldCount,
        boolean completedFullRebalance,
        boolean settledAtExit,
        boolean windowPresentAtExit,
        int windowMinAtExit,
        int windowMaxAtExit,
        long quietAtMillisAtExit,
        boolean deadlineRequested,
        long deadlineMillis,
        List<MinQueueEntry> minQueue,
        boolean levelingDeferred
    ) {}

    /**
     * A member count that can still become the sliding minimum, when it (most recently) began, and
     * when it stopped being in effect ({@link #CURRENT} while it still is). It leaves the window one
     * quiet period after it stopped.
     */
    public record MinQueueEntry(int count, long sinceMillis, long endedAtMillis) {
        public static final long CURRENT = Long.MAX_VALUE;

        public boolean isCurrent() {
            return endedAtMillis == CURRENT;
        }
    }

    private static final Comparator<TopicPartition> PARTITION_ORDER = Comparator.comparing(TopicPartition::topic)
        .thenComparingInt(TopicPartition::partition);

    /** Pure planner: computes the sticky target and withholds moved partitions; also owns the
     * generation metadata that rides in subscription user data. */
    private final CooperativeStickyAssignor planner = new CooperativeStickyAssignor();

    private long quietMillis = DEFAULT_QUIET_MILLIS;
    private long followUpGraceMillis = DEFAULT_QUIET_MILLIS;
    private AssignorControl control = new AssignorControl();
    private LongSupplier monotonicMillis = control::nowMillis;
    private java.util.function.Consumer<RoundTrace> roundListener = trace -> {};

    // Leader-local state. Lost on leader change by design: the successor waits a fresh quiet window.
    /**
     * Monotonic min-queue over the last quiet period: counts strictly increase from oldest to newest
     * and the newest is the current count. An older count at or above a newer one can never be the
     * minimum again, so it is dropped when the newer one arrives.
     */
    private final Deque<MinQueueEntry> minQueue = new ArrayDeque<>();
    private Window window;
    /**
     * A trip skipped leveling and no rebalance has leveled since. Survives episode ends: a sole
     * standby flapping out settles the group, and a per-episode allowance would let every trip defer.
     */
    private boolean levelingOwed;
    private boolean producedThisRound;
    private boolean wasLeaderLastRound;
    private long pendingDeadlineMillis = AssignorControl.NO_DEADLINE;

    /**
     * One episode of pending work. Only the high-water mark and the deadline live here; the
     * minimum is never stored, it is always the sliding minimum over the last quiet period.
     */
    private static final class Window {
        int maxCount;
        long quietAtMillis;

        Window(int count, long quietAtMillis) {
            this.maxCount = count;
            this.quietAtMillis = quietAtMillis;
        }
    }

    @Override
    public String name() {
        return ASSIGNOR_NAME;
    }

    @Override
    public List<RebalanceProtocol> supportedProtocols() {
        return List.of(RebalanceProtocol.COOPERATIVE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, ?> configs) {
        Object quiet = configs.get(QUIET_MILLIS_CONFIG);
        if (quiet instanceof Number n) {
            quietMillis = n.longValue();
        } else if (quiet instanceof String s) {
            quietMillis = Long.parseLong(s);
        }
        followUpGraceMillis = quietMillis;
        Object grace = configs.get(FOLLOW_UP_GRACE_MILLIS_CONFIG);
        if (grace instanceof Number n) {
            followUpGraceMillis = n.longValue();
        } else if (grace instanceof String s) {
            followUpGraceMillis = Long.parseLong(s);
        }
        if (configs.get(CONTROL_CONFIG) instanceof AssignorControl c) {
            control = c;
        }
        monotonicMillis = configs.get(CLOCK_CONFIG) instanceof LongSupplier clock ? clock : control::nowMillis;
        if (configs.get(ROUND_LISTENER_CONFIG) instanceof java.util.function.Consumer<?> listener) {
            roundListener = (java.util.function.Consumer<RoundTrace>) listener;
        }
    }

    /** The control instance the owner loop should watch, for callers that let this class default it. */
    public AssignorControl getControl() {
        return control;
    }

    @Override
    public java.nio.ByteBuffer subscriptionUserData(Set<String> topics) {
        // The planner's user data carries the member's completed generation, which is how stale
        // ownership claims lose during full rebalances.
        return planner.subscriptionUserData(topics);
    }

    @Override
    public GroupAssignment assign(Cluster metadata, GroupSubscription groupSubscription) {
        final long now = monotonicMillis.getAsLong();
        control.cancel(); // this round supersedes any armed deadline
        final Map<String, Subscription> subscriptions = groupSubscription.groupSubscription();
        final Map<String, List<PartitionInfo>> partitionsPerTopic = partitionUniverse(metadata, subscriptions);
        final TreeSet<TopicPartition> allPartitions = new TreeSet<>(PARTITION_ORDER);
        partitionsPerTopic.values()
            .forEach(infos -> infos.forEach(pi -> allPartitions.add(new TopicPartition(pi.topic(), pi.partition()))));
        final Map<TopicPartition, String> ownerByPartition = validatedOwnership(subscriptions, allPartitions);

        final boolean continuingLeader = wasLeaderLastRound;
        final int memberCount = subscriptions.size();
        final Set<String> owners = new HashSet<>(ownerByPartition.values());
        final int unownedAtEntry = allPartitions.size() - ownerByPartition.size();
        final int idleCountAtEntry = memberCount - owners.size();

        if (!continuingLeader) {
            window = null; // inherit nothing from a predecessor leader
            minQueue.clear();
            levelingOwed = false;
        }
        observe(now, memberCount);
        final WindowEvent windowEvent;
        if (window == null) {
            window = new Window(memberCount, now + quietMillis);
            windowEvent = WindowEvent.CREATED;
        } else if (memberCount > window.maxCount) {
            window.maxCount = memberCount;
            window.quietAtMillis = now + quietMillis;
            windowEvent = WindowEvent.BUMPED;
        } else {
            windowEvent = WindowEvent.OBSERVED;
        }
        final int slidingMin = slidingMin();
        // The window this round's decision is made from; completion may move the deadline before exit.
        final int decisionMax = window.maxCount;
        final long decisionQuietAt = window.quietAtMillis;

        final Mode mode = now < window.quietAtMillis ? Mode.BACKFILL : Mode.FULL_REBALANCE;
        final Map<String, List<TopicPartition>> assignment;
        int sharePerIdle = 0;
        int backfilledCount = 0;
        int width = 0;
        boolean completed = false;
        boolean levelingDeferred = false;

        if (mode == Mode.BACKFILL) {
            sharePerIdle = owners.isEmpty() ? 0 : ceilDiv(ownerByPartition.size(), owners.size());
            assignment = backfill(subscriptions, allPartitions, ownerByPartition, owners, sharePerIdle);
            backfilledCount = assignedCount(assignment) - ownerByPartition.size();
        } else {
            width = Math.min(Math.max(slidingMin, owners.size()), memberCount);
            List<String> targets = chooseTargets(subscriptions, ownerByPartition, owners, width);
            if (width < memberCount && !levelingOwed) {
                // Standbys exist only because a dip is still in the window, and the trip that widens
                // past it is already scheduled. Moving partitions between live owners now would just be
                // redone then, so only place unowned partitions. The next trip levels regardless, so a
                // persistent flapper cannot postpone leveling indefinitely.
                levelingOwed = true;
                levelingDeferred = true;
                assignment = placeUnowned(subscriptions, allPartitions, ownerByPartition, targets);
            } else {
                Map<String, Subscription> restricted = new HashMap<>();
                targets.forEach(id -> restricted.put(id, subscriptions.get(id)));
                assignment = new TreeMap<>(planner.assignPartitions(partitionsPerTopic, restricted));
                subscriptions.keySet().forEach(id -> assignment.computeIfAbsent(id, ignored -> List.of()));
            }
            completed = assignedCount(assignment) == allPartitions.size();
            if (completed && !levelingDeferred) {
                levelingOwed = false;
            }
            if (completed) {
                // Standbys left outside the width can join once the sliding minimum rises past it.
                window.quietAtMillis = whenSlidingMinExceeds(width, now);
            }
        }

        final int withheld = allPartitions.size() - assignedCount(assignment);
        final boolean settled = withheld == 0
            && assignment.values().stream().noneMatch(List::isEmpty)
            && !allPartitions.isEmpty();
        if (settled || allPartitions.isEmpty()) {
            window = null; // nothing pending; the next membership change starts a fresh window
        }
        if (window == null) {
            pendingDeadlineMillis = AssignorControl.NO_DEADLINE;
        } else if (withheld > 0 && mode == Mode.FULL_REBALANCE) {
            // The revoking members rejoin on their own, which starts the follow-up round; this
            // deadline is only a safety net. quietAt stays in the past so the follow-up keeps the
            // gate open.
            pendingDeadlineMillis = now + followUpGraceMillis;
        } else {
            pendingDeadlineMillis = window.quietAtMillis;
        }
        producedThisRound = true;

        roundListener.accept(
            new RoundTrace(
                mode,
                continuingLeader,
                windowEvent,
                slidingMin,
                decisionMax,
                decisionQuietAt,
                memberCount,
                owners.size(),
                unownedAtEntry,
                idleCountAtEntry,
                sharePerIdle,
                backfilledCount,
                width,
                withheld,
                completed,
                settled,
                window != null,
                window == null ? 0 : slidingMin,
                window == null ? 0 : window.maxCount,
                window == null ? 0 : window.quietAtMillis,
                pendingDeadlineMillis != AssignorControl.NO_DEADLINE,
                pendingDeadlineMillis,
                List.copyOf(minQueue),
                levelingDeferred
            )
        );

        Map<String, Assignment> result = new TreeMap<>();
        assignment.forEach((member, partitions) -> {
            List<TopicPartition> sorted = new ArrayList<>(partitions);
            sorted.sort(PARTITION_ORDER);
            result.put(member, new Assignment(sorted));
        });
        return new GroupAssignment(result);
    }

    @Override
    public void onAssignment(Assignment assignment, ConsumerGroupMetadata metadata) {
        planner.onAssignment(assignment, metadata);
        wasLeaderLastRound = producedThisRound;
        producedThisRound = false;
        if (wasLeaderLastRound && pendingDeadlineMillis != AssignorControl.NO_DEADLINE) {
            control.arm(pendingDeadlineMillis);
        } else {
            control.cancel();
        }
    }

    /**
     * Rule 1. Preserve every validated owner, then hand unowned partitions to idle members in stable
     * key order, at most one fair share each. Never revokes; leftovers stay unowned until the next
     * round or the quiet-window trip.
     */
    private Map<String, List<TopicPartition>> backfill(
        Map<String, Subscription> subscriptions,
        Set<TopicPartition> allPartitions,
        Map<TopicPartition, String> ownerByPartition,
        Set<String> owners,
        int sharePerIdle
    ) {
        Map<String, List<TopicPartition>> assignment = new TreeMap<>();
        subscriptions.keySet().forEach(id -> assignment.put(id, new ArrayList<>()));
        ownerByPartition.forEach((tp, owner) -> assignment.get(owner).add(tp));

        if (!owners.isEmpty()) {
            Deque<TopicPartition> pool = new ArrayDeque<>();
            allPartitions.stream().filter(tp -> !ownerByPartition.containsKey(tp)).forEach(pool::add);
            List<String> idles = subscriptions.keySet()
                .stream()
                .filter(id -> !owners.contains(id))
                .sorted(Comparator.comparing(id -> stableKey(id, subscriptions.get(id))))
                .toList();
            for (String idle : idles) {
                if (pool.isEmpty()) {
                    break;
                }
                Set<String> topics = Set.copyOf(subscriptions.get(idle).topics());
                int taken = 0;
                for (Iterator<TopicPartition> it = pool.iterator(); it.hasNext() && taken < sharePerIdle;) {
                    TopicPartition tp = it.next();
                    if (topics.contains(tp.topic())) {
                        assignment.get(idle).add(tp);
                        it.remove();
                        taken++;
                    }
                }
            }
        }
        return assignment;
    }

    /**
     * A deferred trip: every validated owner keeps its partitions, and each unowned partition goes to
     * the least-loaded target (ties by stable key). Never revokes.
     */
    private Map<String, List<TopicPartition>> placeUnowned(
        Map<String, Subscription> subscriptions,
        Set<TopicPartition> allPartitions,
        Map<TopicPartition, String> ownerByPartition,
        List<String> targets
    ) {
        Map<String, List<TopicPartition>> assignment = new TreeMap<>();
        subscriptions.keySet().forEach(id -> assignment.put(id, new ArrayList<>()));
        ownerByPartition.forEach((tp, owner) -> assignment.get(owner).add(tp));
        Comparator<String> leastLoaded = Comparator.<String>comparingInt(id -> assignment.get(id).size())
            .thenComparing(id -> stableKey(id, subscriptions.get(id)));
        for (TopicPartition tp : allPartitions) {
            if (ownerByPartition.containsKey(tp)) {
                continue;
            }
            targets.stream()
                .filter(id -> subscriptions.get(id).topics().contains(tp.topic()))
                .min(leastLoaded)
                .ifPresent(id -> assignment.get(id).add(tp));
        }
        return assignment;
    }

    /**
     * Rule 2 member selection: current owners first (they can never be idled, since the width is at
     * least the owner count), then idle members by stable key until the width is reached.
     */
    private List<String> chooseTargets(
        Map<String, Subscription> subscriptions,
        Map<TopicPartition, String> ownerByPartition,
        Set<String> owners,
        int width
    ) {
        Map<String, Integer> load = new HashMap<>();
        ownerByPartition.values().forEach(owner -> load.merge(owner, 1, Integer::sum));
        Comparator<String> ownersByLoadThenKey = Comparator.<String, Integer>comparing(
            id -> -load.getOrDefault(id, 0)
        ).thenComparing(id -> stableKey(id, subscriptions.get(id)));
        List<String> ordered = new ArrayList<>();
        subscriptions.keySet().stream().filter(owners::contains).sorted(ownersByLoadThenKey).forEach(ordered::add);
        subscriptions.keySet()
            .stream()
            .filter(id -> !owners.contains(id))
            .sorted(Comparator.comparing(id -> stableKey(id, subscriptions.get(id))))
            .forEach(ordered::add);
        return ordered.subList(0, Math.min(width, ordered.size()));
    }

    /**
     * A member's ownership claim is kept only when the partition exists, the member still subscribes
     * to its topic, and no competing claim from a higher Kafka generation exists. Equal-generation
     * conflicts make the partition unowned; the planner applies the same rule from its own metadata
     * during full rebalances.
     */
    private static Map<TopicPartition, String> validatedOwnership(
        Map<String, Subscription> subscriptions,
        Set<TopicPartition> allPartitions
    ) {
        Map<TopicPartition, String> owner = new TreeMap<>(PARTITION_ORDER);
        Map<TopicPartition, Integer> claimGeneration = new HashMap<>();
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            Subscription sub = entry.getValue();
            int generation = sub.generationId().orElse(-1);
            Set<String> topics = Set.copyOf(sub.topics());
            for (TopicPartition tp : sub.ownedPartitions()) {
                if (!allPartitions.contains(tp) || !topics.contains(tp.topic())) {
                    continue;
                }
                Integer prior = claimGeneration.get(tp);
                if (prior == null || generation > prior) {
                    owner.put(tp, entry.getKey());
                    claimGeneration.put(tp, generation);
                } else if (generation == prior) {
                    owner.remove(tp); // conflicting claims in the same generation: nobody keeps it
                }
            }
        }
        return owner;
    }

    private static Map<String, List<PartitionInfo>> partitionUniverse(
        Cluster metadata,
        Map<String, Subscription> subscriptions
    ) {
        Map<String, List<PartitionInfo>> partitionsPerTopic = new TreeMap<>();
        subscriptions.values()
            .stream()
            .flatMap(s -> s.topics().stream())
            .distinct()
            .forEach(topic -> {
                List<PartitionInfo> infos = metadata.partitionsForTopic(topic);
                if (infos != null && !infos.isEmpty()) {
                    partitionsPerTopic.put(topic, infos);
                }
            });
        return partitionsPerTopic;
    }

    /** Records the member count in effect from now on and expires counts older than the quiet period. */
    private void observe(long now, int count) {
        MinQueueEntry current = minQueue.peekLast();
        if (current == null || current.count() != count) {
            if (current != null) {
                minQueue.pollLast();
                if (now > current.sinceMillis()) {
                    minQueue.addLast(new MinQueueEntry(current.count(), current.sinceMillis(), now));
                } // else it was replaced in the instant it began: never in effect, so never a minimum
            }
            MinQueueEntry back = minQueue.peekLast();
            if (back != null && back.count() == count && back.endedAtMillis() == now) {
                // Only a zero-length count interrupted this one, so it never actually stopped.
                minQueue.pollLast();
                minQueue.addLast(new MinQueueEntry(count, back.sinceMillis(), MinQueueEntry.CURRENT));
            } else {
                while (back != null && back.count() >= count) {
                    minQueue.pollLast();
                    back = minQueue.peekLast();
                }
                minQueue.addLast(new MinQueueEntry(count, now, MinQueueEntry.CURRENT));
            }
        }
        long periodStart = now - quietMillis;
        while (minQueue.peekFirst().endedAtMillis() <= periodStart) {
            minQueue.pollFirst(); // the current entry never expires, so the queue never empties
        }
    }

    /** The smallest member count in effect at any time during {@code [now - Q, now]}. */
    private int slidingMin() {
        return minQueue.peekFirst().count();
    }

    /**
     * When the sliding minimum will first exceed {@code width}, assuming membership does not change:
     * one quiet period after the last count at or below {@code width} stopped being in effect.
     */
    private long whenSlidingMinExceeds(int width, long now) {
        MinQueueEntry lastAtOrBelow = null;
        for (MinQueueEntry e : minQueue) {
            if (e.count() <= width) {
                lastAtOrBelow = e;
            }
        }
        if (lastAtOrBelow == null || lastAtOrBelow.isCurrent()) {
            // Nothing waits on the minimum rising; the fallback keeps an unsettled group re-checked.
            return now + quietMillis;
        }
        return lastAtOrBelow.endedAtMillis() + quietMillis;
    }

    private static int assignedCount(Map<String, List<TopicPartition>> assignment) {
        return assignment.values().stream().mapToInt(List::size).sum();
    }

    private static String stableKey(String memberId, Subscription subscription) {
        return subscription.groupInstanceId().orElse(memberId);
    }

    private static int ceilDiv(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
