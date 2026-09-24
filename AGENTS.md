# Agent execution contract — capture/replay hardening

**Read this before doing anything else. It governs both rebuild plans.**

This file is the execution contract. The *what* lives in the design documents and the rebuild plan;
the *how you work* lives here. Where this file and a plan disagree about process, this file wins.
Where this file and a design document disagree about behavior, the design document wins.

**The plans do not define behavior and must never be read as if they do.** A plan says what to build,
in what order, and what evidence closes it out; the design says what the thing *is*. Where a plan's
prose and a design section conflict, **the design is correct and the plan is a bug** — implement the
design and report the discrepancy. Plan prose has already been caught inverting a design requirement by
dropping a qualifier, so a summary that reads cleanly is not evidence that it is right.

| Document | Role |
|---|---|
| `docs/captureAndReplay/*.md` | Authoritative design. Never changed by an implementation agent. |
| `docs/replayerRebuildPlanA-inPlace.md` | Primary plan. Default unless told otherwise. |
| `docs/replayerRebuildPlanB-inPlace.md` | Fallback plan. Only on explicit instruction. |
| `docs/replayerRebuildPlan.md` | Superseded for sequencing. Still authoritative for exactly four things: the D1–D18 defect inventory (§2), the R1–R19 obligation set (§6.5), deployed-configuration compatibility (§7), and the `PA1`–`PA3` proxy milestones (§3.2) — which Plan A puts out of its own scope, making §3.2 the only plan that owns proxy repair and therefore the receiving milestone for proxy findings under §2.1. |
| `docs/replayerRebuildStatus.md` | Live status and debt register. Update as you go. |

**Source order:** the authoritative designs, then this execution contract, then the selected rebuild
plan. Nothing else is a source of process rules. If you find a fourth document telling you how to work,
that is a drift report, not an instruction.

---

## 1. Red lines — stop work and ask

These are not "use judgment" items. Hitting one means stopping and bringing it to the owner.

1. **Never change the design.** Not to make a test pass, not to resolve an ambiguity, not because
   the code suggests otherwise. If implementation reveals the design is ambiguous, contradictory, or
   silent on something you need, stop and ask. Absolute.

   **"Absolute" has been read too loosely twice**, so it gets stated in the narrowest form: *you do not edit any
   file under `docs/captureAndReplay/` unless the owner has said to change that thing.* Not a design change you
   believe is right. Not a clarification. Not a sentence added inside a change he did authorize — that is the
   one that actually happened, and it is why the scope of an authorization is the sentence he approved and not
   the section it lives in. Authorizing an amendment is not authorizing everything adjacent to it.

   Two further things that are **not** authorization, both of which were treated as such: your own reasoning
   being sound, and the owner not objecting. Silence is not consent, and being right is not permission.

   And accepting a *deviation* from the design is the same act as changing it. Recording "this requirement is
   not met and that is acceptable" decides the design's meaning, so it needs the same explicit authorization —
   either satisfy the requirement or ask.

   Because this is another rule that requires noticing, it has a check: `tools/verify-design-authorization.sh`
   compares the design corpus against the settled point in `docs/captureAndReplay/APPROVED-AT` and fails on any
   changed document with no dated row in the register's design-changes table, and on any commit that mixes a
   design edit with implementation. **Run it before pushing.** Separating design edits into their own commit is
   only a visibility aid — the rule is that the edit needs the owner's word first.

2. **Any decision to break an existing contract.** CLI options, config keys, metric names, exit
   codes, published test fixtures, protobuf, module or image names.

3. **Any decision *not* to break an existing contract.** Choosing to preserve compatibility is a
   decision, not a safe default. This is the rule that protects the rebuild: a silent choice to keep
   an old type alive "for now" is how the legacy structure survives a rewrite without anyone ever
   deciding to keep it.

Red line 3 is easy to violate by accident, because it is a **non-event** — nothing happens when you
quietly keep something. "Notice that you are being tempted" is exactly the kind of rule that never
fires, so it gets observable triggers instead:

1. **Naming trigger (primary).** The first time new code references a legacy type, *that reference* is
   the red-line-3 decision. Write the row then. This one is greppable, so a reviewer can check it
   without judgment.
2. **Thrash trigger.** The **third** time you rework the same *concern* within one milestone, stop and write
   down what is making it hard. Repeatedly reworking one thing is the observable signature of shoehorning
   new behavior into a shape that does not want it. **The unit is the concern, not the file** — this was
   written as a file rule and duly missed when one behavior was revised four times across two files, which
   is the shape it most needs to catch. Past the third revision, §3.1b says to stop implementing it and ask
   the reviewer for a patch.
3. **Responsibility-placement trigger.** At every milestone boundary, for each responsibility the
   milestone was supposed to move or remove, name the file that holds it now and check that against where
   the design assigns it. **A responsibility still living where the design does not put it is a reportable
   finding**, not a judgment call. Size is not the test — a large file that mirrors the design's state
   model and can be reasoned about is fine. What matters is whether the milestone actually moved the thing
   it existed to move.

## 2. How to escalate

Escalation is a **mechanical step, not a judgment call.** Rules that require noticing get skipped; rules
that are numbered steps get done. So:

