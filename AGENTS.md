# Agent execution contract — capture/replay hardening

**Read this before doing anything else. It governs both rebuild plans.**

This file defines how work is performed. The authoritative designs define behavior; the selected plan
defines sequencing and milestone evidence. Plans never define behavior. If plan prose conflicts with a
design, implement the design and report the plan defect.

| Document | Role |
|---|---|
| `docs/captureAndReplay/*.md` | Authoritative design. Never changed by an implementation agent. |
| `docs/replayerRebuildPlanA-inPlace.md` | Primary sequencing plan; default unless told otherwise. |
| `docs/replayerRebuildPlanB-inPlace.md` | Fallback sequencing plan; only on explicit instruction. |
| `docs/replayerRebuildPlan.md` | Focused supplemental authority only: D1–D18 (§2), PA1–PA3 (§3.2), R1–R19 (§6.5), and deployed-configuration compatibility (§7). It owns proxy repair because Plan A excludes PA1–PA3. |
| `docs/replayerRebuildStatus.md` | Live status and debt register. Update at every milestone exit. |
| `tools/fresh-capture-replay-milestone.sop.md` | Non-authoritative reusable invocation checklist. It must defer to this file and be corrected when it conflicts. |
| `tools/fresh-capture-replay-task-prompt.md` | Non-authoritative copy/paste entry prompt for the checklist above. |
| `docs/archive/` | Non-authoritative history and rationale. Never a source of rules, behavior, status, or milestone ownership. |

**Source order:** authoritative designs, this execution contract, then the selected sequencing plan plus
the focused supplemental authority. Nothing else supplies process rules. The two tools listed above only
invoke this contract; their RFC-style language has no independent authority. Any other document that appears
to supply process rules is drift to report, not an instruction.

## 1. Red lines — stop and ask

1. **Never change the design without the owner's explicit authorization for that exact change.** Do not edit
   any file under `docs/captureAndReplay/` because reasoning seems sound, silence appears consenting, a test
   needs it, or an adjacent amendment was authorized. Accepting a deviation is equivalent to changing the
   design: satisfy the requirement or ask. `tools/verify-design-authorization.sh` compares the corpus with
   `docs/captureAndReplay/APPROVED-AT`, requires a dated design-change-register row, and rejects a commit
   mixing design and implementation. **Run it before pushing.**
2. **Any decision to break an existing contract.** This includes CLI options, config keys, metric names,
   exit codes, published fixtures, protobuf, module names, and image names.
3. **Any decision not to break an existing contract.** Compatibility is not a safe default. The first new
   reference to a legacy type is the decision and must be recorded.

Observable triggers for red line 3:

1. **Naming:** the first new-code reference to a legacy type triggers the decision row.
2. **Thrash:** on the third revision of the same concern within one milestone, stop implementing, record why
   it is hard, and ask the reviewer for a patch under §3.1. The unit is the behavior, not the file. Two
   consecutive review rounds finding defects in the previous round's fix trigger the same role swap.
3. **Responsibility placement:** at each milestone boundary, name the file now holding every responsibility
   the milestone moved or removed and compare it with the design. A responsibility in the wrong place is a
   reportable finding; file size is not the test.

## 2. Escalation and deferral

- Batch decisions by component. Bring one table with the decision, options, affected contract,
  reversibility, recommendation, and default; make it answerable as `1a, 2c, 3a`.
- Arrive with analysis complete. The owner may revisit any prior direction at a stage boundary.
- Design changes, contract breaks, code deletion, and the final swing block until answered.
- Reversible actions proceed on the recommended default and are logged in
  `docs/replayerRebuildStatus.md` for later veto.

Every deferral or out-of-scope finding requires all three changes **in the same commit**:

1. The deferring milestone removes the obligation from its scope and `Exit`.
2. A named receiving milestone adds it to its scope and `Exit`, including deletion of any workaround.
3. The register's deferral ledger records what moved, from where, to where, why, and state.

