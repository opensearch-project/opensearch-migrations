# Simplified Replayer Lifecycle Design

**Status:** Draft for discussion — tactical alternative to
[replayerHardenedArchitectureDesign.md](replayerHardenedArchitectureDesign.md)

**Date:** 2026-09-03

**Companion delta:** [replayerSimplifiedLifecycleDelta.md](replayerSimplifiedLifecycleDelta.md)

**Policy input:** [replayer-expiration-hardening.md](replayer-expiration-hardening.md)

This document is self-contained: it describes the target design in terms of roles and
contracts, without assuming familiarity with the current implementation or the other documents.
The companion delta maps every contract here onto the existing classes.

## 1. What This Design Is

The replayer reads captured HTTP traffic from Kafka, reconstructs request/response transactions
per source connection, replays the requests against a target cluster with time fidelity, writes
comparison evidence, and commits Kafka offsets only for records that are provably finished.

The existing pipeline shape is correct and is kept:

```text
Kafka consumer -> read gate -> accumulator -> concurrency limiter -> transform
  -> per-connection ordered scheduler -> Netty target exchange -> tuple evidence -> offset commit
```

What this design changes is the **connective tissue**: the callbacks, boolean flags, counters,
and optional interfaces that today distribute lifecycle decisions across many partially
overlapping paths. Production incidents in this machinery have repeatedly had the same shape —
a required signal silently swallowed (a `default {}` interface method, a dropped future, a
skipped notification), or a terminal decision (commit vs. retain) made implicitly by whichever
code path happened to run. The fix is not a new execution model; it is a small number of
flattened, mandatory, typed contracts.

Six rules govern the whole design:

1. **One disposition point.** Every Kafka record's terminal decision — close its tracing
   context, and commit or retain its offset — is made by exactly one function that every
   terminal path must call with a complete description of what happened.
2. **Events, not optional callbacks.** Lifecycle notifications are values delivered through one
   mandatory sink. There are no default no-op methods; an unhandled event is a compile error,
   not a silent swallow.
3. **Typed identity.** Connections, sessions, and requests are identified by structured keys
   carried with every event — never reconstructed from string concatenation, placeholder
   session numbers, or whichever traffic-stream key happens to remain in a list.
4. **Obligations, not counters.** Anything that must be acknowledged (a synthetic close, a
   drain gate) is represented by a per-item obligation that completes exactly once, including
   an explicit "there was nothing to close" completion. Gates await `allOf(obligations)`.
5. **Barriers, not fire-and-forget.** Every close, cancel, and shutdown operation returns a
   future that completes only when the operation's entire effect has settled.
6. **Evidence, not impatience.** Offsets commit only on structural evidence that a record is
   finished (completed replay, captured close, scanner-confirmed absence, or an explicit
   operator-classified discard). Elapsed wall-clock time never commits anything.

## 2. Roles and Threads

| Role | Responsibility | Thread |
| --- | --- | --- |
| Kafka source | Poll records, track per-partition offset low-watermarks, run rebalance callbacks, run the scan cursor | Single Kafka executor thread |
| Read gate | Block reads past `settledReplayTime + epsilon`; forward lifecycle notifications without loss | Caller thread, gate released by progress updates |
| Accumulator | Reconstruct requests/responses/closes per source connection; emit source lifecycle events | Single intake thread (the main read loop) |
| Replay coordinator | Bridge source events to replay work; own the pending-transaction registry and the disposition function | Intake thread for admission; completion threads funnel into the disposition function |
| Concurrency limiter | Cap simultaneous in-flight requests | Intake thread blocks on acquisition |
| Per-connection scheduler | Preserve source order and time-shifted send times per connection | Netty event loop assigned to the connection |
| Target exchange | One HTTP request/response against the target, with retries | Netty event loop |
| Evidence writer | Durably write the source/target tuple | Sink executor (or synchronous) |
| Scanner | Metadata-only look-ahead over the same consumer's assignment to prove a blocked connection dead or alive | Kafka executor thread, interleaved with polling |

No role gains a new thread. The design's concurrency discipline is: state is mutated only on
its owner's thread; other threads communicate by completing futures or enqueueing typed events
onto the owner's intake.

## 3. Identity Model

```java
record ConnectionTag(String nodeId, String connectionId, int sessionNumber, int generation) {}

record RequestTag(ConnectionTag connection, int requestIndex) {}
```

* `nodeId`/`connectionId` come from the capture; `sessionNumber` is the request index at which
  this logical session began (keep-alive reuse and restarts create new sessions on one captured
  connection); `generation` is the Kafka consumer's assignment generation when the records were
  read.