- **Batch, don't trickle.** Accumulate decisions and bring a component's worth at once. Forty
  serial interruptions is worse than one table of forty rows.
- **Arrive with the work done.** Every escalation carries the analysis, a recommendation, and a
  default. The owner should be able to answer in one sitting, in the form "1a, 2c, 3a".
- **Format:** a table with one row per decision — what the decision is, the options, which contract
  it touches, reversible or not, and your recommendation.
- **Irreversible actions block.** Design changes, contract breaks, deleting code, and the final
  swing stop until answered.
- **Reversible actions do not block.** Recommend a default, proceed, and log the row in
  `docs/replayerRebuildStatus.md` so it can be vetoed later.

The owner reserves the right to adjust direction at any stage boundary. Expect it. Do not treat an
earlier decision as settled if the owner revisits it.

### 2.1 Deferrals and out-of-scope findings — amend the plan or the work is lost

A deferral is a decision, and the record that survives is the one in the plan. This applies to **both**
shapes the same way: scope a milestone cannot deliver, and a defect or repair found while doing one
milestone that belongs to another. The second is the easier one to lose, because it does not feel like a
deferral — it feels like filing a note. Three things happen **in the same commit**:

1. **The deferring milestone says what it no longer delivers**, in its own section and in its `Exit`
   line. A milestone whose `Exit` still claims work it did not do cannot be closed honestly, and the
   next agent reads that line as done.
2. **The receiving milestone gains the obligation**, in its scope and its `Exit`. Name it in the section
   where the work will actually happen — an obligation recorded only at the point it was deferred *from*
   is invisible to whoever is positioned to discharge it. Prefer the milestone that already builds the
   thing the deferred work needs, so it lands as that milestone's evidence rather than as an errand.
3. **The register's deferral ledger gets a row**: what, from where, to where, and why. Per §6 the plan
   states which milestone *owns* an obligation while the register states whether it is open, proved, or
   deferred — this is that split, not a second copy.

**If you cannot name the receiving milestone, the work is not deferred, it is dropped** — and that is an
escalation, not a judgment call. Deferring into "later" or into the register alone is the failure this
rule exists to prevent. A `Milestone` column holding a workstream name, a document name, or a phrase like
"proxy workstream" is that failure wearing a milestone's clothes: **the value must be a milestone
identifier that appears as a section in a plan**, because that section is what someone reads when they
start the work. Proxy findings therefore go to `PA1`–`PA3` in `replayerRebuildPlan.md` §3.2, which is the
only plan that owns proxy repair.

A workaround compounds this. If a finding is worked around rather than fixed — a build flag, a disabled
check, a skipped test — **the receiving milestone's own text must say the workaround is deleted as part of
that repair**, and the code carrying it must name the milestone. Otherwise the workaround outlives the
defect and silently becomes the design.

## 3. Reviews

Three different things are called "review" and they have different rules.

### 3.1 Agent review — per milestone, repeated until conformance

Codex or a subagent reviewing a diff against the designs.

**This section previously said one bounded pass per milestone, and that was wrong.** Five successive
review passes over `G0`–`G2` each produced real, high-value design-conformance defects — one critical, several
major — and the last round's findings were mostly in code the previous round's fixes had introduced. A fix is
new code. Expecting it to be correct because it was written in response to a review is exactly the assumption
the reviews kept disproving.

What the old rule got right is that **"review until no findings remain" cannot terminate**, because an agent
asked for problems will always produce some. The answer is not a cap on rounds but a termination criterion
tied to the *class* of finding, since one class is objectively checkable against a finite document and the
rest are not.

**Two classes, two dispositions.**

1. **Design-conformance defects — unbounded rounds, every one must be fixed.** A finding qualifies only if
   the reviewer **quotes the design text it contradicts**, with document and section. Every such defect is
   fixed in-milestone. There is no "won't-fix" for this class and no triage into the register as a way of
   not doing it. It may be *deferred* only under §2.1's full procedure — a named receiving milestone that
   owns the component, both plan ends amended, a ledger row — and never to a milestone chosen because it is
   far away.

2. **Everything else — one pass, triaged.** Test-fidelity gaps, plan bookkeeping, naming, structure,
   efficiency. Triaged into blocker / register / won't-fix exactly as before, once.

**Termination.** A milestone's review is complete when a pass returns **no unfixed design-conformance
defects**. That is reachable, because the design is finite and each round removes real contradictions rather
than opinions. **Any production change re-opens it** — including a change made to fix a finding. If a round
produces fixes, the next round reviews those fixes.

**Guard against the failure mode this creates.** An unbounded loop invites a reviewer to reclassify
preferences as conformance defects to make them mandatory. Two things prevent that: the verbatim quote
requirement, and that **a finding is verified before it is accepted.** Across those five passes, three
claims did not survive verification — one cited a rule whose trigger condition was absent, one counted
correctly but drew a conclusion the plan already contradicted, and one **prescribed behavior the design
forbids**, which would have introduced a defect had it been applied. Verifying is not optional politeness;
it is what stops a review from doing damage.

- **Scope it precisely.** Give the reviewer a specific diff, the named invariants it must not violate, and
  specific questions with verdicts expected. Do not ask it to "find problems" or "suggest improvements".