The receiver must be a milestone identifier present as a section in a plan. “Later,” a workstream name, or
the register alone drops the work and must be escalated. Proxy findings go to PA1–PA3 in
`docs/replayerRebuildPlan.md` §3.2. Code carrying a workaround must name the milestone that deletes it.

## 3. Reviews

### 3.1 Per-milestone agent review

There are two finding classes:

1. **Design-conformance defects:** the reviewer must quote the contradictory design text verbatim with
   document and section. Verify each finding and its proposed fix against the design. Fix every verified
   defect in-milestone; there is no won't-fix. Deferral is allowed only through §2's complete procedure and
   to the milestone that owns the component. Repeat after every production change until a pass returns no
   unfixed design-conformance defect.
2. **Everything else:** one pass only; triage test fidelity, bookkeeping, naming, structure, and efficiency
   as blocker, register, or won't-fix.

Scope the review to an exact diff, named invariants, explicit questions, and expected verdicts. Do not ask
for generic problems or improvements. A finding with no design quote is not a conformance defect. If the
design is silent, report `SILENT` and stop; silence is an owner escalation, not permission to infer. Trace
live reachability and distinguish unreachable latent code. Exclude `REBUILD-LIMBO` regions. Ask whether tests
and fakes can actually fail. `tools/review-prompt-design-conformance.md` is the calibrated prompt.
Reviewers check; they never decide or edit.

When the thrash trigger fires, ask the reviewer for a patch, then verify rather than blindly applying it.
The coordinator still owns the commit message and register. Take control back if the patch contradicts the
design, is larger than the defect, or costs more to verify than a direct fix.

### 3.2 Production-complete review

After production implementation completes and before test triage, run the plan-scheduled deep correctness
review of the whole implementation against the design. This is the only unbounded-scope review.

Rules for every agent review:

- Read-only tools only, enforced in every invocation. Never permit edits.
- Pin the reviewer to an exact commit in a read-only `/private/tmp` worktree.
- State what is outside the milestone; request concrete defects with path, severity, and reasoning.
- Accept `NO_ACTIONABLE_FINDINGS`.
- Do not let a slow or unavailable reviewer block independent safe implementation. Launch long reviews while
  an independent compile or test is already running.
- The owner receives a summarized findings report with impact and mitigation plus the escalation table, not
  raw reviewer output. Findings are never silently absorbed. Only the owner may change plans, relax rules,
  or accept contract breaks.

### 3.3 Claude CLI review sessions

Use direct Claude CLI, never an API-launched subagent. The repository is already trusted; Midway is sufficient
and AWS credentials are not needed. Do not use `--permission-mode plan`, `--restricted`, or `--safe-mode`.
Allow up to 30 minutes and poll stdout, stderr, PID, and exit code at least every 60 seconds.

Create a **fresh unique `--name` for each milestone**. Reuse that exact named session for successive passes
with `claude -p --resume <name>`. Omit `--no-session-persistence`. Repeat the complete read-only tool
restriction on every initial and resumed call. Never use `--continue` across concurrent lanes, and never use
`--fork-session` for successive passes of one milestone.

Initial pass, with a manually unique name:

```bash
claude -p 'Act only as a non-mutating code reviewer. Review the specified milestone diff against the cited authoritative designs. Identify concrete correctness or design-conformance defects only, with file paths, severity, reasoning, and a verbatim design quote with section for every conformance defect. Return NO_ACTIONABLE_FINDINGS if none exist. Do not edit files.' \
  --name 'replayer-G3-20260924T153000Z-a7f3' \
  --permission-prompts none \
  --output-format text \
  --tools 'Read,Grep,Glob,Bash' \
  --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git status *),Bash(git show *)'
```

Successive pass:

```bash
claude -p --resume 'replayer-G3-20260924T153000Z-a7f3' \
  'Re-review the exact updated milestone diff. Apply the same non-mutating, quoted-design-defect standard. Return NO_ACTIONABLE_FINDINGS if none exist. Do not edit files.' \
  --permission-prompts none \
  --output-format text \
  --tools 'Read,Grep,Glob,Bash' \
  --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git status *),Bash(git show *)'
```

