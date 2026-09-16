# Capture & Replay Docs Review — 2026-09-18

Scope: all nine documents in `docs/captureAndReplay/` at commit `e8013e98`. Focus: soundness and
cross-document consistency, ahead of a tone/tightening pass.

## Verdict

No blocking soundness errors. The core arguments hold and agree across levels:

- capture-before-forward (proxy §5.5 / architecture §4) and the baseline-ordering argument in
  architecture §8 (replayer baseline ≥ proxy baseline in both cases);
- the `E + S` expiration proof and the `E + 2S` restart fallback (the algebra in §8/§8.1 checks
  out; the fallback can only delay, never advance, expiration);
- whole-record commit with advancement across consecutive finished records beginning with the earliest uncommitted record;
- the two-condition demand model and its recompute rules;
- the deterministic `B + W` retry boundary (freeze-before-payload, irreversibility, `S`
  tolerance);
- shutdown/retirement timelines (60/240/270/300s) and the revocation grace flow are numerically
  consistent everywhere they appear.

## Genuine inconsistencies (fixed in the tightening pass)

1. **Stale thread model in the async guide.** `asyncMessagePassingProgrammingGuide.md` §4 says
   the replay-intake input queue is "drained only by the Kafka executor." The processing
   architecture §4.1 and both LLDs define a dedicated replay-intake thread separate from the
   Kafka thread. The guide (2026-09-14) predates the split (2026-09-15).
2. **Empty-poll demand recompute.** Architecture §2 and §15 list "a poll result, including an
   empty result" / "including empty polls" among demand-recompute triggers. Processing §13.4 and
   the Kafka LLD §13 state that empty polls are never sent to replay intake (the outstanding
   batch request simply remains outstanding). The lower-level model is the intended one.
3. **`ConnectionRequestStarted` trigger.** Architecture §2 and processing §3.2/§7.2 say the
   message fires when the request "actually begins its turn"; the connection LLD §8 fires it on
   `FirstTargetWriteSubmitted` (first accepted target write). These diverge if cancellation lands
   between `BeginTargetTurn` and the first write. Standardized on the LLD trigger.

## Clarity/wording issues (also addressed)

- Processing §8.3: "a completely quiet live partition or `range` archive cannot establish a
  record-based retry boundary or broker-time expiration" — misleading; in `preserve` mode,
  higher-offset records *inside* a range archive do establish both. Only the end of the range
  supplies no further evidence.
- Symbol collisions: architecture §8.1 reused `H` (unseen prior heartbeat) although §4 defines
  `H` as the heartbeat publication interval (renamed to `U`); managed fleet §2.1 used `N` for
  broker skew while the replayer docs use `N = P * T` (renamed to `K`).
- Architecture §10 referenced "the proxy's five-second `F` default" without ever defining the
  default (only the proxy doc does); the default is now stated where `F` is introduced.
- Proxy §1.1: "detached before its connection interval was reset" used an undefined term;
  reworded to the intended meaning (a detached complete request replays even if the connection's
  remaining reconstruction later expires).
- Architecture §9: heartbeat records were said to finish "after the replayer updates the
  baseline"; a late heartbeat that does not move the baseline still finishes and commits.

## Non-issues checked and cleared

- `CaptureCapabilityProbe` "creates no state" vs. serving as `R` evidence: consistent — using a
  record's `LogAppendTime` as evidence creates no state for that record's writer.
- Retention of the writer-partition baseline across expiration vs. the `T` fallback: consistent
  across architecture §7/§8, processing §5.2, and Kafka LLD §10.3 (baseline kept while any
  incomplete state remains; fallback only when no baseline exists).
- Proxy §3.5 leave-group-while-draining vs. "membership is load balancing only": consistent.
- `W` = 5s vs. `F` = 5s: deliberate and explained; not a conflict.
- BYO archive doc vs. architecture §2.3: timestamp modes, `finalized`/`range` end modes, and the
  partition-end input agree.
- `minEpoch`/`maxEpoch` in dump output remain diagnostic-only labels (no protocol epoch).

## Tightening pass (this branch)

Rewrote all nine docs for accessibility and brevity with headings/anchors and all normative
content (values, invariants, orderings, test obligations) preserved, except the enumerated fixes
above. Managed-fleet doc was compressed hardest and reframed: it exists to confirm the design can
support a future controller, not as a near-term implementation contract.