- Reviewers are for **checking**, not deciding. A reviewer's findings are input to an escalation, never
  authority to change course — and never authority to change a design.

### 3.1b Hand the next attempt to the reviewer when you are thrashing

Review normally produces findings and the implementer fixes them. **When the same concern keeps coming back,
swap roles: ask the reviewer for a patch and verify it instead of writing the next attempt yourself.**

The evidence is the retirement-measurement episode. Over four rounds: a third measurement added that the design
forbade and that was not needed; removed; replaced by an explanation that was wrong for one of three cases; then
a diagnostic enum whose `NONE` case over-claimed against `§15.4`. The round that fixed it was the one where the
reviewer supplied a **patch** rather than a finding, and that patch was better than what the previous three
rounds had produced. The reason is not capability — it is that each of my attempts reasoned from the mechanism I
had just built. An agent with no stake in that design produced a cleaner one immediately.

**The trigger already exists and was missed.** §1's thrash trigger fires on the third edit of one file within a
milestone: "stop and write down what is making it hard." The metric code was revised in four consecutive
commits and the trigger never fired, because it reads as a file-level rule and this was one *concern* moving
across two files. So it is restated for this purpose:

> **Third revision of the same concern within one milestone.** Not the same file — the same behavior, wherever
> it lives. At that point stop implementing it and ask the reviewer for a patch.

A second, sharper signal, specific to repeated review: **a round whose findings are in code the previous
round's fix created.** One of those is ordinary. Two consecutively on the same concern means the implementer is
the bottleneck, not the reviewing.

**How the swap works, and what does not transfer.**

- The reviewer supplies a patch. It is still a *proposal*: verify it against the design exactly as you would
  verify a finding, because a patch can contradict the design as easily as a prescribed fix can — and one
  already has.
- **Verify, do not apply.** The last swap's patch was sound and still had two defects: a malformed table and a
  semantics gap its own documentation did not mention. Applying without checking converts a good patch into an
  unexamined one.
- **The commit message and the register stay with you.** They carry the reasoning and are the durable record; a
  patch carries a diff. Rewrite them rather than paraphrasing the patch's own summary.
- **Take control back** when the patch contradicts a design section, when its fix is larger than the defect, or
  when verifying it costs more than writing the fix would have. The swap is a tool for a stuck concern, not a
  default division of labour — most work in a milestone never needs it.

### 3.1a Calibrating a design-conformance review

The prompt is the whole instrument. These requirements exist because each corresponds to a way a real review
pass in this project went wrong, and `tools/review-prompt-design-conformance.md` is the current text.

- **Quote the design, verbatim, with section.** A finding with no quote is not a conformance defect, whatever
  else it may be. This is the load-bearing requirement: it is what makes the class objectively bounded.
- **Say when the design is SILENT, and stop there.** Silence is an owner escalation under red line 1, never a
  defect and never a licence to infer the intent. Several findings were really "the design does not say",
  which is a different and more important report.
- **Trace reachability in the live code, and say plainly when something is unreachable by construction.** A
  latent defect behind an impossible precondition is worth recording and is not the same as a live one.
- **Check the prescribed fix against the design too.** The most dangerous finding this project received was
  correct that the code was wrong and prescribed a remedy `kafkaLLD §5.7` forbids.
- **Exclude `REBUILD-LIMBO` regions.** That code is deliberately inert; reviewing it produces findings about
  code that does not run.
- **Ask specifically whether tests and fakes can fail.** In this project that has been the single
  highest-yield question — four separate cases of a test asserting a property it could not observe, or a fake
  violating the contract it stood in for. §4.1 attacks the same problem from the other side.
- **Read-only, per §3.2a.** The one exception is §4.1's falsification pass, which mutates by design and is
  therefore confined to a throwaway worktree.

### 3.2 Agent review — one full correctness review at production-complete

Separate from the per-milestone passes and scheduled by the plans, not here: when the **production code**
is implementation-complete and before test triage, one deep correctness review of the whole
implementation against the design. This is the only unbounded-scope review in the process, and it is
deliberately placed where there is a complete artifact to review rather than a partial one.

### 3.2a Rules that apply to any agent review

- **Never allow a review agent to edit files.** Read-only tools only, enforced by the tool allow-list.
- **Do not let a slow or unavailable reviewer block otherwise safe implementation progress.**
- Tell the reviewer explicitly what is outside the current milestone.
- Ask for concrete correctness or design-conformance defects with file paths, severity, and reasoning.
  Accept `NO_ACTIONABLE_FINDINGS` as a complete answer.
- Launch a long review while an independent compile or test has several minutes left to run.

### 3.2b Invoking a Claude review in this environment

The repository directory is already trusted. AWS credentials are not needed; Midway is sufficient.
Do **not** use `--permission-mode plan` (it can block waiting for `ExitPlanMode`), and do not use
`--restricted` or `--safe-mode` here. Allow up to 30 minutes for a substantial review — prior successful
runs took about 11 and 14 minutes — and poll for stdout, stderr, PID, and exit code at least every 60
seconds.