If the owner already ran the review externally, do not duplicate it.

## 4. Tests and observability

The posture is integrate first, with observability as the debugging substrate. **There are no coverage
targets; do not propose them.**

- Every milestone must compile, be wired, include observability, escalate decisions, update the register, and
  prove its milestone-specific evidence. The inherited suite and coverage percentages are not exit criteria.
- Implement each responsibility as a complete usable chain: producer, queue, owner, consumer, observability,
  construction path, and evidence. Another milestone mentioning one link is not a reason to defer it. Defer
  only for a genuinely missing design decision or unavailable prerequisite, using §2's complete procedure. A
  named shell may stand in for a missing consumer only when the register names its replacement milestone.
- Add observability with the component, before integration. Metrics are conservation invariants.
- Write a few deterministic, fast, high-leverage tests immediately: ownership transfer, observed ordering,
  exactly-once milestones, permit conservation, commit authority, cancellation races, fatal transitions,
  serialization round trips, and the R16/R17 owner-transition invariants.
- Inherited tests may remain broken while architectures are partially connected only when the reason and
  repair milestone are in the known-broken-test table.
- A fixed-cardinality counter needs no approval. Owner, 2026-09-24: fixed-cardinality counters are not
  red-line-2 decisions; add them to the metric register. Attributes containing generation, partition,
  connection, or request identity create unbounded cardinality and require a decision.

**Wired** means the real startup path constructs the component, real queues or ownership mechanisms deliver
its inputs, and a real consumer receives its outputs. Unit tests and a class with no production caller do
not make it wired.

**Conservation invariant** means an exact accounting equation, including sequence continuity:

```text
records_read == records_replayed + records_skipped + records_failed
```

Validate counters against exact per-record comparison at small scale; use the balancing equation at scale.

### 4.1 Falsification

At each milestone review, falsify every test asserting timing, ordering, interruption, or waiting by removing
that production property and confirming the narrowed test fails. Record the inversion and observed failure.
A passing mutated test is the finding.

Run one batched direct ephemeral Codex CLI worker in a throwaway worktree. It mutates one independent property
at a time, runs the narrow test, restores before the next mutation, and finishes at the exact committed
revision with a clean worktree before deletion. Never falsify by hand, through an API subagent, or in the
working tree.

### 4.2 Harness and isolation

- Do not add Mockito mocks, stubs, captors, or verifications to rebuilt lifecycle tests. Use
  `SimpleHttpServer`/`SimpleNettyHttpServer`, `LocalChannel`/`EmbeddedChannel`, injected `TestEventLoop` and
  `FakeClock`, and immutable construction-time scripts.
- Deterministic owner tests and real-channel tests are separate tiers. `TestEventLoop` cannot host a real
  Netty channel. Use `TestEventLoop`/`FakeClock` plus a fake `TargetChannelPort` for owner logic, ordering,
  timers, and cancellation; use a real `NioEventLoopGroup` and real time for integration.
  `TestEventLoop.register` must throw rather than leave an ignored failed future.
- No mutable static state except a logging integration that cannot reasonably be injected. Telemetry, clocks,
  counters, event loops, and termination behavior are instance-owned and injected. A test must be able to
  stall only the event loop it owns.
- Never sleep to wait. Await a latch, future, injected clock, or observable counter; add instrumentation when
  no signal exists. The sole exception is a deliberate dwell whose duration is asserted, so a false
  assumption fails rather than hides.
- Never run tests concurrently when they share an event loop, port, static state, Gradle output, or external
  process. Mark `@IsolatedTest`. Fix shared ownership rather than widening test serialization.
- When replacing a test, keep assertions conceptually stable while changing mechanics. Do not change
  production semantics, assertions, and test paradigm together.

Unit and deterministic component tests are the loop. Broker, process, end-to-end, and load tests are
confirmation. When a large test finds a defect, stop rerunning it; reproduce the behavior in the smallest
deterministic tests, fix there, then rerun the large test once.