* Every lifecycle event, acknowledgement, registry key, and scanner verdict carries one of
  these tags. There are no placeholder session numbers and no `id + ":" + n + ":" + gen`
  string keys. Two code sites can only "agree on the key" by construction, because the key is
  one shared type.
* Records themselves are identified by `(topic, partition, offset, generation)`; the generation
  check is what makes stale commits from a previous assignment impossible.

## 4. Source Lifecycle Events

The accumulator communicates with the rest of the system through a single sink:

```java
interface SourceEventSink {
    void accept(SourceEvent event);
}

sealed interface SourceEvent {
    /** A complete source request is ready to replay. */
    record RequestReady(RequestTag tag, /* parsed request, timestamps, held record keys */ ...)
        implements SourceEvent {}

    /** The captured source response for a previously announced request is complete,
        or the source side ended without one (the cause says which). */
    record SourcePairSettled(RequestTag tag, SourceEndCause cause, ...) implements SourceEvent {}

    /** The logical source connection is finished. Carries the tag, the cause, and every
        record key still held at connection scope (possibly none). */
    record ConnectionFinished(ConnectionTag tag, ConnectionEndCause cause,
                              List<HeldRecordKey> heldKeys) implements SourceEvent {}

    /** Records deliberately skipped by accumulation policy (e.g. dropped requests). */
    record StreamsDiscarded(ConnectionTag tag, DiscardReason reason,
                            List<HeldRecordKey> heldKeys) implements SourceEvent {}
}

enum SourceEndCause { RESPONSE_COMPLETE, CAPTURED_CLOSE, CONFIRMED_DEAD, READER_INTERRUPTED, SHUTDOWN }

sealed interface ConnectionEndCause {
    record CapturedClose(...) implements ConnectionEndCause {}
    record ConfirmedDead(ScanProof proof) implements ConnectionEndCause {}
    record ReaderInterrupted(int partition) implements ConnectionEndCause {}
    record Shutdown() implements ConnectionEndCause {}
}
```

Properties this flattening buys:

* **One interface, exhaustively handled.** The consumer switches over a sealed hierarchy;
  adding an event type breaks the build everywhere it must be handled. There is no bundle of
  five semi-required callback methods and no `default {}` trap.
* **No returned continuations.** Today's pattern — "request callback returns a consumer the
  accumulator must later invoke with the finished pair" — threads a closure through
  accumulator state. Instead, `RequestReady` and `SourcePairSettled` are separate events
  correlated by `RequestTag`; the coordinator keeps the pending map. The accumulator holds no
  foreign callbacks.
* **One intake ordering point.** Real observations, captured closes, reader-interrupted
  synthetic closes, scanner verdicts, and shutdown all arrive as events on the same serialized
  intake, so the accumulator's single-threaded contract holds and no second thread ever mutates
  accumulation state.
* **Expiry causes are explicit.** There is no single overloaded "expired" status. A source side
  can end as `CONFIRMED_DEAD` (evidence, commit-eligible) or `READER_INTERRUPTED`/`SHUTDOWN`
  (out of runway, never commit). The cause travels with the event, so downstream policy never
  infers it.

Control events flowing the other direction (from the Kafka layer into the intake) use the same
shape: a reader-interrupted close and a scanner verdict are values queued ahead of real records,
processed on the intake thread.

## 5. Request Path and the Pending-Transaction Registry

For each `RequestReady`:

1. The coordinator registers a **pending transaction** under the `RequestTag`. The entry holds:
   the held record keys, the limiter permit (once acquired), the source-side outcome slot, the
   target-side outcome slot, the evidence outcome slot, and the owned request payload handle.
2. The request acquires a limiter permit, is transformed, and is scheduled on the connection's
   ordered scheduler at its time-shifted send time. These stages may fail or be cancelled; every
   stage's failure routes to the same place (step 4).
3. `SourcePairSettled` fills the source slot whenever the accumulator reaches it — before,
   during, or after target work.
4. When both the source and target slots are terminal (or target work is terminal and the
   source is settled by a connection-level cause), the transaction is **finalized**: evidence is
   written if the policy requires it, and the disposition function (§6) is called exactly once
   with all three outcomes.
5. Registry removal happens only after disposition returns. An entry leaving the registry
   therefore proves contexts were closed and the offset decision was made — not merely that a
   completion handler ran.

Ordering within a connection is preserved by the existing per-connection scheduler; this design
does not change how order is enforced, only what happens at the ends of each scheduled item.

## 6. The Disposition Function

One function makes every record's terminal decision:

```java
void disposeRecords(
    RequestOrConnectionTag tag,
    SourceOutcome source,        // completed / captured-close / confirmed-dead(proof) /
                                 // reader-interrupted / shutdown
    TargetOutcome target,        // succeeded / failed(classification) / cancelled(reason) /
                                 // filtered / not-attempted
    EvidenceOutcome evidence,    // durable / failed / not-required
    List<HeldRecordKey> keys
);
```

Rules:

* It **always** closes each key's tracing context, exactly once. Context closure is never
  conditional on the commit decision and is owned by no other code.
* It commits or retains according to the matrix below. There is no boolean parameter and no
  path that reaches neither decision. An exception thrown anywhere upstream must still deliver
  its keys here — the coordinator's finalization wrapper guarantees it.
* Failure classification is a three-class policy, decided by retries plus an operator-declared
  classifier (never guessed at catch time):

| Class | Example | Commit? | Behavior |
| --- | --- | --- | --- |
| Deterministic (poison) | Record that fails identically every run, matched by the operator classifier | Yes | Commit with a loud, durable skip record: ERROR log with identities, metric, evidence entry |
| Transient | Sink I/O hiccup, transform glitch | No | Retry with backoff; on exhaustion halt loudly without committing |
| Teardown | Cancellation from rebalance, connection teardown, shutdown | No | Close contexts, retain records for redelivery, neither crash nor retry |

* Decision matrix (rows the function must handle exhaustively; anything unlisted retains and
  halts loudly):

| Source outcome | Target outcome | Evidence | Decision |
| --- | --- | --- | --- |
| Completed | Succeeded | Durable | Commit |
| Captured close (request never completed) | Not attempted | Durable discard evidence | Commit as deliberate discard (explicit policy) |
| Confirmed dead (scanner proof) | Succeeded or not attempted | Durable | Commit |
| Deliberate discard (dropped/ignored) | Not attempted | Durable discard evidence | Commit |
| Any | Failed: deterministic (classified) | Durable skip evidence | Commit only when the operator classifier is configured |
| Any | Failed: transient, retries exhausted | Any | Retain; halt loudly |
| Reader interrupted / shutdown | Cancelled or not attempted | Optional diagnostics | Retain |
| Completed | Cancelled by ordinary close | Any | Retain; surface as an invariant violation (this should be impossible) |
| Any | Any | Evidence write failed | Retain; halt loudly |

* **Cancellation never selects a commit row**, no matter how commit-eligible the source status
  looks. This single rule replaces today's scattered status-based inference.

## 7. Acknowledgement Obligations and Barriers

### 7.1 Close acknowledgements

When the Kafka layer must wait for connections to finish (a revoked partition's synthetic
closes, or shutdown), it registers one **obligation** per `ConnectionTag`:

```java
CompletableFuture<CloseAck> obligation = closeLedger.expect(tag);

sealed interface CloseAck {
    record SessionClosed(ConnectionTag tag) implements CloseAck {}
    record NoSessionExisted(ConnectionTag tag) implements CloseAck {}
}
```

* If a live target session exists, its close path completes the obligation.
* If no session exists (the connection never produced enough data to open one, or was already
  torn down), the lookup path completes the obligation **explicitly** with `NoSessionExisted`.
  Absence is an answer, not a missed callback.
* The read gate for resuming real records is `allOf(obligations for the revoked generation)`.
  There is no counter to leak, and duplicate acknowledgement is structurally impossible
  (a future completes once).
* There is no timeout that resets the gate and proceeds lossily. A watchdog may log and halt
  loudly; it may not discard obligations.

### 7.2 Operation barriers

* **Close/cancel a connection** returns a future that completes only after: pending
  transformation timers settled, scheduled work drained with cancellation, ordering slots
  drained, the channel actually closed, the session evicted, and the close acknowledgement
  delivered.
* **Limiter shutdown** drains its queue by completing every queued acquisition exceptionally.
* **Process shutdown** enumerates live sessions and applies the same close operation to each,
  awaits the barriers, finalizes every pending transaction as teardown (retain), and only then
  closes the Kafka consumer and sinks. Correctness must not depend on JVM exit.
* **Waiting for remaining work** waits on registry entries' terminal futures; cancelling the
  aggregate wait does not count as draining the work it represents.

## 8. Resource Ownership

Reference-counted request payloads get an explicit contract:

* Transformation hands the coordinator **one owned payload handle** per request. The pending
  transaction owns it; disposition closes it exactly once on every terminal path.
* Each send attempt takes a retained slice/duplicate it must release itself; retries therefore
  never share ownership ambiguously with the original.
* The diagnostic snapshot kept for evidence is its own retained copy, owned by the evidence
  summary, released when the summary is written or the transaction is disposed — whichever
  terminal path runs.
