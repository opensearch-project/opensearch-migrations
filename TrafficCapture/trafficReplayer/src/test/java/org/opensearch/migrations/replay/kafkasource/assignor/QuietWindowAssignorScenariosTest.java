/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource.assignor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.Assignment;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.GroupAssignment;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.GroupSubscription;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.Subscription;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link QuietWindowAssignor} through every reachable pattern of the design's exhaustive
 * output space and prints a round-by-round transcript for inspection
 * (also written to {@code build/quiet-window-assignor-scenarios.txt}).
 *
 * <p>The output space decomposes into backfill patterns B1-B8, full-rebalance patterns R1-R13
 * (the tuple [width vs owners, unowned at entry, moves withheld, standbys beyond width]), window
 * transitions T1-T5, episode shapes E1-E4, and deferred trips D1-D2 (a trip whose standbys exist
 * only because of a dip in the window places unowned partitions but moves nothing, once per episode). Every produced round is classified automatically;
 * the test asserts that all reachable patterns were exhibited and that the patterns pruned as
 * unreachable by the design's invariants (R2, R7, R12, R13, and the no-op scale-up combinations)
 * never occur.</p>
 */
class QuietWindowAssignorScenariosTest {

    private static final long QUIET_MILLIS = 1000;

    /** Reachable patterns the transcript must exhibit. */
    private static final Set<String> EXPECTED = Set.of(
        "B1", "B2", "B3", "B4", "B5", "B6", "B7", "B8",
        "R1", "R3", "R4", "R5", "R6", "R8", "R9", "R10", "R11",
        "D1", "D2",
        "T1", "T2", "T3", "T4", "T5",
        "E1", "E2", "E3", "E4"
    );

    /**
     * Patterns unreachable for trips the timer triggered and for cooperative follow-ups; observing one
     * there is a bug. A membership change landing on the trip round itself can reach them (recorded
     * with a trailing '*'), because the entry state then was never seen by a backfill round.
     */
    private static final Set<String> FORBIDDEN = Set.of(
        "R2",   // [W=,U0,M1,S0]: a settled entry state cannot have retained a window
        "R7",   // [W=,U1,M1,S1]: follow-up entries never leave an owner over target
        "R12",  // [W>,U1,M1,S0]: same totals argument as R7
        "R13",  // [W>,U1,M1,S1]: same totals argument as R7
        "R[W=,U0,M0,S0]",  // fully settled trip: settled rounds discard the window first
        "R[W>,U0,M0,S0]",  // activating an idle requires giving it partitions from somewhere
        "R[W>,U0,M0,S1]"
    );

