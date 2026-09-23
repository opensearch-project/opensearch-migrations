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
| `docs/replayerRebuildPlan.md` | Superseded for sequencing. Still authoritative for exactly three things: the D1–D18 defect inventory (§2), the R1–R19 obligation set (§6.5), and deployed-configuration compatibility (§7). |
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
2. **Thrash trigger.** The **third** time you edit the same file within one milestone, stop and write
   down what is making it hard. Repeatedly reworking one file is the observable signature of shoehorning
   new behavior into a shape that does not want it.
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

## 3. Reviews

Three different things are called "review" and they have different rules.

### 3.1 Agent review — per milestone

Codex or a subagent reviewing a diff. This is what the bullets below govern.

- **No review on sub-slices.** One bounded review per milestone.
- **Bounded means bounded.** One pass. Findings are triaged into blocker / register / won't-fix. Only
  blockers are fixed in-milestone. There is no confirmation round and no "review until no findings
  remain" — an agent asked to find problems will always find some, so that loop cannot terminate.
- **Scope it precisely.** Give the reviewer a specific diff, the named invariants it must not violate,
  and specific questions with verdicts expected. Do not ask it to "find problems" or "suggest
  improvements" — an open-ended prompt returns style notes you then have to read and discard.
- Reviewers are for **checking**, not deciding. A reviewer's findings are input to an escalation, never
  authority to change course.

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
- **No mutable static state**, with a single possible exception for a logging integration that cannot
  reasonably be injected. Telemetry, clocks, coordination counters, event loops, and termination
  behavior are instance-owned and injected. A test must be able to stall only the event loop it owns.
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

## 8a. Limbo is the first place to look, not a graveyard

Carried-but-not-yet-refactored code stays **in place, at the path it will ship from**, marked with
`REBUILD-LIMBO-OPEN(<milestone>)` / `REBUILD-LIMBO-CLOSED(<milestone>)` around a `/* */` region whose
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

`TrafficCapture/trafficReplayer/tools/unmark-limbo.awk` reconstructs a marked file, and every marked file in
the module is verified to round-trip byte-identically through it. That check is the point — carried code is
only safe to mark if getting it back is mechanical.

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
repair milestone; perform the one bounded review; commit with a detailed message and the owner's DCO;
push and update the PR ledger.

**At a major integration boundary:** verify there is exactly one live correctness model; check that no
temporary legacy types leaked into core contracts; measure growth in the coordinators that were supposed
to shrink; run the broader confirmation suite; bring unresolved design and compatibility decisions to the
owner as one batch.

This file is the whole contract. Anything you were told elsewhere about how to work here is superseded by
it, and does not need to be reconciled with it.