* The same "one owner, closes once" rule applies to limiter permits, tracing contexts, timers,
  and registry entries. Leak detection (Netty leak detector plus owner counters) is part of the
  standing test suite: every test ends with all registries empty and all counters at zero.

## 9. Expiration Policy: Epsilon, Scanner, Proxy Cap

These ship together, as specified in the expiration-hardening design; the contracts above are
what make them safe to wire in.

### 9.1 Epsilon lookahead

The read gate admits records only up to `settledReplayTime + epsilon`, with epsilon a small
smoothing margin (~30s) rather than a 400s expiry buffer. Read-ahead stays **coupled to
completed work**: while replay work is outstanding, the settled time advances with completions,
not with the wall clock. This coupling is the memory bound; removing it without an equivalent
low-watermark controller is prohibited.

### 9.2 Scanner

Because epsilon reads can never reach the point where timestamp-driven expiry would fire, a
blocked commit head needs a structural verdict instead:

* The **same consumer** runs a metadata-only scan cursor: after a poll, seek ahead within the
  scan window, decode only connection identity/timestamps/observation kinds, discard payloads,
  seek back. Same consumer ⇒ same partition assignment ⇒ verdicts are always about partitions
  this process actually replays.
* Verdicts: **follow-up present** (leave alive), **confirmed absent** (emit
  `ConnectionEndCause.ConfirmedDead(proof)` as a control event into the serialized intake), or
  **inconclusive** (never commit-eligible).
* `ScanProof` carries partition, generation, the scanned offset/time bounds, the follow-up kind
  that was required, and the configured connection-duration cap — enough to audit later why a
  commit was justified. If the assignment or generation changed mid-cycle, the cycle's results
  are discarded.
* Scanning is continuous, not stall-triggered, so load is steady and dead state is expired
  promptly.

### 9.3 Capture-proxy duration cap

The capture proxy optionally enforces a maximum connection duration and writes a **real close
observation** before closing. The scan window is `connectionTimeout + maxConnectionDuration`;
the two values are a matched pair. Without a finite cap the scanner can only ever return
inconclusive, because "no follow-up within the window" proves nothing about an unbounded
connection.

### 9.4 What may never expire anything

Wall-clock age. Heartbeats and monitors are read-only diagnostics; they may report a suspicious
blocker (with its backside ceiling — the last observed source timestamp — not its insertion
wall time), but they may not mutate accumulator state or trigger commits. Two expiry mechanisms
racing always resolve in favor of the impatient one, so the impatient one must not exist.

## 10. Observability

| Area | Signals |
| --- | --- |
| Read gate | settled replay time, epsilon utilization, records buffered |
| Scanner | scan distance and latency, verdict counts (present/absent/inconclusive), bytes discarded |
| Transactions | registry size by phase, terminal outcome counts, disposition reason counts |
| Commits | worst commit head across partitions (identity + age), unresolved obligations, staged commit latency |
| Resources | owned payload count/bytes, duplicate-close attempts, permits held/queued |
| Proxy | connections closed by the duration cap |

Every disposition row increments a reason-labeled counter, so "why did/didn't this commit"
is answerable from metrics without log archaeology.

## 11. Verification

* **Exhaustiveness tests:** every `SourceEvent`, `SourceOutcome`, `TargetOutcome`,
  `EvidenceOutcome`, and disposition row has a direct test; sealed types plus switch make
  omissions compile errors.
* **Interleaving tests:** with fake clocks and manually completed futures, enumerate
  permutations of source settlement, target completion, cancellation, and close; assert one
  disposition per record, contexts closed exactly once, no commit on any teardown path, and no
  registry residue.
* **Obligation tests:** synthetic closes for connections with and without sessions; the gate
  reopens on real acknowledgements only, and never via timeout.
* **Scanner tests:** follow-up present (survives), confirmed absent (commits with proof),
  inconclusive (retains), generation change mid-scan (discarded), live long connection under
  epsilon (not expired).
* **Leak tests:** Netty leak detection on; all owner counters zero at test end.
* **Restart tests:** at-least-once redelivery after retain paths; committed confirmed-dead
  records are not re-read.

## 12. Non-Goals

Deliberately out of scope, in contrast to the full hardened-architecture proposal:

* No connection actor, mailbox, or new command model — the existing per-connection ordered
  scheduler is kept.
* No replacement of the concurrency limiter's mechanism, only a drain contract on close.
* No new transaction state machine object — the pending-transaction registry is a bookkeeping
  entry plus one finalization function, not an executor-owned actor.
* No part-level evidence store or request/response commit decoupling in this design; the
  disposition function's evidence outcome is deliberately shaped so a later granular evidence
  writer can slot in without moving commit policy again.
* No change to Kafka's at-least-once model, HTTP reconstruction, or transformation behavior.
