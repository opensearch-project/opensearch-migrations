# Kafka Quiet-Window Assignor

**Status:** implemented by `QuietWindowAssignor` and `AssignorControl` in
`TrafficCapture/trafficReplayer/.../kafkasource/assignor`; the replayer's default
`partition.assignment.strategy` (a user Kafka property file may still override it). This is a
simpler alternative to the stability-window cooperative assignor proposal (not checked in).
**Date:** 2026-10-10

## Goal

Reduce partition movement (churn) during ordinary fleet events — rolling restarts, scale-ups,
scale-downs — while keeping every partition's unassigned time bounded. We accept partitions
sitting unassigned for a little while; we do not accept partitions moving between live workers
except during a deliberate, rare, full rebalance.

## The whole algorithm

There are only two rules and one timer.

**Rule 1 — Backfill (every rebalance round, immediately, no timer involved):**
Every worker keeps every partition it currently owns. Partitions with no owner are handed only to
*idle* workers (workers owning nothing), each receiving at most one *fair share* — the average
load of the current owners, rounded up. Leftover unowned partitions stay unowned. Nothing is ever
revoked by this rule.

**Rule 2 — Full rebalance (only after membership has been quiet for `Q`):**
The leader records the eligible-member count each time it changes. `min` is the smallest count in
effect at any time during the last `Q`, a sliding window over time that is never reset. The first
membership change after a settled round starts an *episode*, with `max = count` and
`quietAt = now + Q`. A count above the episode's `max` raises `max` and pushes `quietAt` to
`now + Q`; any other change leaves `quietAt` where it is. When `quietAt` arrives, do one cooperative
sticky rebalance across `width = max(min, current owner count)` workers.

**Deferred leveling:** if a trip would leave standbys (the width is below the number of members
present, which at a trip only happens when a dip is still inside the window), the trip that widens
past the dip is already scheduled, and leveling now would be redone then. So the trip only places
unowned partitions on the least-loaded targets and moves nothing between live owners. Doing this
sets *leveling owed*; the next trip levels at whatever width it gets, even with standbys, and
clears it. The debt survives episode ends, because a sole standby flapping out settles the group:
with a per-episode allowance, a standby that flaps faster than `Q` would defer every trip.

If a rebalance leaves standbys outside the width (because a dip in the last `Q` held `min` down),
`quietAt` moves to the moment the dip ages out of the sliding window, that is, `Q` after the count
last rose above the width. If nothing changes before then, the standbys are activated at that
moment.

The state is small: a monotonic min-queue of counts from the last `Q`, plus the episode's `max`
and `quietAt`. The queue holds each count that could still become the minimum, together with the
time it stopped being in effect (none for the current count). When a count arrives, every older
entry at or above it is dropped, because the new count stays in the window at least as long; for
example, `2 until 1400, 3 until 1500` followed by a return to 2 becomes just `2 (current)`. Counts
therefore increase from oldest to newest, the oldest is `min`, an entry expires `Q` after it ended,
and the queue never holds more than one entry per distinct count (so at most `W`). A count that is
replaced in the same instant it began was never in effect and is dropped rather than ended.
It is held only by the current assignment leader, never shared, and discarded on a leader change
(a new leader simply waits a fresh `Q`).

When a round ends *settled* (every partition owned and no eligible worker idle), the episode ends
and no timer runs. The count history is kept; the next membership change starts a new episode.

**Timer while a rebalance is in flight:** a round that withholds partitions (see below) owes a
follow-up round, which the revoking members start themselves by rejoining. The leader therefore
arms only a safety-net deadline of `now + followUpGrace` instead of the already-passed `quietAt`.
`quietAt` itself stays in the past, so the follow-up round still runs Rule 2. `followUpGrace`
defaults to `Q` and must exceed the revocation grace wait.

## Why this reduces churn

- A rolling restart never moves a surviving worker's partitions. Each departed worker's partitions
  wait, unowned, for up to `Q`; its replacement joins, is idle, and backfills approximately the
  departed share (one-out/one-in with even loads reproduces it exactly). Each round that ends
  settled ends the episode, so as long as each replacement arrives within `Q`, a rolling
  restart of any length triggers **zero** full rebalances.
- A scale-up assigns nothing to the new workers until the group stops growing for `Q`, then moves
  partitions exactly once, onto all of them together.
