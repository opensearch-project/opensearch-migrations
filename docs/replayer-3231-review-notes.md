# PR #3231 Review Notes

**PR:** [#3231 — fix(replayer): resolve rebalance deadlock and drain-gate bugs blocking Kafka consumers](https://github.com/opensearch-project/opensearch-migrations/pull/3231)
**Author:** Aman-bv · **Branch:** `fix/replayer-rebalance-deadlock-and-drain-gate` · 21 files, ~1200+/~85−
**Reviewed against:** `main` @ `af05a02e5` (v3.3.7)
**Status of these notes:** working analysis, nothing posted to the PR yet.

Companion to [`replayer-expiration-hardening.md`](replayer-expiration-hardening.md). That doc
owns the *design*; this one owns *what to do about this PR*. They overlap because #3231 touches
the same expiry/commit machinery Phase 1 rewrites.

---

## 1. Why this PR matters to the design work

#3231 is not a competing design — it is a set of incident-driven fixes to the machinery Phase 1
depends on. Two of its changes are unambiguous bug fixes we want regardless of what happens to
the design (§3.1, §3.6). Two others reach for **wall-clock impatience** as an expiry trigger
(§3.2, §3.5), which is exactly the approach the design doc rejects for PR #3207 and for the
same reason. The remaining three are small and fine.

The important asymmetry: their changes are *load-bearing for an incident happening now*, while
Phase 1 is a design still under review. The fixes should land first. The parts that encode a
temporal expiry policy should not land, because Phase 1 will have to remove them.

---

## 2. Verdict summary

| # | Change | Verdict |
|---|---|---|
| 1 | Commit tuple output in a `finally` block | **Rework** — needs classification, may lose replays |
| 2 | Remove the `isWorkOutstanding()` guard from the idle updater | **Push back** — bounded stall → unbounded read-ahead |
| 3 | `TrackedFuture` duplicate-parent ISE → warn | **Approve** |
| 4 | `schedule.clear()` → `drainWithCancellation()` in `closeClientConnectionChannel` | **Approve** |
| 5 | Wall-clock force-expiry in the accumulator heartbeat | **Reject** — wrong clock; see §3.5 |
| 6 | Rebalance drain-gate trio | **Approve 6b; question 6a; scrutinize 6c** |
| 7 | Observability (timing diagnostics, limiter/consumer heartbeats) | **Approve** |

---

## 3. Change-by-change

### 3.1 Change 4 — `schedule.clear()` → `drainWithCancellation()` — APPROVE

`ClientConnectionPool.closeClientConnectionChannel` (line ~211 on main) still calls
`session.schedule.clear()`, which drops pending `scheduleFuture` entries on the floor. Nothing
ever completes them, so `OnlineRadixSorter` stalls on orphaned futures and the
`requestWorkTracker` entries and `TrafficStreamLimiter` permits they hold are never released.
That is a permit leak that outlives the connection.

`drainWithCancellation(cause)` (`TimeToResponseFulfillmentFutureMap:58`) completes each pending
future exceptionally and then clears — snapshotting the deque first, because
`completeExceptionally` fires `whenComplete` callbacks that re-enter `removeFirstItem()` on the
same deque. Pairing it with `scheduleSequencer.cancelAllWork()` matches what `cancelConnection`
already does on main (`ClientConnectionPool:156-161`), so this change makes the close path
consistent with the cancel path. Straightforwardly correct.

**Where the `CancellationException` lands** — this is the part that matters, because it's the
hand-off into change 1. The cancellation propagates to `hookWorkFinishingUpdates`, which is
attached via `whenComplete` and therefore fires on failure as well as success: the frontier
advances and `totalCountOfScheduledTasksOutstanding` decrements. Good. It then continues into
`handleCompletedTransaction` → `processCompletedTransaction`, arriving as a `CancellationException`
(an `Exception`, so it does *not* take the `rethrowUnexpectedThrowable` path). What happens next
is entirely governed by change 1 — see §3.3.

### 3.2 Change 2 — removing the `isWorkOutstanding()` guard — PUSH BACK

```java
 private void updateContentTimeControllerWhenIdling() {
-    if (isWorkOutstanding()) {
-        return;
-    }
```

The stated rationale is that stuck tasks freeze the gate and starve source-time-based expiry.
The mechanism is real: while work is outstanding the idle updater is suppressed, so the frontier
advances only as fast as the slowest outstanding send.

But the fix trades a **bounded stall** for **unbounded read-ahead**. With the guard removed the
barrier tracks the replay clock unconditionally, so a target that has stopped responding no
longer slows reading at all — the replayer keeps pulling records into memory for as long as the
stall lasts. Today's `lookahead=400` bounds that at ~400s of traffic; the whole point of
Phase 1 is to shrink that number, not to remove the coupling that makes it meaningful.

The stall it's fixing is also *bounded by construction*: sends are capped at
`MAX_RETRIES(4) × targetServerResponseTimeoutSeconds(150)`, so outstanding work drains within
~10 minutes even against a dead target. That's a latency problem, not a livelock — the design
doc classifies it under "not locks (bounded-slow)."

**Recommendation:** revert change 2 and re-test the reported gate-freeze with changes 3, 4, and
6b in place. My hypothesis is that the freeze was a *symptom* of the orphaned futures from
change 4 — futures that never complete mean work that never stops being "outstanding," which
freezes the gate forever. That *is* an unbounded stall, and change 4 fixes it at the root. If
the freeze reproduces after 4 lands, the right fix is a bound on how long the idle updater may
stay suppressed, not deleting the guard.

**Evidence needed to settle it:** with 3+4+6b applied, run a slow/dead-target simulation and
watch (a) whether `tasksOutstanding` ever stops draining, and (b) read-ahead growth
(`kafkaRecordsLeftToCommitEventually`) with the guard present vs. absent. Their new
`BackpressureGateAdvancesWithWorkOutstandingTest` asserts the *new* behavior, so it will need to
change either way; it is not evidence that the new behavior is desirable.

### 3.3 Change 1 — commit in a `finally` block — REWORK

```java
 try {
     try (var tupleHandlingContext = httpContext.createTupleContext()) {
         if (!writeTupleOutput(...)) { return; }
     }
     countFinalOutcome(summary, requestFailure);
     recordTargetResponseCodes(summary);
 } finally {
     if (tupleWriter == null) {
         commitTrafficStreams(rrPair.completionStatus, rrPair.trafficStreamKeysBeingHeld);
     }
 }
```

**The gap they found is real.** On main, if anything throws between `writeTupleOutput` and the
commit call, the commit never happens and the held offsets are orphaned — with no loud failure.
That's a **silent orphan**, and it's the same class of bug as F1/F2 in the design doc. Worth
noting that the `tupleWriter != null` path already handles this correctly:
`failReplayForTupleWrite` (`TrafficReplayerCore:419`) commits nothing, raises a fatal `Error`
naming the held offsets, and tells the operator *"Fix the tuple sink failure before restarting;
otherwise replay will remain blocked at these offsets."* The legacy `tupleWriter == null` path
never got that treatment.

**But the fix converts silent-orphan into silent-drop, which is worse.** `finally` commits
unconditionally on *every* exception route — including the `CancellationException` that change 4
now deliberately injects. A send cancelled mid-drain would have its offsets committed as though
the replay had happened. Not committing is a failure that forces someone to figure out why;
dropping a message because of a glitch, with no obvious signal, is the worse outcome.

#### The policy this should implement

Three classes, not two:

| Class | Examples | Commit? | Behavior |
|---|---|---|---|
| **Deterministic / poison-pill** | Malformed record that fails identically every run | **Yes** | Commit **+ loud skip**: ERROR with ids, a counter metric, durable evidence. A silent skip is never acceptable. |
| **Transient** | S3 hiccup, IO glitch in tuple writing | **No** | Retry with backoff, with retry counters and logs. On exhaustion, **halt loudly without committing** — `failReplayForTupleWrite` already does exactly this. |
| **Teardown** | `CancellationException` from drain / rebalance / shutdown | **No** | Don't commit, don't crash, don't retry. Records re-deliver to the next owner — the existing `TRAFFIC_SOURCE_READER_INTERRUPTED` semantics. |

The teardown row is the one #3231 gets wrong, and it's the class its own change 4 creates.

**Deterministic vs. transient cannot be judged at catch time.** The mechanism is retries plus
an operator-declared poison classifier — precedent already exists in this repo with
`--nonRetryableDocExceptionTypes` / `BulkItemErrorClassifier` on the RFS side. Reuse that shape
rather than inventing a second vocabulary.

**One distinction worth being precise about:** by the time control reaches
`processCompletedTransaction`, the replay has *already happened*. So "don't commit" is protecting
two different things depending on the failure:

- the **replay** itself, when the send failed or was cancelled (nothing was validly replayed);
- only the **evidence**, when the send succeeded and just the tuple write failed.

Both justify not committing, but they differ in whether a re-replay is desirable or merely
harmless. Worth deciding explicitly rather than by accident.

**Concrete rework:** keep the `finally` structure — it's the right shape for closing the gap —
but make the commit conditional on a classification of the in-flight failure, defaulting to
*don't commit + loud* for anything unrecognized. Explicitly suppress on
`CancellationException`. And extend `failReplayForTupleWrite`'s treatment to the
`tupleWriter == null` path so both paths implement the same policy.

**Test to write:** cancel a send mid-drain (i.e. exercise change 4's path) and assert the held
offsets are **not** committed and are re-delivered on the next assignment. I expect current
#3231 to fail that test.

### 3.4 Change 3 — `TrackedFuture` duplicate-parent ISE → warn — APPROVE

`setParentDiagnosticFuture` threw `IllegalStateException` when `parentDiagnosticFutureRef` was
already set. That reference is purely a diagnostic breadcrumb chain — the comment right below it
says the ancestry is deliberately truncated because its value decays. Throwing a control-flow
exception to protect a debugging aid is backwards, and the retry path can legitimately re-parent
a future. Downgrading to a warn-and-return is correct.

Nit: the warn will be noisy if the retry path hits it routinely. Consider `atDebug`, or dedupe.

### 3.5 Change 5 — wall-clock force-expiry in the heartbeat — REJECT

`logHeartbeat` is renamed to `heartbeatAndExpireStaleConnections` and gains:

```java
var wallClockExpiryThresholdMs = connectionTimeout.toMillis() * 3 / 2;
...
var lastPacketMs = accum.getNewestPacketTimestampInMillisReference().get();
var lastPacketAge = lastPacketMs > 0 ? now - lastPacketMs : 0;
if (lastPacketAge > wallClockExpiryThresholdMs) { ... fireAccumulationsCallbacksAndClose(...); }
```

**This subtracts a source timestamp from a wall clock.** `newestPacketTimestampInMillis` is
*source* time — it's written from the observation timestamps in `ExpiringTrafficStreamMap`
(`updateExpirationTrackers`, line ~89) and consumed by `ExpiringKeyQueue` for source-time
expiry. `now` is `System.currentTimeMillis()`. The subtraction is only meaningful when replay is
running at `speedup == 1` *and* has caught up to live traffic.

The consequences are severe and go in both directions:

- **Replaying historical capture** (the normal case — a capture from last week): every
  connection's source timestamp is days behind wall-clock, so `lastPacketAge` is enormous from
  the first heartbeat and **every live connection is force-expired immediately**, including
  perfectly healthy in-flight ones.
- **`speedup > 1`**: the source clock outruns wall-clock, so the threshold is effectively
  tightened by the speedup factor and expiry fires early.
- **`speedup < 1`**: fires late, which is merely useless rather than harmful.

Then compounding it: `fireAccumulationsCallbacksAndClose` is called with
`EXPIRED_PREMATURELY`, which is **not** in `commitTrafficStreams`' suppress set
(`CLOSED_PREMATURELY` and `TRAFFIC_SOURCE_READER_INTERRUPTED` only). So these force-expirations
**commit**. Combined with the above, that's silent data loss on a historical replay — exactly
the failure mode the design doc rejects PR #3207 for, with a bug on top.

Also, independent of the clock error: this runs on the heartbeat scheduler thread while
`accept()` runs on the read loop, and the accumulator is documented as expecting single-threaded
observation processing. `Accumulation.hasBeenExpired` is made `volatile` in this PR, which
suggests the author noticed the race; volatile on one flag doesn't make
`fireAccumulationsCallbacksAndClose` safe to call concurrently with `accept()`. The loop also
mutates `liveStreams` while iterating a snapshot taken via `.collect(toList())` — the snapshot
avoids `ConcurrentModificationException` but not the underlying interleaving with `accept()`.

**Recommendation:** drop the expiry behavior. Keep the rename and the `wallClockExpired` counter
only if it counts something diagnostic. If a stuck-connection signal is wanted now, log a
warning with the connection id and let a human act — that's evidence-gathering, not policy.
Phase 1's scanner is the structural answer.

### 3.6 Change 6 — the rebalance drain-gate trio

**6a — `onNetworkConnectionClosed` key mismatch: QUESTION.** The change rewrites the lookup to
use `PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER` instead of the passed `sessionNumber`:

```java
-var sessionKey = connectionId + ":" + sessionNumber + ":" + generation;
+var sessionKey = connectionId + ":" + PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER + ":" + generation;
```

Registration does use the placeholder (`KafkaTrafficCaptureSource:185`), so a mismatch would
indeed leak the counter and wedge the drain gate forever. **But the only production caller —
`TrafficReplayerTopLevel:236`, via `setGlobalOnSessionClose` — already passes
`PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER`.** So on main today the keys already agree, and I
can't construct the leak from production code.

Worth asking the author which caller they observed passing a real session number. If there is
one, that's the actual bug and it should be named. If there isn't, this change hardens the
lookup against a future caller — fine, but then it should be framed that way, and it changes
the meaning of the `sessionNumber` parameter to "ignored," which deserves a comment or removing
the parameter. Note their edit to test #1 inverts what that test asserted (it previously proved
a non-placeholder key matched; now it proves the parameter is ignored) — that's a semantic
change to a regression test and should be called out, not slipped in.

**6b — `BlockingTrafficSource` delegation: APPROVE, and needed regardless.** `BlockingTrafficSource`
implements `ITrafficCaptureSource` but did **not** override `onNetworkConnectionClosed` or
`onConnectionAccumulationComplete`; both are `default {}` no-ops on the interface
(`ITrafficCaptureSource:37,51`). Since `runReplay` is typed on `BlockingTrafficSource` and that
is what gets wired into `setGlobalOnSessionClose`, **every close callback in production is
silently swallowed** — it never reaches `KafkaTrafficCaptureSource`. So the counter never
decrements, and the drain gate never opens. This is the real deadlock, and it's a genuine
find: an empty default method on an interface silently absorbing a required notification.

This one should land on its own, ahead of everything else in the PR. It's ~10 lines, it's
independently testable, and it's the fix for the incident.

**6c — 5-minute drain-gate timeout with forced reset: SCRUTINIZE.** After
`DRAIN_GATE_TIMEOUT_NANOS`, `handleDrainGateIfActive` resets the counter, clears the pending map,
and logs at ERROR — the message itself admits *"data loss may occur for in-flight connections."*

With 6b in place, the condition this backstop exists for should be unreachable. Keeping a
watchdog that self-describes as lossy, for a state that can no longer occur, mostly guarantees
that when it *does* fire it will be for an unrelated reason and will cause a fresh incident.
The design doc's rule applies: a mechanism that discards records on a timer is impatience, not
evidence.

If a backstop is wanted, make it **halt loudly rather than proceed lossily** — that's the same
call as §3.3, and consistent with `failReplayForTupleWrite`. Also worth checking against
reassignment semantics: clearing `pendingTrafficSourceReaderInterruptedCloses` wholesale
discards state for connections that may belong to a partition we still own.

The `touch()` call inside the drain loop (keeping the consumer alive so we don't fall out of the
group mid-recovery) is good and should be kept independent of the timeout question. The
`catch (RuntimeException)` around it is reasonable given a new assignment can race the pause.

### 3.7 Change 7 — observability — APPROVE

`warnIfReadWasSlow` / `acceptWithTimingDiagnostic` / `warnIfBatchWasSlow` (a clean extraction of
the existing inline batch-timing warn, plus per-record and read-phase equivalents),
`TrafficStreamLimiter.logHeartbeat` with a semaphore-exhaustion warn, and the
`TrackingKafkaConsumer` heartbeat upgraded to report the *worst* commit head across all
partitions instead of an arbitrary first one. All useful and all aligned with the design doc's
metrics table (§5.4) — the worst-head reporting in particular is what the "commit-head age"
row wants.

Two notes:

- Uses fully-qualified `java.util.concurrent.*` / `org.slf4j.*` / `org.opensearch.migrations.Utils`
  inline in several places instead of imports. Cosmetic, but inconsistent with the file.
- `commitHead` now reports the worst partition while `commitTail` still reports the *first*
  tracker's high-watermark, so the two fields in one log line can describe different partitions.
  Either label them or make both worst-partition.
- The `age` here derives from `peekHeadMetadata().addedAt`, which is wall-clock at insertion —
  a fine stall signal, but not the "backside ceiling" (last observed source timestamp) the
  design doc needs. Don't let this metric be mistaken for that one.

---

## 4. Recommended sequencing

1. **Land 6b alone** (`BlockingTrafficSource` delegation) — the actual deadlock fix, ~10 lines.
2. **Land 4 + 3 + 7** — permit-leak fix and diagnostics, low risk.
3. **Re-test the gate freeze** with 1–2 applied. Decide change 2 on that evidence; expected
   outcome is that it's no longer needed.
4. **Rework change 1** against the three-class policy, with the cancellation test from §3.3.
5. **Drop change 5.** Reopen only as diagnostics.
6. **Resolve 6a** by asking which caller passed a real session number; **replace 6c's lossy
   reset** with a loud halt, or delete it once 6b lands.

---

## 5. Impact on the design doc

Nothing in #3231 changes Phase 1's or Phase 2's direction. What it changes is the starting line:

- **Phase 0 is not finished.** The design doc's Phase 0 covers F1/F2 (shipped in #3225).
  #3231's 6b and 4 are two more bugs in the same family — machinery that silently drops a
  required notification, or silently orphans work — and they belong in the same phase. Add them
  to §4 once they land.
- **§5.1's ε-lookahead must be re-derived if change 2 lands.** The whole ε argument assumes the
  frontier is coupled to completed work; removing the `isWorkOutstanding()` guard decouples them
  and makes read-ahead depend only on the replay clock. That doesn't break the scanner, but it
  does invalidate the memory-bound reasoning in §5.1, which would then need an explicit
  read-ahead cap instead. Another reason to prefer reverting change 2.
- **§5.2's scanner is the answer to what change 5 was reaching for.** Both are trying to expire
  connections that source-time expiry can't reach. Change 5 asks "has enough time passed?"; the
  scanner asks "do follow-up records exist?" When Phase 1 lands, any wall-clock expiry must be
  removed, or the two mechanisms will fight — and the impatient one will win, because it fires
  first.
- **§5.4's metrics table should absorb change 7** rather than duplicate it. Worst-head-across-
  partitions is strictly better than what the table assumed exists.
- **#3231 renames `logHeartbeat` → `heartbeatAndExpireStaleConnections`** and touches
  `CapturedTrafficToHttpTransactionAccumulator`, `ReplayEngine`, `TrafficReplayer`'s heartbeat
  scheduler, and `KafkaTrafficCaptureSource` — all files Phase 1 edits. Expect conflicts; if
  change 5 is dropped the rename should be dropped with it, which removes most of them.

**Open question for Phase 1, surfaced by this review:** the design assumed the scanner would
inject a synthetic expire event and let the accumulator's normal machinery clear the blocker.
#3231's change 5 is a crude version of exactly that injection, and it exposes that
`EXPIRED_PREMATURELY` **commits**. Phase 1 needs `EXPIRED_PREMATURELY` (or a new status) to
distinguish the two expiry modes from §5.2's invariant table — confirmed-dead commits,
out-of-runway does not. Today's single status cannot express both, so Phase 1 must either add a
status or pass the commit decision explicitly.