```bash
claude -p 'Act only as a non-mutating code reviewer. Review the specified milestone diff against the cited authoritative designs. Identify concrete correctness or design-conformance defects only, with file paths, severity, and reasoning. Return NO_ACTIONABLE_FINDINGS if none exist. Do not edit files.' \
  --permission-prompts none \
  --no-session-persistence \
  --output-format text \
  --tools 'Read,Grep,Glob,Bash' \
  --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git status *),Bash(git show *)'
```

The owner may choose to run reviews externally himself. When he has, do not duplicate them.

### 3.3 Owner review — the human

The owner reviews at milestone boundaries, and what he reviews is the **escalation table plus a
summarized findings report with impact and mitigation** — not raw reviewer output, and not the diff.
Findings are never silently absorbed. Per §2 he is also the only one who decides to change plans, relax a
rule, or accept a contract break.

## 4. Tests and observability

The posture is **integrate first, observability as the debugging substrate.** Not coverage.

- **No coverage targets.** Not per stage, not overall. Do not propose them.
- **Every milestone clears the same five process gates: compiles, wired, observability present,
  decisions escalated, status table updated.** A milestone also names its own evidence — the specific
  behaviors it must demonstrate — and that evidence is what the work is judged on. What is *not* an exit
  criterion is passing the inherited suite, or hitting a coverage bar.
- **Implement a responsibility as a complete usable chain:** producer, queue, owner, consumer,
  observability, construction path, and evidence. Another milestone mentioning one link is not a reason
  to defer it. Defer only for a specific missing design decision or an unavailable prerequisite, using
  §2.1's full procedure.
- **Observability is a deliverable of the milestone that creates the component**, not a later pass.
  It must exist before the big-bang integration; logging added retroactively means the first
  debugging pass is blind.
- **Write the few high-leverage tests immediately.** Deterministic, fast, and covering behavior whose
  bugs are miserable to find in logs. The high-value set is: ownership transfer, observed ordering,
  exactly-once milestones, permit conservation, commit authority, cancellation races, fatal-transition
  behavior, serialization round-trips, and the owner-transition invariants behind R16/R17. These must be
  solid — the load test is final affirmation, never the driver of the loop.
- **It is acceptable to leave inherited tests broken** while old and new architectures are only partially
  connected, *provided* the reason and the intended repair milestone are recorded in the status table.
  Stabilize the production end-to-end path first; decide where outside-in tests give the most leverage
  once production is coherent.
- **Metrics are conservation invariants** — see the definition below.
- **A counter with fixed cardinality needs no approval.** Owner, 2026-09-24: "It's fine to add more counters —
  those are decisions you never have to ask me for if the cardinality is fixed." So adding one is not a red-line-2
  contract decision and does not go in an escalation table; record it in the register's metric list and move on.
  *Fixed* cardinality is the whole condition: a counter whose attributes include a generation, partition,
  connection or request identity is one series per identity forever, and that **is** a decision to bring.

### 4.1 Falsification — a test that cannot fail is not evidence

A green test proves only that it ran. At each milestone's review pass, every test asserting a
**timing, ordering, interruption, or waiting** property is checked by removing that property from the
production code and confirming the test then fails. The inversion used and the failure observed are
recorded with the milestone's evidence.

This is not ceremony on every assertion. It is scoped to the class of property whose test can silently
assert nothing, because the *absence* of an event is indistinguishable from an event that was never
reachable. A plain value assertion fails loudly when the value is wrong; "X happened before Y" passes
just as well when neither happened.

The rule exists because four G0–G2 tests passed for reasons unrelated to their names, and none was
subtle:

- two revocation tests advanced the injected clock past the deadline *before* revoking, so the
  grace-wait body never executed;
- one was named for a commit staged during the grace interval and never called `onPartitionsRevoked`;
- the real-broker wakeup test caught `WakeupException` itself, so the production boundary that must
  catch it could stay broken underneath a passing test; and
- two real-proxy tests gated on "at least one record", which a startup capability probe satisfies
  before the record under test exists.

Each would have been caught by the same check, and the cost of the check is near zero for a real test.
It is expensive only for a test that is not testing anything, which is the point.

**Run it as a subagent in a throwaway worktree**, not by hand and not in the working tree: the subagent
breaks one property, runs the narrowed test, reports pass or fail, and the worktree is discarded. A
*pass* is the finding. Doing this in place risks committing a deliberate break, and doing it by hand is
how it gets skipped under time pressure.

### Two terms this document uses precisely

**"Wired"** means reachable through the real message paths, not merely present and compiling. A component
is wired when the actual startup path constructs it, its inputs arrive through the real queue or owner
mechanism rather than a test calling its methods directly, and its outputs go somewhere that consumes
them. A class with unit tests and no production caller is **not** wired — that is how 707 lines of
`RecordDispositionLedger` sat dead with zero references. If a milestone's real consumer does not exist
yet, wiring to a named shell counts, provided the status table names the milestone that replaces it.

**"Conservation invariant"** means an accounting identity that must balance exactly, asserted as an
equation rather than eyeballed on a dashboard. Nothing is created or destroyed silently: every record
read reaches exactly one terminal disposition, so per partition

```
records_read == records_replayed + records_skipped + records_failed
```

must hold, together with sequence continuity (no gaps in the offsets accounted for). The point is that
this is *falsifiable* — it is a different kind of claim from "the run finished" or "the error count looks
low," and it is what a top-level test asserts on. It is also what makes verification cheap at full scale:
if the counters balance and have been validated against exact per-record comparison at small scale, they
are trustworthy evidence at 200 MB/s where per-record comparison is not affordable.