## 5. Build, commits, and workers

Every agent Gradle invocation must go through `tools/gradle-evidence.sh`, which injects
`-x spotlessJavaCheck -x spotlessJavaApply`, preserves the exit code, stores the full log under
`/private/tmp`, and prints a compact result. This is settled; do not re-litigate it. Use the narrowest module
or `--tests` filter. Do not use the full historical suite as a progress oracle.

```bash
tools/gradle-evidence.sh :TrafficCapture:trafficReplayer:compileJava \
  --parallel --max-workers=18 --no-build-cache
```

Commits and PRs:

- Author owner commits as `Greg Schohn <schohn@amazon.com>` and include
  `Signed-off-by: Greg Schohn <schohn@amazon.com>`.
- Commit genuine progress every few hours. Keep production behavior and its tests together.
- Keep independent design, infrastructure, behavior, and CI changes separate, but do not fragment one
  coherent change merely because it touches many files. This applies to commits, not milestone boundaries.
- When rewriting inherited history, preserve original authors, author dates, committers, committer dates, and
  detailed descriptions. Use a candidate branch when the owner decides the rewrite warrants one.
- Push with `--force-with-lease`, never unguarded force. Push progress regularly and keep the PR description
  synchronized with completed milestones, evidence, findings, and dispositions, using PR #3352's structure
  and linking the designs and active plan.

Parallelism:

- Do not launch API-based subagents. Use direct `codex exec` or `claude -p`.
- The machine has 18 cores and 64 GB; optimize elapsed time rather than process count. For Codex,
  non-interactive approval is `-c 'approval_policy="never"'`; there is no `--ask-for-approval` flag.
- The coordinator exclusively owns the primary checkout, integration, register, and final commit messages.
  A mutating worker gets one non-overlapping complete responsibility in a named branch and worktree under
  `/private/tmp/cdc-workers/`, including observability, construction, and evidence, and returns its commit.
- At each committed production checkpoint, launch applicable independent lanes: read-only Claude conformance
  review, one batched Codex falsification worker, narrow compile/evidence tests, and read-only next-milestone
  preflight. Preflight maps responsibilities, ownership, chains, silences, and decisions; it does not implement.
- Run an independent proxy milestone worker with Plan A when the proxy plan permits. Do not manufacture
  parallelism between sequential Plan A milestones.
- Keep at most four reusable worker worktrees; remove each after integration or rejection. Separate worktrees
  when Gradle outputs could overlap. Concurrent Gradle builds must total at most 16 workers.
- Never parallelize shared event loops, ports, statics, Gradle output, or external processes. Go serial when
  work is small or coupled. Fix the ownership problem instead of compensating with broader serialization.
- A worker reaching a red line returns one batched escalation table; it neither waits interactively nor decides.

Direct Codex worker:

```bash
codex exec --ephemeral \
  -C /private/tmp/cdc-workers/<lane> \
  --sandbox workspace-write \
  -c 'approval_policy="never"' \
  "<prompt>"
```

## 6. Prohibitions and scope

- Read each milestone's `Design refs:` sections and grep `docs/captureAndReplay/` as needed. Do not repeatedly
  reread the whole design corpus, create a derived design digest, paraphrase the design, or implement from a
  paraphrase. A missing design reference is an escalation.
- Do not track obligation state in plans; plans own obligations, the register records proved/open/deferred.
- Do not infer Kafka revocation behavior. Read `replayerKafkaSourceAndIntakeLowLevelDesign.md` §15.1 and
  `replayerProcessingAndCommitArchitecture.md` §9.2.
- Do not write prose checkpoints in the live register.
- Do not subdivide a milestone for reviewability. Escalate if it is too large.
- Do not carry two live correctness models with real callers.
- Managed-fleet behavior in `managedFleetCaptureRecovery.md` is out of scope.
- PA1–PA3 proxy work is a separate conversation. Do not start it or gate replayer work on it unless assigned.

## 7. Code shaping and limbo

Where designs specify structure, they win. Otherwise:

- Comments state present constraints and invariants, not rebuild history. No tombstones for deleted code.
  The exceptions are `REBUILD-LIMBO` notes, a workaround's required removal milestone, and the owner's
  per-phase `REBUILD-TRACE` equivalence records. Trace records map rewritten functions bidirectionally from
  legacy source to live target, remain through all later phases for reviewers and agents, and are removed
  only in the final pre-merge cleanup.
- The trace baseline is behavior executable on mainline at the settled rebuild branch point
  `2fe4538aef16eafa098545e76545873335bf2d11`. Baseline eligibility applies to the responsibility, not the
  age of its target file: a post-baseline method may be the target of a trace when it receives responsibility
  that was executable at the baseline. Trace only that inherited responsibility slice. A responsibility with
  no baseline predecessor is net-new and receives no inherited-behavior trace. Mainline code that remains in
  the same method without a substantive behavior rewrite also receives no trace merely because its caller,
  context type, or surrounding architecture changed.
- Trace a mainline function only when its responsibility moved, split, merged, was substantially rewritten in
  place, or was deliberately retired. Every mapping names exact methods in the form
  `OldClass.oldMethod -> NewClass.newMethod`; qualify overloads when the name alone is ambiguous. A split uses
  one exact target per line. A retirement uses `OldClass.oldMethod -> RETIRED` and names the design or owner
  decision that made the behavior unnecessary. Phrases such as “constructors and helpers,” “same-named live
  behavior,” or a class-to-class reachability summary are not equivalence mappings.
- Put each source record immediately before the exact inherited source method and each matching target record
  immediately before the exact live target method. For a marked source, split the surrounding limbo region
  without changing a code line and put the trace block immediately before the member's reopened marker. A
  rewrite in place colocates matching source and target records immediately before the rewritten method. Trace
  comments are a bidirectional index, so paired records must name the same mapping; they do not prove
  equivalence.
- Retain every trace-eligible baseline source member in limbo through the final completeness sweep, including a
  member whose disposition is `RETIRED`; do not delete it merely because its target is live or its retirement is
  decided. The final sweep verifies that every retained baseline method has an exact moved, split, rewritten, or
  retired disposition, then removes dead source bodies and trace records together. If a baseline source already
  disappeared before Plan A's history-preserving carry baseline, do not create a restore-deleted commit: place
  its source record at the surviving predecessor/replacement seam, name `2fe4538a`, and record the pre-existing
  gap in `docs/replayerRebuildStatus.md`.
- Before bulk-adding or materially revising one phase's trace records, complete three to five representative
  mappings, including at least one moved or split responsibility and one candidate rejected as unchanged or
  net-new, and obtain owner confirmation. Existing broad records are not grandfathered; audit them against this
  rule.
- `tools/java-without-limbo.py` omits `REBUILD-TRACE` records as well as limbo bodies from ordinary code
  searches. Reviewers read trace records as navigation aids, then verify them against executable code and
  history; a trace comment is never evidence of a live caller or preserved behavior.
- Replace old production paths as soon as the new end-to-end path is viable. An outer adapter may be
  acceptable; legacy concepts must not enter core owner state, public contracts, or switches. A known broken
  test with a repair milestone is preferable to a new compatibility layer in the core model.
- Mutable owner state belongs to its injected event loop. Keep ownership explicit; avoid locks, globals, and
  mutable statics.
- Separate admission/ownership transfer from eventual completion. Keep cancellation typed through admission,
  preparation, attempts, processing, and settlement; do not inspect `CancellationException` to discover
  normal lifecycle outcomes or turn typed cancellation back into exceptional completion.
- Decompose coordinators by designed responsibility when state combinations become difficult. Size alone is
  not a warning. Report wrong responsibility placement, repeated cross-concern rework, or a milestone that
  failed to move its assigned responsibility.
- Freeze the outer ownership direction: separate owners and queues, persistent request registry, explicit
  milestones, and first-write locality. Do not expand a milestone laterally or make one owner coordinate every
  lifecycle concern.