    @Test
    void exhaustivePatternTranscript() throws Exception {
        var ctx = new Ctx();

        try {
            scenarioStartup(ctx);
            scenarioRollingRestart(ctx);
            scenarioSteadyStateNoopRound(ctx);
            scenarioScaleUpOscillationAndAbort(ctx);
            scenarioScaleDown(ctx);
            scenarioMinNeverIdlesOwners(ctx);
            scenarioUnevenLoadsRebalanceMoves(ctx);
            scenarioUnevenLoadsWithFlapper(ctx);
            scenarioSeededImbalance(ctx);
            scenarioLeaderLossMidRebalance(ctx);
            scenarioNewPartitions(ctx);
            scenarioDeferredTripPlacesOrphans(ctx);
            scenarioDepartureCoincidesWithTrip(ctx);
            ctx.printLedger();
        } finally {
            Path out = Path.of("build", "quiet-window-assignor-scenarios.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, ctx.transcript.toString());
            System.out.print(ctx.transcript);
        }

        var missing = new TreeSet<>(EXPECTED);
        missing.removeAll(ctx.coverage.keySet());
        assertTrue(missing.isEmpty(), "patterns never exhibited: " + missing);
        var observedForbidden = new TreeSet<>(ctx.observedPatterns);
        observedForbidden.retainAll(FORBIDDEN);
        assertTrue(observedForbidden.isEmpty(), "invariant-pruned patterns were observed: " + observedForbidden);
    }

    // ------------------------------------------------------------------ scenarios

    /** B1 x3, T2/T3, T5, R10, E1: nothing is assigned until the group stops growing for Q. */
    private void scenarioStartup(Ctx ctx) {
        var g = new Group(ctx, "S1 startup: empty group grows to 3 workers, nothing assigned until quiet", 12);
        ctx.g1 = g;
        g.add("w1");
        g.round("w1 joins empty group");
        g.assertPattern("B1");
        g.tick(100);
        g.add("w2");
        g.round("w2 joins");
        g.assertPattern("B1");
        g.tick(100);
        g.add("w3");
        g.round("w3 joins");
        g.assertPattern("B1");
        assertTrue(g.leaderDeadlineArmed(), "startup must arm the quiet timer");
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertPattern("R10");
        g.assertSettledAndTimerCancelled();
        ctx.cover("E1", g.where("full rebalance completed in a single round"));
    }

    /** T1, B4, B7: replacements inherit the departed share; zero full rebalances. */
    private void scenarioRollingRestart(Ctx ctx) {
        var g = ctx.g1;
        g.banner("S2 rolling restart: one-out/one-in twice; zero full rebalances, zero survivor movement");
        int rebalancesBefore = g.rebalanceRounds;
        g.tick(100);
        g.remove("w1"); // the leader
        g.round("w1 (the leader) stops");
        g.assertPattern("B4");
        g.tick(100);
        g.add("w4");
        g.round("replacement w4 joins");
        g.assertPattern("B7");
        g.assertSettledAndTimerCancelled();
        g.tick(100);
        g.remove("w3");
        g.round("w3 stops");
        g.assertPattern("B4");
        g.tick(100);
        g.add("w5");
        g.round("replacement w5 joins");
        g.assertPattern("B7");
        g.assertSettledAndTimerCancelled();
        assertEquals(rebalancesBefore, g.rebalanceRounds, "a rolling restart must trigger no full rebalance");
    }

    /** B2: a round with nothing to do preserves everything and arms nothing. */
    private void scenarioSteadyStateNoopRound(Ctx ctx) {
        var g = ctx.g1;
        g.banner("S3 steady state: a rebalance round with no membership change is a pure preserve");
        g.tick(100);
        g.round("metadata-refresh round, no membership change");
        g.assertPattern("B2");
        g.assertSettledAndTimerCancelled();
    }

    /**
     * B3, T4, D1, R9, R11, R8, E2, E3, B6: scale-up waits; flapping does not extend the window; the
     * first trip defers, and a flapper that keeps flapping gets the narrow rebalance on the next one.
     */
    private void scenarioScaleUpOscillationAndAbort(Ctx ctx) {
        var g = new Group(ctx, "S4 scale-up: new workers stand by; a flapper cannot extend the window", 12);
        g.bootstrap("w1", "w2", "w3");
        g.tick(100);
        g.add("w4");
        g.round("w4 joins (scale-up)");
        g.assertPattern("B3");
        g.tick(100);
        g.add("w5");
        g.round("w5 joins (scale-up)");
        g.assertPattern("B3");
        g.tick(150);
        g.remove("w5");
        g.round("w5 drops (flap): quietAt must hold while t advances");
        g.assertPattern("B3");
        assertEquals(QuietWindowAssignor.WindowEvent.OBSERVED, g.lastTrace.windowEvent());
        g.tick(150);
        g.add("w5");
        g.round("w5 returns (flap) -- count equals the window high, so no bump");
        g.assertPattern("B3");
        assertEquals(QuietWindowAssignor.WindowEvent.OBSERVED, g.lastTrace.windowEvent());
        g.advanceToDeadline();
        boolean revoked = g.fireTimerSingleRound();
        g.assertPattern("D1"); // width 4 < 5 present: defer; nothing moves, w4 and w5 wait
        assertFalse(revoked, "a deferred trip must not move partitions");
        g.tick(100);
        g.remove("w5");
        g.round("w5 flaps again (drops)");
        g.tick(50);
        g.add("w5");
        g.round("w5 returns -- its new dip keeps the window minimum at 4");
        g.advanceToDeadline();
        revoked = g.fireTimerSingleRound();
        g.assertPattern("R9"); // deferral already used this episode: rebalance at width 4 now
        assertTrue(revoked);
        g.round("cooperative follow-up");
        g.assertPattern("R11");
        assertTrue(g.lastTrace.completedFullRebalance());
        assertTrue(g.leaderDeadlineArmed(), "completion with a standby must start a fresh window");
        ctx.cover("E2", g.where("withhold round then completion round"));
        g.advanceToDeadline();
        g.fireTimerSingleRound();
        g.assertPattern("R8"); // the stable 5th worker is finally activated
        g.converge();
        g.assertSettledAndTimerCancelled();

        g.banner("S4b abort: a join mid-rebalance closes the gate; revoked partitions are backfilled");
        g.tick(100);
        g.add("w6");
        g.round("w6 joins");
        g.tick(100);
        g.add("w7");
        g.round("w7 joins");
        g.advanceToDeadline();
        revoked = g.fireTimerSingleRound();
        assertTrue(revoked, "widening to 7 requires moves");
        g.tick(50);
        g.add("w8");
        g.round("w8 joins before the follow-up round -- new high aborts the episode");
        g.assertPattern("B6");
        ctx.cover("E3", g.where("mid-rebalance join aborted the episode; backfill repaired revoked partitions"));
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertSettledAndTimerCancelled();
    }

    /** B4, R4: orphans wait Q, then are spread across survivors exactly once. */
    private void scenarioScaleDown(Ctx ctx) {
        var g = new Group(ctx, "S5 scale-down: survivors untouched for Q, then one spread of the orphans", 12);
        g.bootstrap("w1", "w2", "w3", "w4", "w5");
        g.tick(100);
        g.remove(g.lightestNonLeaderOwner());
        g.round("one worker leaves for good");
        g.assertPattern("B4");
        g.tick(150);
        g.remove(g.lightestNonLeaderOwner());
        g.round("a second worker leaves for good");
        g.assertPattern("B4");
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertPattern("R4");
        assertEquals(0, g.lastRoundRevocations, "a pure scale-down spread must not move surviving partitions");
        g.assertSettledAndTimerCancelled();
    }

    /** B8, B6, D1, R1: the window minimum can fall below the owner count; owners are never idled for it. */
    private void scenarioMinNeverIdlesOwners(Ctx ctx) {
        var g = new Group(ctx, "S6 width floor: window min dips to 2, but current owners are never idled", 12);
        g.bootstrap("w1", "w2", "w3");
        g.tick(100);
        g.add("w4");
        g.round("w4 joins (window high becomes 4)");
        g.tick(150);
        g.remove("w2");
        g.remove("w3");
        g.round("w2 and w3 die together (count dips to 2; w4 backfills one share)");
        g.assertPattern("B8");
        g.tick(150);
        g.add("w5");
        g.add("w6");
        g.round("w5 and w6 join together (count returns to the high; no bump)");
        g.assertPattern("B6");
        g.advanceToDeadline();
        boolean revoked = g.fireTimerSingleRound();
        g.assertPattern("D1"); // width = max(min 2, owners 3) = 3 < 4 present: deferred
        assertFalse(revoked, "the width floor must prevent idling an active owner");
        g.tick(100);
        g.remove("w4");
        g.remove("w5");
        g.round("w4 and w5 die together (dip to 2; standby w6 backfills one share)");
        g.tick(50);
        g.add("w7");
        g.add("w8");
        g.round("w7 and w8 join (w7 takes the remaining orphans; w8 stands by)");
        g.advanceToDeadline();
        revoked = g.fireTimerSingleRound();
        g.assertPattern("R1"); // leveling owed: rebalance at width 3; loads already even, nothing moves
        assertFalse(revoked, "the width floor must prevent idling an active owner");
        assertTrue(g.leaderDeadlineArmed());
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertSettledAndTimerCancelled();
    }

    /**
     * B8, B6, D1, R8, R10: partial backfill leaves {4,4,4,1}; the first trip defers leveling, and the
     * widening trip levels once (3 moves instead of 4 when leveled twice).
     */
    private void scenarioUnevenLoadsRebalanceMoves(Ctx ctx) {
        var g = new Group(ctx, "S7 uneven loads after a one-time dip: leveling deferred to the widening trip", 12);
        unevenLoadsSetup(g);
        int revocationsBefore = g.totalRevocations;
        g.advanceToDeadline();
        boolean revoked = g.fireTimerSingleRound();
        g.assertPattern("D1"); // width 4 < 5 present: no leveling yet
        assertFalse(revoked);
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertSettledAndTimerCancelled();
        assertEquals(3, g.totalRevocations - revocationsBefore, "one leveling from {4,4,4,1,0} to 5 owners needs 3 moves");
    }

    /** B8, B6, D1, B4, R3, R5, E2: same loads, but a second dip lands before the widening trip. */
    private void scenarioUnevenLoadsWithFlapper(Ctx ctx) {
        var g = new Group(ctx, "S7b same uneven loads plus a second dip: the owed leveling happens at width 4", 12);
        unevenLoadsSetup(g);
        g.advanceToDeadline();
        boolean revoked = g.fireTimerSingleRound();
        g.assertPattern("D1");
        assertFalse(revoked);
        g.tick(100);
        g.remove("w6");
        g.remove("w7");
        g.round("w6 and w7 flap out together (w6's partition is orphaned; nobody idle)");
        g.tick(50);
        g.add("w6");
        g.add("w7");
        g.round("w6 and w7 return -- the dip keeps the window minimum at 3");
        g.advanceToDeadline();
        revoked = g.fireTimerSingleRound();
        g.assertPattern("R3"); // deferral used: level {4,4,4,1} at width 4
        assertTrue(revoked);
        g.round("cooperative follow-up");
        g.assertPattern("R5");
        assertTrue(g.lastTrace.completedFullRebalance());
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertSettledAndTimerCancelled();
    }

    private void unevenLoadsSetup(Group g) {
        g.bootstrap("w1", "w2", "w3"); // {4,4,4}
        g.tick(100);
        g.add("w4");
        g.add("w5");
        g.round("w4 and w5 join together (window high becomes 5)");
        g.tick(150);
        g.setPartitionCount(13);
        g.remove("w2");
        g.remove("w3");
        g.round("w2 and w3 die as the topic grows to 13 (9 unowned; w4 and w5 each backfill a share)");
        g.assertPattern("B8");
        g.tick(150);
        g.add("w6");
        g.add("w7");
        g.round("w6 and w7 join together (count returns to the high; w6 gets the 1 leftover orphan)");
        g.assertPattern("B6");
    }

    /** B4, R6: ownership restored from a prior assignor can be arbitrarily unbalanced. */
    private void scenarioSeededImbalance(Ctx ctx) {
        var g = new Group(ctx, "S8 seeded imbalance {5,1}+1 orphan (e.g., migration from another assignor)", 7);
        g.add("w1");
        g.add("w2");
        g.seedOwnership("w1", 0, 1, 2, 3, 4);
        g.seedOwnership("w2", 5);
        g.round("group attaches with restored ownership; partition 6 was never assigned");
        g.assertPattern("B4");
        g.advanceToDeadline();
        boolean revoked = g.fireTimerSingleRound();
        g.assertPattern("R6"); // the only reachable entry with an over-target owner
        assertTrue(revoked);
        g.converge();
        g.assertSettledAndTimerCancelled();
    }

    /** T1, E4: a successor leader inherits nothing, backfills, and waits a fresh Q. */
    private void scenarioLeaderLossMidRebalance(Ctx ctx) {
        var g = new Group(ctx, "S9 leader dies mid-rebalance: successor backfills and waits a fresh window", 12);
        g.bootstrap("w1", "w2", "w3");
        g.tick(100);
        g.add("w4");
        g.round("w4 joins");
        g.advanceToDeadline();
        boolean revoked = g.fireTimerSingleRound();
        assertTrue(revoked, "widening to 4 requires moves");
        g.tick(50);
        g.remove("w1"); // the leader, mid-episode
        g.round("leader w1 dies before the follow-up round");
        assertTrue(g.leaderChangedLastRound);
        g.assertPattern("B8"); // successor repairs: w4 gets a share of the revoked + departed partitions
        assertTrue(g.leaderDeadlineArmed(), "the successor must wait a fresh quiet window");
        ctx.cover("E4", g.where("successor leader backfilled and re-armed a fresh window"));
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertPattern("R4");
        g.assertSettledAndTimerCancelled();
    }

    /** B5, B4: new partitions never move owned ones; idle workers may absorb them immediately. */
    private void scenarioNewPartitions(Ctx ctx) {
        var g = new Group(ctx, "S10 partition growth: new partitions flow to idle workers, never force moves", 12);
        g.bootstrap("w1", "w2", "w3");
        g.tick(100);
        g.add("w4");
        g.round("w4 joins (standby)");
        g.tick(150);
        g.setPartitionCount(15);
        g.round("topic grows to 15 partitions; the 3 new ones backfill the idle w4");
        g.assertPattern("B5");
        g.assertSettledAndTimerCancelled();
        g.tick(100);
        g.setPartitionCount(18);
        g.round("topic grows to 18 partitions with nobody idle");
        g.assertPattern("B4");
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertPattern("R4");
        g.assertSettledAndTimerCancelled();
    }

    /** D2: a departure that lands on a deferred trip is repaired without moving anyone. */
    private void scenarioDeferredTripPlacesOrphans(Ctx ctx) {
        var g = new Group(ctx, "S12 a worker dies exactly at a deferred trip: its orphans are placed, nothing moves", 12);
        g.bootstrap("w1", "w2", "w3");
        g.tick(100);
        g.add("w4");
        g.add("w5");
        g.add("w6");
        g.round("w4, w5, w6 join (three standbys)");
        g.tick(150);
        g.remove("w5");
        g.remove("w6");
        g.round("w5 and w6 drop together (dip to 4; w4 still idle, so the episode continues)");
        g.tick(150);
        g.add("w5");
        g.add("w6");
        g.round("w5 and w6 return");
        g.advanceToDeadline();
        g.remove("w3");
        boolean revoked = g.round("w3 dies just as the deadline arrives (this round is the trip)");
        g.assertPattern("D2"); // width max(4, 2 owners) = 4 < 5 present: place w3's partitions only
        assertFalse(revoked);
        g.advanceToDeadline();
        g.fireTimerAndConverge();
        g.assertSettledAndTimerCancelled();
    }

    /** R12*: a departure landing on a trip with no standbys reaches a pattern a timer-only trip cannot. */
    private void scenarioDepartureCoincidesWithTrip(Ctx ctx) {
        var g = new Group(ctx, "S13 a worker dies exactly at a non-deferred trip: orphans placed and moves withheld together", 12);
        g.bootstrap("w1", "w2", "w3");
        g.tick(100);
        g.add("w4");
        g.round("w4 joins");
        g.tick(100);
        g.add("w5");
        g.round("w5 joins");
        g.tick(150);
        g.remove("w4");
        g.remove("w5");
        g.round("w4 and w5 drop together (the group settles, ending the episode)");
        g.tick(150);
        g.add("w4");
        g.add("w5");
        g.round("w4 and w5 return (a new episode; by its trip their dip has aged out)");
        g.advanceToDeadline();
        g.remove("w3");
        boolean revoked = g.round("w3 dies just as the deadline arrives (this round is the trip)");
        g.assertPattern("R12"); // width 4 = members present; orphans placed, w1/w2 each yield one
        assertTrue(revoked);
        g.converge();
        g.assertSettledAndTimerCancelled();
    }

    // ------------------------------------------------------------------ harness

    private static final class Ctx {
        final StringBuilder transcript = new StringBuilder();
        final Map<String, String> coverage = new TreeMap<>(patternOrder());
        final Set<String> observedPatterns = new HashSet<>();
        final Map<String, String> patternNotes = new TreeMap<>(patternOrder());
        int roundCounter;
        Group g1;

        void cover(String pattern, String where) {
            coverage.putIfAbsent(pattern, where);
            observedPatterns.add(pattern);
        }

        void print(String line) {
            transcript.append(line).append('\n');
        }

        void printLedger() {
            print("");
            print("=".repeat(100));
            print("PATTERN COVERAGE LEDGER");
            print("=".repeat(100));
            var all = new TreeMap<String, String>(patternOrder());
            EXPECTED.forEach(p -> all.put(p, coverage.getOrDefault(p, "NOT COVERED")));
            FORBIDDEN.forEach(
                p -> all.put(p, coverage.containsKey(p) ? "!! OBSERVED: " + coverage.get(p) : "unreachable for timer-driven trips (asserted never observed there)")
            );
            coverage.forEach((p, where) -> all.putIfAbsent(p, where));
            all.forEach((p, where) -> print(String.format("  %-16s %-24s %s", p, patternNotes.getOrDefault(p, ""), where)));
        }

        private static Comparator<String> patternOrder() {
            return Comparator.comparing((String s) -> s.isEmpty() ? 'Z' : s.charAt(0)).thenComparing(s -> {
                var digits = s.replaceAll("\\D", "");
                return digits.isEmpty() ? 99 : Integer.parseInt(digits);
            }).thenComparing(s -> s);
        }
    }

    private static final class Group {
        final Ctx ctx;
        final String topic = "capture";
        int partitionCount;
        long nowMillis = 0;
        int generation = 1;
        final Map<String, Member> members = new TreeMap<>();
        String leaderId;
        String previousLeaderId;
        boolean leaderChangedLastRound;
        boolean anyRoundRun;
        QuietWindowAssignor.RoundTrace lastTrace;
        String lastPattern;
        String lastWhere;
        int lastRoundRevocations;
        int rebalanceRounds;
        int totalRevocations;
        int scenarioRound;
        String scenarioName;
        boolean lastWindowPresent;
        long lastQuietAt;
        long lastRoundNowMillis;

        final class Member {
            final QuietWindowAssignor assignor = new QuietWindowAssignor();
            final AssignorControl control = new AssignorControl();
            TreeSet<TopicPartition> owned = new TreeSet<>(
                Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition)
            );

            Member() {
                assignor.configure(
                    Map.of(
                        QuietWindowAssignor.QUIET_MILLIS_CONFIG, QUIET_MILLIS,
                        QuietWindowAssignor.CONTROL_CONFIG, control,
                        QuietWindowAssignor.CLOCK_CONFIG, (java.util.function.LongSupplier) () -> nowMillis,
                        QuietWindowAssignor.ROUND_LISTENER_CONFIG,
                        (java.util.function.Consumer<QuietWindowAssignor.RoundTrace>) t -> lastTrace = t
                    )
                );
            }
        }

        Group(Ctx ctx, String scenarioName, int partitionCount) {
            this.ctx = ctx;
            this.scenarioName = scenarioName.split(" ")[0];
            this.partitionCount = partitionCount;
            banner(scenarioName + "  (Q=" + QUIET_MILLIS + "ms, topic '" + topic + "' with " + partitionCount + " partitions)");
        }

        void banner(String title) {
            scenarioName = title.split(" ")[0];
            ctx.print("");
            ctx.print("-".repeat(100));
            ctx.print(title);
            ctx.print("-".repeat(100));
        }

        /** Set by membership or topic changes, cleared by each round; marks trips that coincide with one. */
        boolean changedSinceLastRound;

        void add(String id) {
            changedSinceLastRound = true;
            members.put(id, new Member());
            if (leaderId == null) {
                leaderId = id;
            }
        }

        void remove(String id) {
            changedSinceLastRound = true;
            members.remove(id);
            if (id.equals(leaderId)) {
                leaderId = members.isEmpty() ? null : members.keySet().iterator().next();
            }
        }

        void seedOwnership(String id, int... partitions) {
            for (int p : partitions) {
                members.get(id).owned.add(new TopicPartition(topic, p));
            }
        }

        void setPartitionCount(int count) {
            changedSinceLastRound = true;
            partitionCount = count;
        }

        void bootstrap(String... ids) {
            for (String id : ids) {
                add(id);
                round(id + " joins (bootstrap)");
            }
            advanceToDeadline();
            fireTimerAndConverge();
        }

        /** Removing the leader resets its window by design, so scenarios shaping the window spare it. */
        String lightestNonLeaderOwner() {
            return members.entrySet()
                .stream()
                .filter(e -> !e.getValue().owned.isEmpty() && !e.getKey().equals(leaderId))
                .min(Comparator.comparingInt((Map.Entry<String, Member> e) -> e.getValue().owned.size()).thenComparing(Map.Entry::getKey))
                .orElseThrow()
                .getKey();
        }

        /** Advances the clock a little (well under Q) so transcripts show whether quietAt holds or moves. */
        void tick(long ms) {
            nowMillis += ms;
        }

        /** Moves the clock to the leader's armed deadline, which is when a real timer would fire. */
        void advanceToDeadline() {
            var deadline = members.get(leaderId).control.peekDeadline();
            assertTrue(deadline.isPresent(), "expected the leader to have a quiet-window deadline armed");
            assertTrue(deadline.getAsLong() >= nowMillis, "an armed deadline must not be in the past here");
            nowMillis = deadline.getAsLong();
        }

        void fireTimerAndConverge() {
            fireTimerSingleRound();
            converge();
        }

        boolean fireTimerSingleRound() {
            assertTrue(
                members.get(leaderId).control.takeDueDeadline(nowMillis),
                "expected the leader's quiet-window deadline to be due"
            );
            return round("quiet-window deadline -> enforceRebalance()");
        }

        void converge() {
            int guard = 0;
            while (lastRoundRevocations > 0) {
                assertTrue(++guard < 8, "cooperative convergence did not terminate");
                round("cooperative follow-up (revoking members rejoined)");
            }
        }

        /** Runs one assignment round; returns true if any member had partitions revoked. */
        boolean round(String trigger) {
            scenarioRound++;
            ctx.roundCounter++;
            leaderChangedLastRound = anyRoundRun && !leaderId.equals(previousLeaderId);

            var cluster = buildCluster();
            var subscriptions = new LinkedHashMap<String, Subscription>();
            members.forEach((id, m) -> {
                var sub = new Subscription(
                    List.of(topic),
                    m.assignor.subscriptionUserData(Set.of(topic)),
                    List.copyOf(m.owned),
                    generation,
                    Optional.empty()
                );
                subscriptions.put(id, sub);
            });
            Map<String, Set<TopicPartition>> before = members.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue().owned)));

            lastTrace = null;
            GroupAssignment ga = members.get(leaderId).assignor.assign(cluster, new GroupSubscription(subscriptions));
            assertNotNull(lastTrace, "the leader's assign() must emit a round trace");
            if (lastTrace.mode() == QuietWindowAssignor.Mode.FULL_REBALANCE) {
                rebalanceRounds++;
            }

            // Apply and check invariants.
            Map<TopicPartition, String> afterOwner = new HashMap<>();
            int revocations = 0;
            for (var e : members.entrySet()) {
                Assignment a = ga.groupAssignment().get(e.getKey());
                assertNotNull(a, "every member must receive an assignment entry: " + e.getKey());
                for (TopicPartition tp : a.partitions()) {
                    assertTrue(afterOwner.put(tp, e.getKey()) == null, "partition double-assigned: " + tp);
                }
                var revoked = new TreeSet<>(e.getValue().owned);
                a.partitions().forEach(revoked::remove);
                revocations += revoked.size();
                if (lastTrace.mode() == QuietWindowAssignor.Mode.BACKFILL || lastTrace.levelingDeferred()) {
                    assertTrue(revoked.isEmpty(), "backfill/deferred trips must never revoke, but " + e.getKey() + " lost " + revoked);
                }
            }
            Map<TopicPartition, String> beforeOwner = new HashMap<>();
            before.forEach((id, owned) -> owned.forEach(tp -> beforeOwner.put(tp, id)));
            afterOwner.forEach((tp, newOwner) -> {
                String old = beforeOwner.get(tp);
                assertTrue(
                    old == null || old.equals(newOwner) || !members.containsKey(old),
                    "cooperative violation: " + tp + " moved " + old + " -> " + newOwner + " in one round"
                );
            });
            members.forEach((id, m) -> {
                m.owned = new TreeSet<>(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition));
                m.owned.addAll(ga.groupAssignment().get(id).partitions());
            });
            generation++;
            members.forEach((id, m) -> m.assignor.onAssignment(
                ga.groupAssignment().get(id),
                new ConsumerGroupMetadata("replayer", generation, id, Optional.empty())
            ));
            lastRoundRevocations = revocations;
            totalRevocations += revocations;

            // The deadline semantics the transcript must make visible: interior observations hold
            // quietAt while the clock advances; creations and bumps restart it; a completed
            // rebalance that left standbys moves it to when the sliding minimum can next rise.
            var t = lastTrace;
            checkMinQueueAgainstBruteForce(t);
            if (t.mode() == QuietWindowAssignor.Mode.FULL_REBALANCE && t.withheldCount() > 0) {
                assertEquals(
                    nowMillis + QUIET_MILLIS,
                    t.deadlineMillis(),
                    "a withheld round must arm only the follow-up safety net, never a past-due deadline"
                );
            }
            if (t.deadlineRequested()) {
                assertTrue(t.deadlineMillis() > nowMillis, "no round may arm a deadline that is already due");
            }
            if (t.windowPresentAtExit()) {
                if (t.completedFullRebalance()) {
                    assertTrue(
                        t.quietAtMillisAtExit() > nowMillis && t.quietAtMillisAtExit() <= nowMillis + QUIET_MILLIS,
                        "after completion, quietAt must be when the oldest low count ages out of the window"
                    );
                } else if (t.windowEvent() == QuietWindowAssignor.WindowEvent.CREATED
                    || t.windowEvent() == QuietWindowAssignor.WindowEvent.BUMPED) {
                    assertEquals(nowMillis + QUIET_MILLIS, t.quietAtMillisAtExit(), "quietAt must restart at now+Q");
                } else if (lastWindowPresent) {
                    assertEquals(lastQuietAt, t.quietAtMillisAtExit(), "an interior observation must hold quietAt");
                }
            }

            classifyAndPrint(trigger, before, revocations);
            lastWindowPresent = t.windowPresentAtExit();
            lastQuietAt = t.quietAtMillisAtExit();
            lastRoundNowMillis = nowMillis;
            previousLeaderId = leaderId;
            anyRoundRun = true;
            return revocations > 0;
        }

        /**
         * Every (time, count) the current leader has seen, never coalesced or pruned, so the
         * min-queue's answer can be checked against a direct scan.
         */
        private final List<long[]> fullCountHistory = new ArrayList<>();

        private void checkMinQueueAgainstBruteForce(QuietWindowAssignor.RoundTrace t) {
            if (leaderChangedLastRound || !anyRoundRun) {
                fullCountHistory.clear(); // the assignor deliberately forgets on a leader change
            }
            fullCountHistory.add(new long[] { nowMillis, members.size() });
            long periodStart = nowMillis - QUIET_MILLIS;
            int bruteMin = Integer.MAX_VALUE;
            for (int i = 0; i < fullCountHistory.size(); i++) {
                long segmentStart = fullCountHistory.get(i)[0];
                long segmentEnd = i + 1 < fullCountHistory.size() ? fullCountHistory.get(i + 1)[0] : Long.MAX_VALUE;
                // A count replaced in the same instant it began was never in effect.
                if (segmentEnd > periodStart && segmentEnd > segmentStart) {
                    bruteMin = Math.min(bruteMin, (int) fullCountHistory.get(i)[1]);
                }
            }
            assertEquals(bruteMin, t.windowMinAtDecision(), "min-queue disagrees with a scan of the full history");

            var queue = t.minQueue();
            for (int i = 0; i < queue.size(); i++) {
                var e = queue.get(i);
                assertEquals(i == queue.size() - 1, e.isCurrent(), "only the newest entry may be current");
                if (i > 0) {
                    assertTrue(queue.get(i - 1).count() < e.count(), "min-queue counts must strictly increase");
                }
                if (!e.isCurrent()) {
                    assertTrue(e.endedAtMillis() > periodStart, "expired entries must be pruned");
                }
            }
            assertEquals(members.size(), queue.get(queue.size() - 1).count(), "the newest entry is the current count");
        }

        private Cluster buildCluster() {
            var node = new Node(0, "localhost", 9092);
            var infos = new ArrayList<PartitionInfo>();
            for (int p = 0; p < partitionCount; p++) {
                infos.add(new PartitionInfo(topic, p, node, new Node[] { node }, new Node[] { node }));
            }
            return new Cluster("sim", List.of(node), infos, Set.of(), Set.of());
        }

        private void classifyAndPrint(String trigger, Map<String, Set<TopicPartition>> before, int revocations) {
            var t = lastTrace;
            lastPattern = classify(t, before);
            lastWhere = scenarioName + " r" + scenarioRound;
            boolean coincidentTrip = changedSinceLastRound && lastPattern.startsWith("R");
            ctx.cover(coincidentTrip ? lastPattern + "*" : lastPattern, lastWhere + ": " + trigger);
            changedSinceLastRound = false;
            recordWindowTransitions(t);

            String quietAtNote = "";
            if (t.windowPresentAtExit()) {
                if (t.completedFullRebalance()) {
                    quietAtNote = " (MOVED to when the lowest count in the window ages out)";
                } else if (t.windowEvent() == QuietWindowAssignor.WindowEvent.OBSERVED) {
                    quietAtNote = " (HELD)";
                } else {
                    quietAtNote = " (RESTARTED now+Q)";
                }
            }
            String decisionWindow = "decided-from[min=" + t.windowMinAtDecision() + " max=" + t.windowMaxAtDecision()
                + " quietAt=" + t.quietAtMillisAtDecision() + "ms]";
            String countsInWindow = t.minQueue()
                .stream()
                .map(e -> e.isCurrent() ? e.count() + " (current)" : e.count() + " until " + e.endedAtMillis())
                .collect(Collectors.joining(", "));
            String exitWindow = t.windowPresentAtExit()
                ? "exit[min=" + t.windowMinAtExit() + " max=" + t.windowMaxAtExit()
                    + " quietAt=" + t.quietAtMillisAtExit() + "ms]" + quietAtNote
                : "exit[discarded: settled]";
            var sb = new StringBuilder();
            sb.append(String.format("%n[%s r%d] %s%n", scenarioName, scenarioRound, trigger));
            sb.append(
                String.format(
                    "  t=%dms (+%d) gen=%d leader=%s%s  window %s %s -> %s  mode=%s%n",
                    nowMillis,
                    nowMillis - lastRoundNowMillis,
                    generation - 1,
                    leaderId,
                    leaderChangedLastRound ? " (LEADER CHANGED)" : "",
                    t.windowEvent(),
                    decisionWindow,
                    exitWindow,
                    t.mode() + (t.mode() == QuietWindowAssignor.Mode.FULL_REBALANCE ? " width=" + t.width() : " share=" + t.sharePerIdle())
                        + (t.levelingDeferred() ? " LEVELING-DEFERRED" : "")
                )
            );
            sb.append(String.format("  min-queue over the last Q (oldest = min): %s%n", countsInWindow));
            var unowned = new TreeSet<Integer>();
            for (int p = 0; p < partitionCount; p++) {
                unowned.add(p);
            }
            for (var e : members.entrySet()) {
                var ps = e.getValue().owned.stream().map(TopicPartition::partition).collect(Collectors.toCollection(TreeSet::new));
                unowned.removeAll(ps);
                var beforePs = before.getOrDefault(e.getKey(), Set.of())
                    .stream()
                    .map(TopicPartition::partition)
                    .collect(Collectors.toCollection(TreeSet::new));
                String delta = beforePs.equals(ps) ? "" : "   (was " + ranges(beforePs) + ")";
                sb.append(String.format("    %-4s [%s]%s%n", e.getKey(), ranges(ps), delta));
            }
            sb.append(
                String.format(
                    "    unowned=[%s]  revocations=%d  settled=%s  timer=%s%n",
                    ranges(unowned),
                    revocations,
                    t.settledAtExit(),
                    !t.deadlineRequested() ? "none"
                        : "armed@" + t.deadlineMillis() + "ms"
                            + (t.deadlineMillis() != t.quietAtMillisAtExit() ? " (follow-up safety net)" : "")
                )
            );
            sb.append(
                String.format(
                    "    pattern %s%s -- %s",
                    lastPattern,
                    coincidentTrip ? "* (membership change landed on the trip)" : "",
                    describe(lastPattern)
                )
            );
            ctx.print(sb.toString());
        }

        private String classify(QuietWindowAssignor.RoundTrace t, Map<String, Set<TopicPartition>> before) {
            if (t.levelingDeferred()) {
                return t.unownedAtEntry() > 0 ? "D2" : "D1";
            }
            if (t.mode() == QuietWindowAssignor.Mode.BACKFILL) {
                if (t.ownerCountAtEntry() == 0) {
                    return "B1";
                }
                int pool = t.unownedAtEntry();
                int idles = t.idleCountAtEntry();
                if (pool == 0 && idles == 0) {
                    return "B2";
                }
                if (pool == 0) {
                    return "B3";
                }
                if (idles == 0) {
                    return "B4";
                }
                long capacity = (long) t.sharePerIdle() * idles;
                if (pool < capacity) {
                    boolean unfedIdle = members.entrySet()
                        .stream()
                        .anyMatch(e -> before.getOrDefault(e.getKey(), Set.of()).isEmpty() && e.getValue().owned.isEmpty());
                    return unfedIdle ? "B6" : "B5";
                }
                return pool == capacity ? "B7" : "B8";
            }
            String w = t.width() == t.ownerCountAtEntry() ? "W=" : "W>";
            String u = t.unownedAtEntry() > 0 ? "U1" : "U0";
            String m = t.withheldCount() > 0 ? "M1" : "M0";
            String s = t.memberCount() > t.width() ? "S1" : "S0";
            String tuple = "R[" + w + "," + u + "," + m + "," + s + "]";
            ctx.patternNotes.put(R_NAMES.getOrDefault(tuple, tuple), tuple);
            return R_NAMES.getOrDefault(tuple, tuple);
        }

        private void recordWindowTransitions(QuietWindowAssignor.RoundTrace t) {
            if (leaderChangedLastRound) {
                ctx.cover("T1", lastWhere + ": new leader discarded inherited window state");
            } else if (t.windowEvent() == QuietWindowAssignor.WindowEvent.CREATED && t.continuingLeader()) {
                ctx.cover("T2", lastWhere + ": fresh window created");
            }
            if (t.windowEvent() == QuietWindowAssignor.WindowEvent.BUMPED) {
                ctx.cover("T3", lastWhere + ": new membership high restarted the window");
            }
            if (t.windowEvent() == QuietWindowAssignor.WindowEvent.OBSERVED) {
                if (t.mode() == QuietWindowAssignor.Mode.BACKFILL) {
                    ctx.cover("T4", lastWhere + ": observation inside the window");
                }
            }
            if (t.mode() == QuietWindowAssignor.Mode.FULL_REBALANCE) {
                ctx.cover("T5", lastWhere + ": quiet deadline reached; gate open");
            }
        }

        void assertPattern(String expected) {
            assertEquals(expected, lastPattern, "unexpected pattern at " + lastWhere + " (" + scenarioName + ")");
        }

        void assertSettledAndTimerCancelled() {
            assertTrue(lastTrace.settledAtExit(), "expected a settled round at " + lastWhere);
            assertFalse(leaderDeadlineArmed(), "settled state must not keep a timer armed");
        }

        boolean leaderDeadlineArmed() {
            return members.get(leaderId).control.peekDeadline().isPresent();
        }

        String where(String note) {
            return lastWhere + ": " + note;
        }

        private static String ranges(TreeSet<Integer> ps) {
            if (ps.isEmpty()) {
                return "";
            }
            var parts = new ArrayList<String>();
            int start = ps.first();
            int prev = start;
            for (int p : ps.tailSet(start, false)) {
                if (p != prev + 1) {
                    parts.add(start == prev ? String.valueOf(start) : start + "-" + prev);
                    start = p;
                }
                prev = p;
            }
            parts.add(start == prev ? String.valueOf(start) : start + "-" + prev);
            return String.join(",", parts);
        }
    }

    private static final Map<String, String> R_NAMES = Map.ofEntries(
        Map.entry("R[W=,U0,M0,S1]", "R1"),
        Map.entry("R[W=,U0,M1,S0]", "R2"),
        Map.entry("R[W=,U0,M1,S1]", "R3"),
        Map.entry("R[W=,U1,M0,S0]", "R4"),
        Map.entry("R[W=,U1,M0,S1]", "R5"),
        Map.entry("R[W=,U1,M1,S0]", "R6"),
        Map.entry("R[W=,U1,M1,S1]", "R7"),
        Map.entry("R[W>,U0,M1,S0]", "R8"),
        Map.entry("R[W>,U0,M1,S1]", "R9"),
        Map.entry("R[W>,U1,M0,S0]", "R10"),
        Map.entry("R[W>,U1,M0,S1]", "R11"),
        Map.entry("R[W>,U1,M1,S0]", "R12"),
        Map.entry("R[W>,U1,M1,S1]", "R13")
    );

    private static String describe(String pattern) {
        return switch (pattern) {
            case "B1" -> "startup: no owners exist, so no fair share exists; nothing is assigned until quiet";
            case "B2" -> "settled preserve: nothing unowned, nobody idle; pure no-op";
            case "B3" -> "scale-up standby: owners keep everything, newcomers wait for the quiet window";
            case "B4" -> "orphans wait: unowned partitions but no idle worker to take them";
            case "B5" -> "partial backfill: pool smaller than capacity; every idle fed, the last one partially";
            case "B6" -> "partial backfill: pool exhausted before every idle was fed; trailing idles stay standby";
            case "B7" -> "exact backfill: the pool is exactly the idle capacity; one-out/one-in restores the shape";
            case "B8" -> "overflow backfill: every idle takes a full share; leftovers stay unowned until the trip";
            case "D1" -> "deferred trip: standbys exist only because of a dip; nothing moves until the widening trip";
            case "D2" -> "deferred trip with orphans: unowned partitions placed on the least-loaded targets; nothing moves";
            case "R1" -> "no-op trip: width floor keeps all owners; flap-lowered min idles nobody";
            case "R3" -> "leveling trip at current width: uneven owners rebalanced; standby stays standby";
            case "R4" -> "scale-down spread: orphans distributed across survivors, nothing moved";
            case "R5" -> "completion round: withheld partitions land on their planned owners; standby remains";
            case "R6" -> "seeded-imbalance trip: an over-target owner must yield partitions";
            case "R8" -> "scale-up trip: moves withheld so new workers can be activated";
            case "R9" -> "scale-up trip with a standby: window min excludes the flapper";
            case "R10" -> "completion/startup round: only unowned partitions are placed";
            case "R11" -> "completion round with a standby: newly activated workers receive withheld partitions";
            default -> pattern.startsWith("R[") ? "unnamed rebalance tuple" : "";
        };
    }
}