- A scale-down leaves survivors untouched for `Q`, then spreads the orphaned partitions once.
- A flapping worker (leave/rejoin) never pushes out `quietAt`, because rejoining does not exceed
  the episode's previous high, so one unstable pod cannot postpone recovery of real losses. Its dip
  stays in the sliding `min` for `Q`, so the flapper is not given partitions until it has stayed
  for a full `Q`.

## Why unassigned time is bounded

Within one episode, each push of `quietAt` requires a member count strictly higher than any seen
since the episode began. Counts cannot exceed the fleet's maximum size `W`, so `quietAt` is pushed
at most `W` times. Any partition is therefore unowned for at most `(W + 1) × Q`, and in the common case
(membership stops changing) at most `Q`. No separate forced-recovery mechanism is needed.

## Definitions

| Term | Meaning |
| --- | --- |
| owner | A member whose claim to a partition is valid (see below). |
| idle worker | An eligible member owning zero partitions. |
| unowned partition | A current partition with no valid owner. Deliberately **not** distinguished from "never assigned" — no history is kept. |
| fair share | `ceil(owned partition count / owner count)`. Undefined when there are no owners, in which case backfill assigns nothing. |
| settled | No unowned partitions and no idle workers. |
| `Q` | The quiet window duration (config). |
| `W` | Maximum possible fleet size (deployment config; used only in the bound argument). |