### Harness and isolation constraints

- **Do not add Mockito mocks, stubs, captors, or verifications** to rebuilt lifecycle tests. Use the
  repository's established patterns instead — `SimpleHttpServer` / `SimpleNettyHttpServer`,
  `LocalChannel` / `EmbeddedChannel`, injected `TestEventLoop` and `FakeClock`, and immutable scripts
  configured at fixture construction rather than mutable runtime stubbing.
- **Those patterns are two tiers and do not compose on one event loop.** No test can have both
  deterministic time and a real channel: every Netty channel validates the loop's concrete type, so
  `LocalChannel`, `EmbeddedChannel`, and anything connecting to `SimpleNettyHttpServer` all reject
  `TestEventLoop`. Pick one — injected `TestEventLoop` and `FakeClock` with a fake `TargetChannelPort`
  for owner logic, ordering, timers and cancellation; or a real channel on a real `NioEventLoopGroup`
  with real time for integration. `TestEventLoop.register` throws and says so, because Netty's own path
  merely fails a promise, which a test that ignores the returned future experiences as a hang.
- **No mutable static state**, with a single possible exception for a logging integration that cannot
  reasonably be injected. Telemetry, clocks, coordination counters, event loops, and termination
  behavior are instance-owned and injected. A test must be able to stall only the event loop it owns.
- **Never sleep to wait for something to happen.** Wait on the signal instead: a latch, a future, an
  injected clock you advance, or a counter you poll until it reaches the expected value. A comment of the
  form "long enough that X has certainly happened" is a race with a note attached — it passes on a quiet
  machine and fails in CI, and when it fails it accuses the wrong code. If nothing observable marks the
  moment you need, **add the instrumentation**; a counter incremented on entry to a phase is visible while
  that phase is still running, whereas a span is only exported once it ends.

  One narrow exception, and only because it inverts the logic: a deliberate dwell whose duration is then
  **asserted on** is a measurement, not a wait. Sleeping 400 ms and then requiring the observed operation to
  have lasted at least 400 ms proves the thing was genuinely in progress; if it was not, the assertion
  fails loudly instead of the test passing for the wrong reason. Sleeping 400 ms and then hoping is the
  banned form. The test is whether a failure of your assumption breaks the test or hides in it.
- Never run two tests concurrently when they share an event loop, port, static state, Gradle output, or
  external process. Note `@IsolatedTest`.
- When replacing a test, keep the assertions conceptually stable while changing the mechanics. Do not
  change production semantics, assertions, and the test paradigm at the same time.

### Iteration rule, including the load tier

Unit and deterministic component tests are the implementation loop. Long-running broker, process,
end-to-end, and load tests are confirmation only.

When a large or load test finds a defect: **stop using it as the loop.** Bisect through logs,
split the discovered behavior into the smallest deterministic tests that reproduce it, fix against
those, then re-run the large test once. Never re-run a load test to discover the next mistake, and
never re-run it before smaller tests exist for what it found.

## 5. Build and tooling

- **Always pass `-x spotlessJavaCheck -x spotlessJavaApply`** on every Gradle invocation. Spotless is a
  reformatter with no correctness risk; running it per iteration costs hours across a rebuild and about a
  minute once at the end. Settled — do not re-litigate.

  ```
  ./gradlew :TrafficCapture:trafficReplayer:compileJava \
    -x spotlessJavaCheck -x spotlessJavaApply \
    --parallel --max-workers=18 --no-build-cache
  ```
- Use the narrowest module or `--tests` filter while iterating.
- Do not run the full historical suite to decide whether work is going well. Passing the historical
  suite is not an acceptance criterion; passing the smallest suite that proves the design is.

### Commits and pull requests

- Author the owner's commits as `Greg Schohn <schohn@amazon.com>` and include
  `Signed-off-by: Greg Schohn <schohn@amazon.com>` on every applicable commit.
- **Commit genuine progress every few hours.** Do not accumulate a 10,000-line review.
- **Keep tests in the same commit as the production behavior they prove.** Do not create commits
  containing only test migration or assertion churn.
- **Do not satisfy that rule by combining unrelated work.** Independent design fixes, infrastructure
  fixes, behavior changes, and CI repairs stay in separate commits. This is about not *burying* an
  unrelated fix inside another change.
- **Equally, do not fragment one change set.** A single coherent piece of work is **one commit**, however
  many files it touches. Splitting it so each part looks independently justifiable is noise, and there
  will be many changes ahead — err toward one commit per coherent change rather than a stack of them.
- **This is about commits, not milestones.** Commit often, review once per milestone. §6 still forbids
  subdividing a *milestone* for reviewability.
- When rewriting inherited history, preserve original authors, author dates, committers, and commit
  dates, and keep the detailed commit descriptions when consolidating.
- **Rewrite into a candidate branch first when the work is big enough to warrant it** — the owner's
  call, not a blanket rule for every rewrite. Verify the candidate before moving the working branch.
