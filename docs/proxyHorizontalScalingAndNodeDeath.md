# Proxy Horizontal Scaling and Writer-Completion Declarations

**Status: partially implemented, remaining scaling protocol is a sketch.** Exact full manifests,
ordered publishing, strict manifest timestamps, and interleaved chunk reconstruction are implemented.
Consumer-group assignment, `NoMoreWrites`, zombie containment, and their integration tests remain
future work.

A note on terminology, since "node death" was the earlier framing and misled: nothing in the
protocol asserts that a node is dead. Every declaration is scoped to one `(nodeId, partition)`
pair and says only "no further records from this writer here" — see §3.3 for why the
partition scope is forced rather than incidental. The filename still says `NodeDeath`; the link
target was kept stable rather than churn the cross-references, and it can be renamed if desired.

It supersedes `replayer-expiration-hardening.md` §5.4.5 and §7's "A stable per-host nodeId"
rejection, and it closes the dead-proxy residual that both that document's §5.4 and
`replayerHardenedArchitectureDesign.md` §10.8 leave open. It revisits
`replayerHardenedArchitectureDesign.md` §19.7 (how a proxy chooses its partitions).

It does not change the expiry *policy* or §10.8's decision to declare all open connections rather
than idle-only. It updates the omission predicate to match the exact-registry proof: one complete
manifest copied after the connection's last record is sufficient. What the remaining scaling work
changes is the set of admissible positive signals.

## 1. What problem this solves

Two problems that turned out to be the same problem.

**Scaling capture.** Today a proxy hashes its own `nodeId` to pick a starting partition and
claims `shardWidth` consecutive partitions from there (`PartitionRoutingPlan.forTopic`).
Ranges collide at random, growing the topic reshuffles every proxy's range, and there is no
way to add capture capacity deliberately.

**Proving a connection is gone.** The absence-proof rule
(`replayerHardenedArchitectureDesign.md` §10.8) can prove an open connection is gone using one
complete, exact manifest from its own node after the connection's last record. It
cannot prove anything about a node that stopped emitting manifests, because absence of
manifests is indistinguishable from a slow node.

Be precise about what that residual currently is, because it is *not* a timeout. A wall-clock
commit is already rejected (`replayer-expiration-hardening.md` §7) and already banned
(`replayerHardenedArchitectureDesign.md` §10.5's "Wall-clock age → **Never**" row and §20's
non-goal "any path by which wall-clock timeout code can commit Kafka"). What the shipped design
does instead is:

- **Retain and halt loudly** (§10.5, "No proxy snapshots arriving → **Never** → Retain; halt
  loudly if it blocks progress"). Correct, but it converts a dead proxy into an operator page.
- **Emit unresolved-head metrics and diagnostics.** Operators may let the run wait indefinitely or
  terminate it in this premature terminal state. Neither choice advances the commit.

The connection: if the fleet has a membership protocol, then a departure is an *event* a
surviving member observes, not a silence it has to interpret. A surviving member can record that
event as `NoMoreWrites`. If no member survives, the protocol deliberately has no automatic
settlement path; a fresh replacement process cannot retroactively speak for an old `nodeId`.

## 2. Core reframe

Two ideas carry the whole design.

**Death is declared per node, not per partition.** The assertion we need is "node X will
never write another record." That is a fact about X. Earlier drafts tied it to partition
ownership — a new owner's arrival declared the old owner's connections dead — which is
unsound, because ownership legitimately transfers while the previous owner is still alive
and still writing to those partitions (§4.4).

**Partition assignment is load balancing, not a lock.** The assignor decides where *newly
opened* connections go. It says nothing about existing ones and grants no exclusivity.
Multiple nodes writing one partition concurrently is normal and safe, because every
liveness conclusion is already keyed per `(nodeId, partition)`.

A consequence worth stating up front: **the assignor is not safety-critical.** Uneven
distribution costs throughput headroom, never correctness. That matters because unevenness is
not only a failure case — the design produces it deliberately and routinely:

- A drain after a scale-up has A still writing to partitions B now owns, by design (§3.5).
- The first proxy in a fleet holds all M partitions until others join.
- Connection-to-partition hashing is uneven at small connection counts, and long-lived
  connections keep it that way.

Since none of those are distinguishable from a misassignment by their effect on the log, the
protocol cannot depend on evenness and does not. A partition assigned to nobody is simply
unused; a partition assigned to two nodes is the drain case, which is already legal.

### 2.1 What changes and what doesn't

The layers are separated here because omission and writer completion authorize the same downstream
disposition through different structural facts.

| Layer | Status |
|---|---|
| **Expiry policy** — what a confirmation authorizes (commit vs. retain, per the disposition matrix) | **Unchanged.** A declaration-based confirmation produces the same `ConfirmedAbsent` verdict and takes the same downstream path. |
| **Omission predicate** — the latest complete exact manifest from one node on one partition after the connection's last record omits it | **Implemented.** One manifest is sufficient because registry copy and final-record acknowledgement are linearized. |
| **Admissible signals** | **Strengthened.** One added, one deleted outright. |
| **Discard rule** | **New.** Not an absence proof at all. |

The evidence set gets strictly better in three ways:

- A **positive declaration** (`NoMoreWrites`, §3.3) is added as an independent evidence type.
  It covers writer completion rather than one connection: one record can settle every prior
  connection for that writer/partition, because it is an assertion by or about the writer
  rather than an inference from what is missing.
- **Omission's scope of necessity shrinks.** A node holds a partition until every connection it
  placed there has closed, and its `NoMoreWrites` is ordered after the last of those records — so every
  *terminal* and *handover* case is now settled by a statement. Omission is left covering only
  the connections of a node that is alive and still assigned, which is the case where the node is
  demonstrably able to speak for itself.
- The **dead-proxy residual narrows but does not disappear.** A surviving member can write
  `NoMoreWrites` after observing departure. If every member is gone, no process can produce that
  ordered fact, so unresolved work remains retained. §6.2 makes that accepted terminal behavior
  explicit instead of adding a second, out-of-band proof mechanism.

The **discard rule** is genuinely new policy and deserves its own line rather than riding along
under "absence proofs are unchanged": records arriving from a *peer*-declared finished node after
that declaration's offset are treated as inert. Only *peer*-declared: a node's own release is
retractable, since ordinary scale-down hands a released partition back to the same node and
discarding then would destroy live traffic (§4.3). That asymmetry is what makes a zombie's late
writes harmless to the commit decision, and it is also the direct cause of the completeness gap in
§6.1 — the traffic reached the source and is then deliberately dropped. Nothing in the
absence-proof framework implies it.

So: the policy is constant, the omission predicate is strengthened around the exact registry, the
evidence set gains writer completion, and one new discard rule is introduced whose cost is stated in
§6.1.

## 3. Protocol

### 3.1 Static setup

- One topic, `M` partitions, provisioned for the **peak** fleet size. Kafka can add
  partitions but never remove them, and vacant capacity is free here, so oversizing `M` up
  front means scaling proxies never requires a Kafka change.
- One consumer group, members = proxies. Each proxy `subscribe()`s to the traffic topic,
  `pause()`es every assigned partition immediately, and `poll()`s about every 100ms. It
  consumes nothing; membership is the only thing being used, and pausing means `poll()`
  issues no fetches.
- That `poll()` loop runs on **its own thread**, never shared with capture work. Otherwise producer
  backpressure on the capture path could stall heartbeats and get the proxy evicted, so capture
  slowness would masquerade as proxy death — and eviction trips the §3.5 latch, which is not
  recoverable in-process.
- `CooperativeStickyAssignor`, or a custom cooperative assignor if we later want block
  shapes. Cooperative is required, not an optimization: the eager protocol revokes every
  partition from every member on every rebalance.
- Each proxy publishes its `nodeId` in the subscription `userData` so members can name each
  other.

### 3.2 Per-proxy state

| State | Source | Used for |
|---|---|---|
| `nodeId` | fresh per process | attribution; never reused |
| `assignedPartitions` | assignor callbacks | where **new** connections may be placed |
| connection → partition | chosen at open, **immutable** | routing every subsequent record |
| partitions with live connections | `ProxyLivenessRegistry` key set | manifest targets, `NoMoreWrites` timing |

The connection→partition entry is chosen once and stored. Nothing recomputes it. This is
what makes adoption unable to remap anything: there is no hash whose inputs could shift.
`CaptureKafkaPublisher`'s existing consistency check becomes a map lookup rather than a
recomputation, keeping the assertion without the hazard.

### 3.3 Record types

Traffic records and manifest chunks as today, plus **one** declaration:

```
NoMoreWrites { nodeId, partition, declaredBy }
    // "nodeId has no more connections on partition, as of this offset"
    // declaredBy == nodeId  -> self-release; retractable by a later reassignment (§4.3)
    // declaredBy != nodeId  -> a peer observed nodeId depart the group; terminal
```

`declaredBy` is **load-bearing, not just diagnostic**: it selects whether the discard rule in §4.1
applies. A self-release settles the past without condemning the future, because a node can be
reassigned a partition it released (§4.3); a peer declaration is terminal, because its purpose is to
bound a node that may still be running (§4.2).

Earlier drafts had two types, `Release` and a fleet-wide `NodeDeath`. That was a confusing
split, because a node-scoped judgment cannot be consumed as one:

**A replayer reads each partition independently, so a fleet-wide fact has to be materialized on
every partition where it is needed.** There is no cross-partition inference — the reader of
partition P settles P's connections from P's records alone. So "X is dead everywhere" is not a
record the protocol can express; what it can express is M copies of "X writes no more on P,"
one per partition. The single type makes that explicit in the shape of the data rather than
leaving it to prose, and the replayer gets one settling rule instead of two.

The distinction that survives is **provenance, not scope**. Those are two separate axes, and the
earlier draft's real problem was varying both at once:

| | provenance (who asserted it) | scope (what one record licenses) |
|---|---|---|
| `Release` | the node itself | one partition |
| `NodeDeath` | a peer | "everywhere" |
| `NoMoreWrites` | either, in `declaredBy` | one partition, always |

Because both old types differed on both axes simultaneously, it was impossible to tell which axis
the replayer actually depended on — and the fleet-wide scope was unusable regardless, per the
paragraph above. Now scope is fixed: every declaration settles exactly one thing, "X is finished on P
as of this offset." Scope therefore distinguishes nothing, and the only remaining difference is who
wrote it.

That difference is consequential rather than cosmetic, and separating the axes is what makes it
visible. Provenance decides **durability of the claim, not its reach**: a self-release is retractable
by a later reassignment, a peer declaration is terminal. §4.1's discard rule branches on exactly
that. Had the two axes stayed entangled, "terminal" and "fleet-wide" would still look like one
property, which is how the scale-down bug in §4.3 got in.

The subtlety worth keeping straight is that the underlying *fact* behind a peer declaration
genuinely is broader — that node really is gone from every partition, not just this one. But
breadth of fact cannot turn into breadth of scope here, because no reader is in a position to use
it. So it surfaces as **how many records get written** — M copies, one per partition (§3.5's crash
case) — rather than as what any single record means. That is also why the two rows in §4.1
collapsed into one. There is deliberately no out-of-band replayer query that can settle all
partitions at once: without a log position it cannot prove which records it covers.

The record carries no generation or epoch. `nodeId` is fresh per process, so a declaration is
unambiguous forever without a counter.

### 3.4 Manifest emission

Every quantum, for each partition in `assignedPartitions ∪ partitionsWithLiveConnections`,
emit a manifest listing **all** of that node's open connections on that partition — not just the idle
ones. This inherits `replayerHardenedArchitectureDesign.md` §10.8's reasoning unchanged: "it was
active before the first snapshot" does not imply it will emit a record after that snapshot.

The registry is exact and this is load-bearing, not defense in depth. Registration linearizes before
the first traffic submission, a manifest copies the registry at one linearization point, and removal
linearizes only after Kafka acknowledges the final traffic record. Therefore a connection is present
in every manifest copied during its writable lifetime and absent only after its complete record set
is durably ordered before that manifest. One complete omission is enough; a second omission would
not repair an early-removal bug because both copies could occur during the same invalid gap.

Listing all open connections remains the simpler protocol even with an exact registry. It avoids an
activity classifier, makes an empty manifest unambiguous, and allows the replayer to compare the
latest complete manifest directly rather than combine traffic recency with declaration filtering.
Replacing the synchronized registry with weakly consistent iteration would invalidate the proof and
is not permitted.

Each manifest declares `chunkCount`; chunks are numbered exactly `0..chunkCount-1`. Every chunk in
one manifest carries one shared `emittedAtMillis`, and that value strictly increases per
`(nodeId, partition)` even if the proxy clock stalls or moves backward. Timestamps are diagnostic;
manifest completeness and authority come from chunk indexes and Kafka offsets.

Listing the full open set also means the fan-out in §8's transient-spike note is over all open
connections, not a filtered subset.

- Assigned with zero connections → emit an **empty** manifest. That is a positive statement
  (§5.4.2), and it is what distinguishes an idle-but-alive node from a dead one.
- Not assigned and zero connections → emit `NoMoreWrites{nodeId, partition, nodeId}` and stop
  touching that partition until it is assigned again. The declaration is a statement about the
  connections that existed when it was written, **not** a promise never to write here again — the
  partition can legitimately come back on a later rebalance, which is the ordinary scale-down path
  (§4.3).
- Never self-declare `NoMoreWrites` for a partition that is still assigned.

A self-declared `NoMoreWrites` must land after the node's last traffic record on that partition.
The idempotent producer (default) guarantees per-partition ordering for a single producer, so
"sent after" is "lands after" — but the final records must be handed to the producer before the
declaration is.

### 3.5 Lifecycles

**Startup.** Join the group, receive an assignment, begin accepting connections. No claim
record; adoption declares nothing.

**Scale-up.** B joins; the assignor moves some partitions from A to B. A's existing
connections on those partitions **drain in place** — A keeps writing their traffic records
and keeps emitting manifests listing them, on partitions B now owns for new connections.
A declares `NoMoreWrites` on each as its last connection there closes. No client connection is
disturbed by a scale-up.

**Scale-down.** B, C, and D leave; the assignor gives their partitions back to A, including
partitions A itself self-declared `NoMoreWrites` on during the earlier scale-up. A simply resumes
writing to them — `onPartitionsAssigned` adds them back to `assignedPartitions` and new connections
route there again. No record announces the reacquisition and none is needed; see §4.3, which is also
why the discard rule in §4.1 ignores self-declared records.

**Graceful shutdown.** Deregister from the load balancer → drain connections → declare
`NoMoreWrites` on every partition → **leave the group last**. Leaving early turns an orderly
departure into an eviction.

**Cooperative revoke.** `onPartitionsRevoked` only removes partitions from
`assignedPartitions`. It does not need to drain, because drain-in-place is legal — so the
callback returns immediately and never risks blowing `max.poll.interval.ms`.

**Eviction or stall.** `onPartitionsLost`, or a local staleness gate (see §5), trips a
one-way latch: this process never writes again, and per an operator flag either exits or
degrades to pass-through. A surviving member declares its departure.

**Crash.** No self-declaration. A surviving member observes the membership change and writes
`NoMoreWrites{X, P, self}` for **every** partition `P` in the topic — not just the ones it is
assigned, and not just the ones it believes X was using, since it has no reliable view of either.
M records per death, which is negligible next to traffic.

Every member computes departures from its own membership view, and any that notice may write.
Duplicates are idempotent: the rule in §4.1 is keyed on the earliest such offset per (X, P), and
a second copy at a later offset concludes nothing new. So no coordination or leader election is
needed to decide who writes.

## 4. Replayer rules

The replayer needs **no** group membership access, no admin API, and no write permission.
Everything is in-band and read-only.

### 4.1 Settling

A declaration does two separable jobs, and conflating them is a defect: **settling** the
connections that came before it, and **discarding** anything that comes after it. Settling applies
to both variants; discarding applies only to peer-declared ones. §4.3 explains why.

| Observation | Conclusion |
|---|---|
| `NoMoreWrites{X, P, *}` at offset O | every connection of X on P **already known at O** is dead as of O |
| latest complete exact manifest from X on P omits C, after C's last record | C is dead (§3.4) |

"Already known at O" is the load-bearing qualifier. The declaration settles the connections whose
last record precedes O; it is not a standing rule about the pair (X, P) that also condemns
connections X opens later. A connection first seen after O was never in the declaration's scope,
so it accumulates normally.

Both signals are usable while the replayer is lagging because both are positioned in the same Kafka
partition as the traffic they describe. There is no out-of-band membership verdict: a present-time
membership answer has no offset and therefore cannot prove which historical records it covers.

**Discard rule (peer-declared only).** Records arriving from X on P after a
`NoMoreWrites{X, P, declaredBy}` offset where `declaredBy != X` are **discarded** and counted. That
counter is the detection mechanism for the completeness gap in §6.1. Records after a
*self*-declared `NoMoreWrites` are retained and processed normally.

### 4.2 Why the discard rule is peer-only

Because a self-declaration and a peer-declaration are asserted about different things.

A **self**-declaration is a node's statement about its own past: "I have no connections here as of
now." It is not a promise about the future, and §4.3 shows it cannot be one. A node that later
writes to that partition again has not contradicted anything.

A **peer** declaration is a statement about a node that might still be running — that is the entire
reason it exists. Its whole purpose is to bound a zombie, so it must be terminal. It is safe to make
it terminal because the node it condemns can never legitimately write again:

- If the node truly died, a replacement is a new process with a fresh `nodeId` (§3.3), so it is a
  different subject and the declaration does not touch it.
- If the node was merely evicted or stalled, §3.5's one-way latch has already stopped it from
  writing. That latch is what makes the terminal reading sound; without it, an evicted node could
  rejoin under the same `nodeId` and have its traffic discarded.

Note also that a peer declaration about X never impedes any *other* node: declarations are keyed
per `(nodeId, partition)`, so `NoMoreWrites{B, P, A}` says nothing about A's own writes to P.

Dropping discard for self-declarations also fails in the safe direction. The records in question are
real captured traffic, and retaining them risks nothing worse than a duplicate; discarding them is
unrecoverable data loss.

### 4.3 Scale-down: a node reacquiring a partition it released

This is the case that forces the split above, and an earlier draft got it wrong.

Take one proxy A holding all `M` partitions. Scale up to four: the assignor gives A a quarter, and A
drains and self-declares `NoMoreWrites{A, P, A}` on the other three quarters (§3.4). Now scale back
down to A alone. **Kafka hands every partition back to A** — same process, same `nodeId`, and no
mechanism by which A could decline. A immediately begins writing new connections to partitions it
has already declared itself finished with.

Under a discard rule that ignored `declaredBy`, all of that new traffic would be silently dropped:
ordinary scale-down would become unbounded, undetectable-by-design data loss on the majority of
partitions. That is strictly worse than the problem the discard rule exists to solve.

Three properties make reacquisition safe, and none requires a new record or an epoch:

- **The discard rule does not apply**, because A's declaration was self-declared (§4.2).
- **Nothing was settled wrongly.** A only self-declares a partition with zero live connections, so
  at offset O there was genuinely nothing left on P to settle. The connections A opens after
  reacquiring have fresh connection ids and first records after O, so §4.1's "already known at O"
  qualifier excludes them.
- **The declaration was never falsified.** A self-declaration is scoped to a partition *and* to the
  connections existing when it was written; it claims nothing about future assignment. Reading it as
  "A will never write here again" is the misreading that caused the bug.

**Repeated cycles are fine and need no counter.** Scaling up and down repeatedly produces an
alternating sequence on P — traffic, self-declaration, traffic, self-declaration — and each
declaration settles only what preceded it. There is no state to reconcile across cycles, which is
why no era, epoch, or generation field is needed on the record or on traffic records. This is the
concrete payoff of `nodeId` being fresh per process.

The one thing reacquisition must not do is resurrect a condemned node. If a peer declared
`NoMoreWrites{A, P, C}`, A must never write again at all — enforced by the latch in §3.5, not by
anything in the reacquisition path.

### 4.4 Why adoption proves nothing

Stated explicitly because an earlier draft got it wrong. With 120 partitions and one proxy
A, A owns all 120 and has connections spread across them. B joins and takes 60. A still
has live connections on those 60 and must keep writing there. So "B is now the owner of P"
cannot imply "A's connections on P are finished." Only A can say that, or a survivor observing
A's departure — which is exactly the two provenances of `NoMoreWrites`.

Two nodes' manifests interleaving on one partition is already handled: the scanner keys
streams by `(nodeId, partition, routingPlanId)`, so A's connections can only be proven dead
by A's own manifests, and B's stream is a different key.

## 5. Local zombie containment

Broker-side fencing (`transactional.id` + producer epoch) would make a stale node's writes
*fail* rather than land-and-be-discarded. We are deliberately not doing that, because it
puts `read_committed` visibility latency and transaction-commit round trips on the whole
capture path to close a sub-second window, and the commit decision is already protected by
§4.1's discard rule. Documented as the upgrade if the notification gap proves to matter.

Instead, locally:

- **One-way latch** on eviction or staleness. Never clearable; requires a restart.
- **Staleness gate on the capture path.** A volatile `lastSuccessfulPollNanos`, read on the
  Netty thread; refuse to hand bytes to the producer if it is staler than budget. This
  closes the window from the near side — records are never created rather than created and
  discarded.
- **Bounded `delivery.timeout.ms`**, so batches cannot sit in the accumulator through a
  stall and flush after the handover.

A self-imposed watchdog is legitimate *here* and not for declaring others dead, because the
failure direction is safe: not firing leaves you no worse off, and firing spuriously
recycles a healthy proxy, which costs availability rather than correctness.

## 6. Failure audit

### 6.1 Capture completeness in the zombie window — detected by metric presence

A stalled node's records land after its peer-declared `NoMoreWrites` offset and are discarded. That traffic
reached the source but was never durably captured, so the target diverges. No protocol at
this layer can fix it, because the source already applied the mutation.

What determines the cost is the operator flag:

- **fail-closed** — the proxy fails the client request, so the source never applies it and
  there is nothing to diverge.
- **fail-open** — the proxy passes traffic through uncaptured, deliberately accepting
  divergence during proxy failures.

So the flag is availability versus **migration fidelity**, not availability versus
strictness. That framing belongs in customer-facing docs.

Detected by the discarded-record counter being non-zero.

### 6.2 The last proxy crashing — retained, never inferred

If every member disappears, no survivor can write `NoMoreWrites`. The protocol deliberately has no
second settlement mechanism for that case. A group-membership query describes the present but has no
Kafka offset, so it cannot prove which historical records it covers. A clock has the same defect and
is also inadmissible.

The replayer retains the unresolved commit head and either waits indefinitely or terminates the
current run in a premature terminal state. It emits:

- unresolved connection and commit-head counts;
- retained record and byte counts;
- the blocked partition, `nodeId`, and head offset;
- the last authoritative manifest offset, plus source and manifest timestamps for diagnostics only.

If the original process was stalled rather than dead and resumes under the same `nodeId`, later
traffic and manifests resolve the blocker normally. If a survivor observed the departure, its
ordered `NoMoreWrites` record resolves the blocker. A fresh replacement process has a new `nodeId`
and cannot retroactively prove what happened to its predecessor. Permanent retention after a real
total fleet crash is therefore an accepted availability outcome.

When capture resumes behind a retained head, hard record and byte budgets prevent unbounded local
growth. Reaching a budget pauses or terminates replay without advancing the commit. Every failure
direction remains "stall or stop," never "commit wrongly."

### 6.3 Not separate holes

- **Evicted-but-healthy node** writing during the discovery window: same discarded-record
  counter as §6.1. Not a distinct case.
- **Uneven or duplicated assignment**, however caused: benign, per §2.

## 7. Code deltas

| Change | Where |
|---|---|
| Remove the chunk-contiguity requirement | **Implemented.** `KafkaLivenessScanner` groups chunks by node, partition, plan, and sequence; unrelated records may appear between chunk offsets. |
| Use one complete exact omission | **Implemented.** `AbsenceProof.LivenessOmission` carries one complete manifest span after the connection's last record; scanner tests cover presence/omission replacement and malformed manifests. |
| Allocate one strict timestamp per manifest | **Implemented.** `CaptureKafkaPublisher` copies one strictly increasing timestamp to every chunk. It remains diagnostic only. |
| Record the partition per connection instead of hashing | replaces `PartitionRoutingPlan.partitionFor`; the registry already stores it |
| Delete level-1 routing (nodeId hash → shard start) | `PartitionRoutingPlan.forTopic`; `selectedPartitions` becomes the assignment |
| Drop `topicPartitionCount` from the plan digest, or drop `routingPlanId` outright | `PartitionRoutingPlan.makePlanId`. The mapping is fully determined by the stored per-connection partition, so the guard collapses to "a connection's partition stamp never changes", which `KafkaTrafficCaptureSource.java:648` already checks. |
| Group membership client | new, in the proxy: subscribe, pause, poll on a dedicated thread (§3.1), callbacks, and subscription `userData` carrying `nodeId` |
| Relieve the proxy duration cap of its correctness role | no code deleted — `replayer-expiration-hardening.md` §5.3/§5.4 and `replayerHardenedArchitectureDesign.md` §10.8 stop citing the cap as the finite window a dead-proxy proof needs. The cap stays as operational policy. **No wall-clock setting is removed, because none exists**: force-expiry was rejected (§7 there) and banned (§10.5 row, §20 non-goal). Verified by grep — nothing clock-driven is reachable from the Kafka commit path. |
| `NoMoreWrites` record | `TrafficCaptureStream.proto`; emitted by `CaptureKafkaPublisher` |
| Settle-on-declaration evidence | new `ScanEvidence` variant; `KafkaLivenessScanner` |
| Discard superseded records + counter | `KafkaTrafficCaptureSource` |
| Latch, staleness gate, failure-mode flag | `CaptureProxy`, `KafkaCaptureFactory` |

## 8. Out of scope for this round

- Broker-side fencing via `transactional.id` (§5).
- A custom block assignor. `CooperativeStickyAssignor` is sufficient now that vacancy is
  not needed for correctness.
- Static membership (`group.instance.id`). Would avoid rebalances on planned restarts, but
  needs a stable identity from outside the process, which is the deployment coupling we are
  avoiding.
- Deliberate expiry of over-old connections. This is the only sound way to shrink post-scale-up
  manifest fan-out: capping *manifests* is unsafe, since skipping one for a partition that has live
  connections manufactures a false omission (§3.4). So the lever is connection lifetime, not
  manifest count. Deferred — the spike is
  transient and bounded by the request-duration cap.

## 9. Decisions and remaining follow-ups

### 9.1 Decided

**Peer-declared `NoMoreWrites` records are mandatory.** They are a point-in-time observation whose
temporal ordering against the traffic they describe is critical, and putting them in the log is what
makes that ordering readable. An out-of-band membership answer has no offset and cannot substitute.
See §4.1 and §6.2.

**`declaredBy` stores the full `nodeId`,** not a self/peer bit. Declarations are rare enough that the
size is irrelevant, and storing the id makes "this is a self-release" checkable by comparison
(`declaredBy == nodeId`) rather than an unverifiable flag — worth having, since that bit now gates
whether traffic is discarded.

**Kafka membership timeouts are left at the client's defaults and are not set by us.** No values are
pinned here deliberately — the client version documents them, and restating them in a design doc only
creates something to drift. The reasoning behind not tuning them is that the tradeoff is asymmetric:
slow death detection costs retained memory and a delayed commit, both recoverable, while a *false*
eviction trips the §3.5 latch and takes a healthy proxy out of service over a GC pause. Bias toward
patience, which is what the defaults already do.

**Manifest fan-out gets no cap;** connection-lifetime expiry is the only sound lever and it is
deferred to §8.

### 9.2 Follow-up

Implement the remaining §7 rows and add multi-proxy integration coverage for scale-up, scale-down,
graceful departure, peer-observed loss, stale-writer discard metrics, and total-fleet-loss retention.