A member's ownership claim is valid when the member is in the current group, is still subscribed
to the partition's topic, the partition still exists, and Kafka's cooperative generation rules
accept the claim (stale-generation conflicts are resolved by the embedded sticky planner's
metadata; the loser's claim is dropped and the partition becomes unowned).

## Pseudocode

Kafka instantiates one assignor per consumer and calls `assign()` only on the leader of each
round. All interesting state is leader-local.

```text
class QuietWindowAssignor implements ConsumerPartitionAssignor, Configurable:
    config: Q, followUpGrace (default Q), monotonicClock, assignorControl
    stickyPlanner          # Kafka's cooperative-sticky algorithm, used as a pure planner

    leader-local state (lost on leader change; that is fine):
        minQueue = []      # (count, endedAt); counts increase oldest to newest; newest is current
        episode = NONE     # { max, quietAt }
        levelingOwed = false   # survives episode ends; reset only on leader change
        producedThisRound = false
        wasLeaderLastRound = false

    assign(cluster, subscriptions):
        now = monotonicClock.now()
        assignorControl.cancel()
        group = normalize(cluster, subscriptions)   # valid owners, eligible members
        count = group.eligibleMemberCount

        if not wasLeaderLastRound:
            minQueue = []; episode = NONE           # inherit nothing from prior leaders
        if minQueue is empty or minQueue.last.count != count:
            if minQueue nonempty:
                if minQueue.last.since == now: drop it          # zero duration: never in effect
                else: minQueue.last.endedAt = now
            pop from the back while minQueue.last.count >= count   # can never be the min again
            minQueue.append((count, CURRENT))
        pop from the front while minQueue.first.endedAt <= now - Q  # aged out
        min = minQueue.first.count                  # sliding; never reset

        if episode == NONE:
            episode = { max: count, quietAt: now + Q }
        else if count > episode.max:
            episode.max = count; episode.quietAt = now + Q

        if now < episode.quietAt:
            assignment = backfill(group)                        # Rule 1
        else:
            width = max(min, group.ownerCount)                  # Rule 2
            targets = first `width` of:
                current owners, most partitions first, ties by stable key,
                then idle workers by stable key
            if width < count and not levelingOwed:              # deferred leveling
                levelingOwed = true
                assignment = owners keep everything; each unowned partition
                             goes to the least-loaded target
            else:
                desired = stickyPlanner.assign(cluster, group restricted to targets)
                assignment = withholdMoves(desired, group)
                if nothing was withheld: levelingOwed = false
            if nothing was withheld:                            # standbys (if any) wait until
                episode.quietAt =                               # the dip ages out of the window
                    (endedAt of the last minQueue entry <= width) + Q

        if settled(assignment, group):
            episode = NONE
        return finishRound(assignment)

    backfill(group):
        assignment = every valid current owner keeps its partitions
        if group.ownerCount == 0: return assignment
        share = ceil(group.ownedPartitionCount / group.ownerCount)
        pool = unowned partitions in stable order
        for idle in group.idleWorkers in stable-key order, while pool nonempty:
            give idle up to `share` partitions from pool
        return assignment

    withholdMoves(desired, group):                  # standard cooperative step
        for each partition:
            if its current owner is absent or equals its desired owner:
                assign it to the desired owner
            else:
                assign it to nobody this round      # owner must revoke first;
                                                    # Kafka then triggers a follow-up round

    finishRound(assignment):
        producedThisRound = true
        nextDeadline =
            NONE                 if episode == NONE
            now + followUpGrace  if Rule 2 withheld partitions  # follow-up is rejoin-driven
            episode.quietAt      otherwise
        return one assignment entry per member

    onAssignment(localAssignment, metadata):
        stickyPlanner.onAssignment(localAssignment, metadata)
        wasLeaderLastRound = producedThisRound
        producedThisRound = false
        if wasLeaderLastRound and nextDeadline exists:
            assignorControl.arm(nextDeadline)

    subscriptionUserData(topics): return stickyPlanner.subscriptionUserData(topics)
    supportedProtocols(): return [COOPERATIVE]

stable key = groupInstanceId when configured, otherwise memberId
```

The timer cannot authorize an assignment; it only asks Kafka to start a round. The decision is
always remade from current state inside `assign()`. The owner loop (`KafkaSourceOwner`, on the
Kafka thread) consumes the deadline exactly as in the stability-window proposal:

```text
ownerLoopIteration():
    if assignorControl.takeDueDeadline(monotonicClock.now()):
        consumer.enforceRebalance("quiet-window deadline")
    consumer.poll(min(normalPollTimeout, assignorControl.timeUntilDeadline()))
```

`takeDueDeadline()` returns a given deadline at most once.

## How the full rebalance completes without plan state

A full rebalance may need two or more rounds: round one withholds every partition that must move;
the revoking members rejoin, Kafka starts the next round, and the leader simply recomputes from
scratch. Recomputation converges because the planner is sticky with respect to current ownership
and member selection is deterministic (owners first, stable-key ties). No plan is persisted or
handed off:

- **Membership grows mid-rebalance:** the new high pushes `quietAt` out, so the round falls back to
  Rule 1 — partitions already revoked are simply unowned and get backfilled to idle workers
  (possibly including the newcomer), the rest within `Q` at the next trip.
- **Leader dies mid-rebalance:** the new leader starts with no history or episode; revoked partitions are
  backfilled under Rule 1 and the rebalance finishes after a fresh `Q`. Later, never earlier.

## Worked examples

- **Rolling restart, fleet of 100:** worker 37 leaves → its share is unowned, an episode starts
  (`quietAt = now + Q`). Replacement 37' joins → idle, backfills the share → round is settled →
  episode ends, timer cancelled. Repeat per pod. No full rebalance, no survivor movement.
- **Scale-up 100 → 110:** each new high pushes `quietAt` out. Once arrivals stop, after `Q`:
  `width = max(110, 100) = 110`, one sticky rebalance. If the count dipped to 109 within the last
  `Q`, `width = max(109, 100) = 109`, and the 110th worker is activated `Q` after the count last
  returned to 110, provided it doesn't dip again before then.
- **Scale-down 100 → 90:** orphans sit unowned (no idle workers). No new high, so the episode from
  the first departure trips after `Q`: `width = max(90, 90) = 90`, orphans spread once.
- **Startup:** first members join with zero owners; backfill assigns nothing (no fair share
  exists). Growth keeps pushing `quietAt` out; `Q` after the last new member,
  `width = max(min, 0) = min` and everything is assigned at once. Startup is just scale-up.
- **New topic partitions:** they appear as unowned. Idle workers (if any) may pick them up as
  backfill; otherwise they wait for the next trip. They never cause movement.

## Deliberate differences from the stability-window proposal

| Stability-window mechanism | Replaced by | Accepted cost |
| --- | --- | --- |
| Baseline `B` + partition shape persisted in generation-stamped policy metadata on every member | Fair share and width derived from current ownership, which Kafka's cooperative protocol already carries | If every worker is replaced *simultaneously* (no overlap), nothing is remembered and recovery waits `Q`. (The metadata version has the same practical behavior: no surviving member means no surviving metadata.) |
| Reconciliation plan, plan handoff to successor leaders, `repairReleasedPlanPartitions` | Stateless per-round recomputation (the same trick Kafka's own cooperative-sticky assignor uses) | A leader change mid-rebalance delays completion by up to `Q`. |
| Five-phase timed state machine | Two rules gated by one deadline; "phase" is a derived condition | While idle flappers exist, one no-op rebalance round may fire per `Q`. |
| Rolling observation buffer over the most recent `Q`; resets on new lows *and* highs | The same sliding buffer for `min`, but only a new episode high pushes the deadline; settled rounds end the episode | A departure during an armed episode does not extend it — orphans beyond available idle workers wait until the existing trip. |
| `targetWidth` formula (`windowCeiling ≤ B ? floor : max(B, floor)`) | `width = max(sliding min, current owner count)` | After flappers rejoin and are backfilled during a scale-down, they stay active instead of being idled. (Strictly less churn; slightly wider than "proven stable".) |
| Baseline-slot sizing (small/large slots), omitted-baseline bookkeeping, never backfilling new partitions | One fair-share cap; any unowned partition is backfillable | A new topic partition can activate an idle worker before quiescence. No movement results. |
| `maximumOmissionDuration` forced recovery | The `(W + 1) × Q` bound falls out of new-high-only deadline pushes | None. |

## Invariants

1. A partition has at most one owner; cooperative revoke-before-assign is never bypassed.
2. Backfill never revokes and never gives any single idle worker more than one fair share.
3. Partitions move between live owners only in a Rule 2 round whose episode deadline was
   rechecked inside the current `assign()`.
4. The timer only requests rebalance rounds; it never authorizes a stale decision. No round arms
   a deadline that has already passed.
5. A new leader can delay a full rebalance (fresh `Q`) but can never cause one early.
6. All consumer interaction stays on the Kafka owner thread.

Commit and revocation behavior is unchanged and defined by
[Kafka Source and Replay Intake Low-Level Design](replayerKafkaSourceAndIntakeLowLevelDesign.md).

## Required tests

- Startup assigns nothing until quiet, then assigns everything in one rebalance.
- One-out/one-in with even loads hands the replacement exactly the departed partitions, settles,
  and cancels the timer.
- A full rolling restart (replacement within `Q` each time) performs zero full rebalances.
- Scale-up workers stay idle until quiet; oscillation 109–110 after the last high yields width 109.
- Scale-down orphans are spread exactly once, `Q` after the episode began.
- A rejoining flapper does not push out `quietAt`; a genuinely new high does. The flapper's dip
  holds the sliding `min` down for exactly `Q`.
- A round that withholds partitions arms only `now + followUpGrace`, never a deadline that has
  already passed.
- A one-time dip defers leveling to the widening trip, which then levels once.
- A dip that persists past the deferred trip gets the narrow leveling on the next trip, including
  when a sole flapping standby ends the episode in between.
- A departure landing on a deferred trip places the orphans without moving anyone.
- More idle workers than fair shares: extras stay idle; fewer: leftover partitions stay unowned.
- A new high during a multi-round rebalance aborts it; revoked partitions are backfilled, the rest
  recovered at the next trip.
- Leader replacement mid-rebalance: new leader backfills immediately and completes after a fresh
  `Q`; it never rebalances early.
- Settled state arms no timer and triggers no periodic `enforceRebalance()`.
- New topic partitions never cause movement of owned partitions.
- Conflicting ownership claims resolve by planner generation; the loser's partition becomes
  unowned, never double-owned.

## Implementation scope

- Wrap Kafka's cooperative-sticky planner for target computation and generation metadata;
  advertise only the cooperative protocol and apply the withhold-moves step in the wrapper.
- Assume all replay workers subscribe to the same topics with equal partition weight; fall back to
  the planner's plain behavior (or refuse) for heterogeneous subscriptions.
- `Q` is the only new user-facing configuration: workflow key `kafkaQuietWindowMs` and CLI flag
  `--kafka-quiet-window-ms` (default 60000), forwarded to the consumer as
  `quietwindow.assignor.quiet.ms`. The replayer rejects a value that does not exceed
  `cancellationGraceMs`.
- `followUpGrace` is an internal consumer property (`quietwindow.assignor.followup.grace.ms`,
  default `Q`) that is not exposed by the CLI or workflow schema. The poll-loop deadline plumbing
  (`assignorControl`) is internal wiring, not configuration.