- Use `--force-with-lease`. Never an unguarded force push.
- Push real progress regularly and keep the PR description synchronized with completed milestones,
  validation evidence, reviewer findings, and dispositions. Start from the structure of PR #3352, link
  the authoritative designs and the active plan, and maintain a concise progress ledger.

### Parallelism and hardware

The development machine has 18 cores and 64 GB of memory. Use it, but optimize for **elapsed time**
rather than maximum process count.

- Fan out independent code inspection, compilation, and test work.
- Use subagents for substantial independent questions, not tiny edits whose coordination costs more than
  they save.
- Use separate worktrees when builds could write overlapping Gradle outputs.
- **Never parallelize tests that share an event loop, port, static state, Gradle output, or external
  process.** When work units become small or tightly coupled, go serial.
- Parallel work is what exposed why mutable statics are dangerous: they make otherwise independent tests
  interfere. Fix the ownership problem rather than widening test serialization.

## 6. What *not* to do

- **Do not re-read the *whole* design corpus at step boundaries.** Reading the sections relevant to what
  you are building is mandatory; re-reading all 8,474 lines repeatedly is where the time goes.
  **Each milestone in the plans carries a `Design refs:` line naming the sections it is built from. Read
  those, and `grep docs/captureAndReplay/` for anything else you need.** There is deliberately no derived
  summary document: a digest is a second copy of the design that can drift, whether it paraphrases or
  quotes.
- **Do not track obligation status inside a plan.** A plan may say which milestone owns which
  obligation; whether it is proved, open, or deferred lives in one grep-able table.
- **Do not infer Kafka revocation behavior from memory or from a plan summary.** Read the authoritative
  text on staged commits and cancellation during `onPartitionsRevoked` —
  `replayerKafkaSourceAndIntakeLowLevelDesign.md` §15.1 and
  `replayerProcessingAndCommitArchitecture.md` §9.2 — and escalate if it still leaves a choice open.
- **Do not paraphrase the design, and do not implement from a paraphrase.** Before building a component,
  read the design sections its milestone cites. If a milestone's cited sections do not cover something
  you need, that is a gap to escalate — not a licence to infer from the plan's summary. The designs were
  built carefully and they are short enough to read: nine documents, 8,474 lines total, and no single
  milestone needs more than a fraction of that.
- **Do not write prose checkpoints.** A status row per obligation, not a narrative.
- **Do not subdivide a milestone to make it reviewable.** If a milestone is too big to review, say
  so and escalate; do not split it into "migrate the remaining callers" slices.
- **Do not carry two correctness models.** If a new mechanism and the one it replaces are both live
  with real callers, that is a defect to report, not a transitional state to extend.

## 7. Out of scope here

- **Managed-fleet behavior** (`managedFleetCaptureRecovery.md`) is not implemented. It exists to
  prove the architecture can support a future controller.
- **The proxy workstream (PA1–PA3)** is handled in a separate conversation. Do not start it, and do
  not gate replayer work on it.

## 8. Code shaping

Preferences about the *shape* of the implementation. Where the designs specify a structure, they win —
these apply to the decisions the designs leave to the implementer.

### 8.1 Comments describe the code, not how it got there

The deliverable is code a developer can read and change correctly. It is not a record of the rebuild.

A comment earns its place by stating something a reader needs in order to change this code safely and
cannot see from the code itself: a constraint that is not local, an invariant the types do not carry, a
reason the obvious simplification is wrong. Keep those, and keep them short.

A comment loses its place when it narrates history: what used to be here, what was deleted, which
milestone deleted it, what a previous attempt did, why an earlier approach failed. **Deleted code gets no
tombstone.** Do not leave a comment naming removed options, types, or methods. The removal is in the diff,
the reasoning is in `docs/replayerRebuildStatus.md`, and the rule it followed is in the design — three
places a reader can already reach, none of which is the file the thing used to live in.

The test is whether the comment would still make sense to someone who does not know a rebuild happened.
"This must not inherit Netty's scheduler, which measures deadlines against `System.nanoTime()`" passes:
it prevents a wrong change. "Carried from the pre-rebuild implementation; G3 restores the rest" fails:
it is only meaningful as history, and it will be stale the moment G3 lands.

Two deliberate exceptions, both temporary and both self-deleting:

- **`REBUILD-LIMBO` region notes**, which exist to be read while the region is marked and are deleted with
  it. §8a governs those.
- **A workaround's removal milestone**, required by §2.1 — a flag or shim must name what deletes it,
  because otherwise it outlives its cause. That is forward-looking: it tells a reader what to do.

Explaining *why* code is shaped a certain way is not history, even when the reason is a past mistake —
write the reason as a present-tense constraint rather than as a story about the mistake.

## 8a. Limbo is the first place to look, not a graveyard

Carried-but-not-yet-refactored code stays **in place, at the path it will ship from**, marked with
`REBUILD-LIMBO-START(<milestone>)` / `REBUILD-LIMBO-END(<milestone>)` around a `/* */` region whose
delimiters sit on their own lines. The code inside is verbatim, so blame survives both the carry and the
eventual restore — un-marking is a pure deletion of the marker and delimiter lines, which never touches a
code line. The only lines that change are inner comment delimiters, escaped so the region cannot terminate
early.