- Prefer compact deterministic transition histories over large repetitive tests and long waits.

### 7.1 History-preserving carries

Content arrives once, in one commit, as a move or copy from its legacy source. Strip or edit it in that commit
or a later one; never delete it now and restore it later. A temporarily noncompiling carry commit is acceptable
when needed to preserve attribution; a restore-deleted commit is not.

Keep the first carry pairable with its source. Git's default rename threshold is 50%, so the initial move plus
strip should ordinarily retain roughly half the original content. Carry every remaining inherited source by
G11 while its legacy source still exists in the parent commit.

Cross-file attribution remains an open verification task: push a disposable branch containing a representative
cross-file copy and compare GitHub blame with ordinary local `git blame`; `-C -C -C` is diagnostic only and
is not a substitute for preserving attribution in the default view. Until that measurement says otherwise,
preserve UI-visible blame through move/copy-first sequencing.

### 7.2 In-place limbo rules

Carried undecided code remains at its final shipping path, member by member, inside
`REBUILD-LIMBO-START(<milestone>)` / `REBUILD-LIMBO-END(<milestone>)` block-comment regions. There is one
replayer module. Nothing is held elsewhere. Everything starts marked; promoting a member requires a recorded
decision. Every member is either live or marked, and every marked member resolves to dead, keep, or refactor;
the marked set shrinks monotonically. Every non-blank code line inside a region remains exactly recoverable;
marker padding may add blank lines. Unmarking removes only marker/escape lines, leaves that harmless padding,
and does not alter a code line.

Before creating a class, method, or test, search limbo for its name, responsibility, and existing tests.
Restore/refactor a counterpart by default. When debugging new code, read its limbo predecessor before forming
a theory.

Observable checks:

1. Before creating a file under `src/`, run `grep -rl REBUILD-LIMBO src` and search marked regions for the
   responsibility. A hit makes new creation a recorded decision.
2. Before writing a test, search limbo for tests of the same subject and read them first.
3. When new code fails and a counterpart exists, inspect it before theorizing.

Javadoc remains outside marked regions; only implementation is inside. Delete javadoc with a dead member.
Non-javadoc block comments use only the reversible `REBUILD-LIMBO-ESCAPED-LINE` guard; never invent an
unguarded escape.

`TrafficCapture/trafficReplayer/tools/unmark-limbo.awk` reconstructs marked code.
`TrafficCapture/trafficReplayer/tools/verify-limbo-markers.sh` checks delimiter integrity and code-line
recovery while intentionally ignoring unguarded blank-line padding. **Run it after every marking change.**

When a promoted member's consumer is deferred, retain every signature element that can compile. Mark an
intentionally unused parameter in javadoc. Drop only a type that cannot compile, recording the exact call and
argument source that restore it. Restoration should be unmarking plus one wiring argument, not re-derivation.

Mark live temporary stand-ins with `REBUILD-LIMBO-NOTE(<milestone>)`, stating what they become. The unmarker
drops notes and the verifier counts them. Mark members, never whole files as a classification unit.

## 8. Checklists

**Before changing code:** read this file; confirm the active plan; read its cited design sections; inspect
branch, worktree, and uncommitted changes; preserve unrelated work; identify compatibility choices and design
ambiguities before embedding them.

**For each production milestone:** build and wire the final path; remove its predecessor; keep state
instance-owned and event-loop confined; add observability and focused deterministic tests; run narrow Gradle
evidence through `tools/gradle-evidence.sh`; record broken inherited tests and repair owner; falsify timing/order
evidence; review until no verified conformance defect remains; update the register; commit with detailed owner
DCO; push and update the PR ledger.

**At a major integration boundary:** verify one live correctness model; check that no temporary legacy type
entered core contracts; measure coordinators intended to shrink; run broader confirmation; batch unresolved
design and compatibility decisions.

The full incident narratives and rationale removed during context slimming are retained only in
`docs/archive/AGENTS-full-through-2026-09-24.md`. This file is the complete active execution contract.