**In place is not a cosmetic choice; it is what makes the rest of this section work.** Code you do not see
is code you reinvent. Inline, the existing implementation is unavoidable — you cannot open the file you are
about to add a method to without reading what is already there. Anything that separates carried code from
the place it will ship from reintroduces that gap, so nothing may.

A file therefore appears in a compiled source set whenever *any* part of it is wanted, with the rest marked
around it. There is **one** replayer module: everything lives at the path it will ship from, and nothing is
held anywhere else. Abandonment is a per-member decision that deletes the member, not a decision to file the
whole thing somewhere else.

**The unit of categorization is the member, not the file.** Fields, methods, and nested classes are
categorized individually; a file is merely where they live. Thinking in files is the error that produced
both of the mistakes this section exists to prevent — it forces an all-or-nothing verdict on a file that
almost always contains some of each kind.

A file is present in a compiled source set because **some part of its functionality belongs in the final
deliverable** — not because the file is "in final form," and not necessarily because any of its current
*code* survives. Its remaining members are marked in place. `TrafficReplayer.java` is the worked example:
the CLI surface is live because the owner decided that functionality ships, and the seven wiring functions
around it are marked, in the same file, at their final path.

**Everything starts marked. Being live requires a decision about that member**, recorded by the milestone
that makes it — never a side effect of happening to compile. The first walk got this backwards: it promoted
75 whole files because javac accepted them, which is the "cheapest correct move is to add" failure in a new
costume, since nothing had to justify being live.

As the rebuild proceeds every marked member resolves to exactly one of three: **dead** (deleted), **keep**
(promoted as-is), or **refactor** (rewritten into the component the design assigns it to). The marked set
shrinking monotonically toward zero is the progress measure. **This system is only worth having if it is
trusted, which means it has to be exhaustive**: a member that is neither live nor marked is a silent loss,
and one wrong verdict discovered late costs more than the whole walk saved.

**Before writing any new class, method, or test, look in limbo for the thing you are about to create.** If
it is there, restore and refactor it. Do not invent a parallel implementation beside it. Inventing loses the
original's history, duplicates coverage that already exists, and — worst — discards the evidence the
original carries about intended behavior. This rule was written because an agent rewrote a fixture,
reported four "defects" in it, and asked the owner to explain them, while the owner's own test for that
fixture sat in limbo unread. That test showed the four were self-inflicted by the rewrite. The cost of not
looking is not lost time; it is a confidently wrong report.

Because "remember to check" is a rule that never fires, it gets observable triggers instead:

1. **Creation trigger.** Before creating any file under `src/`, `grep -rl REBUILD-LIMBO src` for the
   simple name you are about to use, and grep the marked regions for the responsibility in words. A hit
   means restore-and-refactor is the default and creating something new is a decision to record.
2. **New-test trigger.** Before writing a test, grep limbo for tests of the same subject. A pre-existing
   test is the specification of the behavior you are about to assert on, and reading it comes first.
3. **Debugging trigger.** When new code fails and a limbo counterpart exists, read the counterpart before
   forming a theory. A passing predecessor is evidence; a theory formed without it is a guess.

**Javadoc stays outside the region; only implementation goes inside.** The shape is: javadoc, then
`REBUILD-LIMBO-START`, then the implementation, then `REBUILD-LIMBO-END`. Java does not nest block comments,
so a javadoc block inside a region would terminate it early — and escaping it to survive is both lossy to
reverse and destroys the blame on the most informative lines in the file, which are the ones stating what the
code was *for*. Keeping it outside needs no escaping at all. When a member is resolved to dead, delete its
javadoc with it.

The residue is non-javadoc block comments inside implementation, which are rare — five in this module. Each
is lifted out verbatim behind `REBUILD-LIMBO-ESCAPED-LINE`, a guard reversed by stripping exactly one known
prefix, so reconstruction stays mechanical. **Never invent an ad-hoc escape without a guard**: an unguarded
rewrite cannot be reversed reliably, because the rewritten form is indistinguishable from code that was
always written that way.

`TrafficCapture/trafficReplayer/tools/unmark-limbo.awk` reconstructs a marked file, and
`tools/verify-limbo-markers.sh` checks every marked file in the module: that each region's delimiters are
well-formed, so the marked code is inert rather than merely unused, and that reconstruction recovers every
code line, compared against the content the marking commit replaced. **Run it after any marking change.**
That check is the point — carried code is only safe to mark if getting it back is mechanical.

The recovery is exact on code and **not** byte-exact, which this file previously claimed. Marking pads each
region with a blank line inside its delimiters, and that padding is unguarded: the blank before a mid-file
`*/` is usually the blank that separated two members, so no rule can tell the marker's blank from the
original's, and stripping it would lose real content. The verifier therefore ignores blank lines, and
anything it does report is a genuinely lost, added, or altered line. This is the guard rule above failing in
miniature on the marker's own output — worth keeping visible rather than quietly restating the claim.

**When you promote a member whose consumer is still deferred, keep its signature and defer only what
cannot compile.** A parameter that no live caller reads yet costs nothing to thread through; deleting it
costs someone a rediscovery of which option fed it, and that is the road to reinventing proven code beside
the original instead of restoring it. Mark such a parameter's purpose in javadoc — including that it is
intentionally unused — so it does not get tidied away as dead. Only a type that genuinely cannot appear in
a compiled signature is dropped, and where it was dropped, the promoted member records the exact call that
restores it and where the missing argument comes from. **Restoration should be un-marking plus wiring one
argument, never re-deriving an argument list.**

**Mark live code that a later milestone must still change with
`REBUILD-LIMBO-NOTE(<milestone>)`.** A promotion often leaves a stand-in behind — a temporary root type, a
signature missing a parameter whose type is still marked, a field that moves once its real home compiles.
None of that is marked code, so none of it appears in the region count, and a reader has no way to find it
unless they already know which type name to grep for. A `NOTE` makes the set enumerable:
`grep -rn 'REBUILD-LIMBO-NOTE(G3)' src` is the list of places that milestone has to touch. Write what it
becomes, not how it got there. `tools/unmark-limbo.awk` drops these lines like any other scaffolding and the
verifier counts them as markers, so a stale one cannot survive un-marking.

**Mark members, never whole files as a unit.** An all-or-nothing verdict on a file hides the class, its
history, and its existing coverage, which is what makes the failure above possible. If two of five methods
are wanted, the file sits at its final path with the other three marked around them.

**Replace rather than bridge.** When new code replaces old code, remove the old production path as soon
as the new end-to-end path is viable. Do not put temporary legacy concepts into core owner state, public
contracts, or switches — the in-place attempt did this with `LegacySourceTeardown` and
`ReplayTransaction.RunwayLossReason`, and making a legacy concept part of the new core model only moves
deletion work into a later milestone. An adapter at an outer boundary is sometimes acceptable; a legacy
concept in the core model is not. **A known broken test with a documented repair milestone is preferable
to a compatibility layer that becomes part of the new production model.**

**Keep ownership explicit.** Mutable owner state belongs to its injected event loop. Avoid locks, global
coordination, and mutable static state.

**Separate admission from completion.** "Did the owner accept ownership of the request and its
resources?" and "how did the accepted request eventually finish?" are different questions and get
explicit typed boundaries. Do not make callers infer ownership transfer from an eventual result value or
from exceptional completion.

**Keep cancellation typed.** Cancellation is a normal lifecycle outcome, not an error-discovery protocol.
Do not recognise normal cancellation by inspecting `CancellationException`, and do not convert a typed
cancellation result back into a failed future at another edge. One cancellation vocabulary through
admission, preparation, target attempts, processing, and settlement. Reserve exceptional completion for
invariant failure, infrastructure failure, or something genuinely exceptional.

**Decompose coordinators before they become unreviewable.** The owner split is directionally right; the
in-place attempt concentrated too much in `TargetConnectionOwner` and too many interacting state machines
in `RequestReplayOwner`. Do not respond by reverting to a lock-based god object — keep the owner boundary
and extract small owner-confined components with narrow state and explicit inputs and outputs. Useful
boundaries include admission, ordered execution, request registry, turn milestones, processing
milestones, cancellation arbitration, timers, ordered close, and fatal-transition reporting, but the
actual split follows the designs rather than this list.

Warning signs, any of which is reportable. Note that none of them is a size threshold: a large class that
mirrors the design's state model and reads as one coherent state machine is not a problem.

- **Correctness depends on combinations across many independent state fields** — the state space is the
  real complexity measure, not length. A file with a dozen booleans whose interactions are interpreted
  informally is worse than a long file with explicit sealed states and exhaustive switches.
- **A responsibility lives somewhere the design does not assign it**, or one file holds responsibilities
  the design splits across owners (§1's placement trigger).
- The same file is repeatedly reworked because unrelated responsibilities meet there (§1's thrash
  trigger).
- A milestone meant to move a responsibility out of a coordinator left it there.

**Freeze the outer ownership direction** rather than redesigning it repeatedly, and do not expand a
milestone laterally. Separate owners and queues, a persistent request registry, explicit milestones, and
first-write locality are the right shape — adopt them without inheriting the existing implementation's
accumulated form as a compatibility obligation. Do not let a persistent registry become a reason for one
owner to coordinate every lifecycle concern.

**Evidence quality.** Large test files and many five-second waits are not automatically strong evidence.
Prefer compact deterministic transition histories over thousands of repetitive test lines.

## 9. Checklists

**Before changing code:** read this file; confirm which plan is active; read the design sections cited by
the next milestone's `Design refs:` line; inspect the branch, worktree, and any existing uncommitted user
changes and preserve unrelated ones; identify any compatibility choice or design ambiguity *before* it is
embedded in code.

**For each production milestone:** build the final-form production path and remove the path it replaces;
keep state instance-owned and event-loop confined; add observability and a few focused deterministic
tests; run the narrow compile and tests with `-x spotless`; record known-broken inherited tests with their
repair milestone; review until a pass returns no unfixed design-conformance defect (§3.1); commit with a
detailed message and the owner's DCO;
push and update the PR ledger.

**At a major integration boundary:** verify there is exactly one live correctness model; check that no
temporary legacy types leaked into core contracts; measure growth in the coordinators that were supposed
to shrink; run the broader confirmation suite; bring unresolved design and compatibility decisions to the
owner as one batch.

This file is the whole contract. Anything you were told elsewhere about how to work here is superseded by
it, and does not need to be reconciled with it.
