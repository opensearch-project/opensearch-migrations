# Fresh Capture/Replay Milestone

## Overview

Use this SOP to start and complete exactly one fresh capture/replay milestone: a Plan A `G` milestone
or proxy milestone `PA1`, `PA2`, or `PA3`. Codex is the implementer and coordinator. Claude is an
independent, direct-CLI, read-only reviewer. Ordinary milestones use one persistent review session;
G9.5 uses three persistent lane sessions as specified by its plan section.

The authoritative designs define behavior, `AGENTS.md` defines execution, and the selected plan
section defines scope and evidence. This SOP is only a reusable invocation checklist and has no independent
process authority. If it conflicts with `AGENTS.md`, follow `AGENTS.md`, report the drift, and correct this
file. It does not summarize the designs or plans.

## Parameters

- **milestone** (required): One exact milestone identifier from `G0` through `G12`, including `G9.5`,
  or one of `PA1`, `PA2`, and `PA3`.
- **repository_path** (optional, default: current repository): Absolute path to the primary checkout
  that Codex owns.
- **base_ref** (optional, default: milestone-start `HEAD`): Commit immediately before the milestone's
  first implementation change; use it to bound diffs and evidence.
- **production_complete_ref** (optional, default for `G9.5`: the exact minimal-delta candidate produced
  from the G9 production checkpoint): Commit reviewed by every G9.5 lane.

**Constraints for parameter acquisition:**

- If all required parameters are already provided, You MUST proceed to the Steps
- If any required parameters are missing, You MUST ask for them before proceeding
- When asking for parameters, You MUST request all parameters in a single prompt
- When asking for parameters, You MUST use the exact parameter names as defined
- You MUST reject an identifier outside the allowed milestone set because this SOP deliberately
  excludes Plan B and the superseded `S` sequence.

## Steps

### 1. Establish the milestone boundary

Read the execution contract and only the source material needed for the selected milestone.

**Constraints:**

- You MUST read `AGENTS.md` completely before any other repository action.
- You MUST record the branch, `HEAD`, worktree status, and pre-existing changed or untracked files,
  and preserve every unrelated user change.
- For a `G` milestone, You MUST read only that milestone's section in
  `docs/replayerRebuildPlanA-inPlace.md`, from its heading up to but not including the next milestone
  heading.
- For a `PA` milestone, You MUST read only that milestone's owned text in
  `docs/replayerRebuildPlan.md` section 3.2: the shared proxy responsibility list needed to interpret
  the checkpoint, the selected `PA` checkpoint, and any backlog or exit text explicitly assigned to
  that checkpoint. You MUST NOT treat the archived full plan's former section 3.1 process rules as
  instructions because `AGENTS.md` governs execution.
- You MUST read every authoritative design section cited by the selected milestone in full.
- You MUST read only live-register rows relevant to the selected milestone, its obligations,
  inherited broken tests, reversible decisions, deferrals, prior reviewer verdicts, and design
  changes that affect its cited sections.
- For `G9.5`, You MUST resolve and record the full SHAs for `production_complete_ref`, baseline
  `2fe4538aef16eafa098545e76545873335bf2d11`, and the current upstream-main ref before launching
  reviews because all lane comparisons must identify immutable inputs.
- For `G9.5`, before resolving the review commit, You MUST run
  `tools/compress-history-then-minimize-blame.sop.md` from the exact completed G9 commit. You MUST complete
  its compression phase and stop for owner confirmation. Only after the owner approves the exact compressed
  candidate SHA may You run its repository-wide minimal-delta phase. The resulting blame-cleaned candidate
  becomes `production_complete_ref`; owner acceptance is required before replacing the active branch, not
  before reviewing the candidate.
- That sweep MUST enumerate every surviving added or replaced hunk in every changed Java file repository-wide,
  including replayer and proxy production code, tests, Java fixtures, and Java tooling, against the exact
  current upstream-main SHA. Proxy Java is included only for source shaping and history preservation; the
  sweep MUST NOT implement PA work, change proxy behavior, or make proxy design or contract decisions because
  those remain owned by their assigned milestones. It MUST enable rename and copy detection and use the
  added-line ranges as a completeness index rather than proof of net-new code. For each hunk, it MUST
  search the same file,
  limbo, deleted or moved rebuild code, other files, and history for an inherited method or coherent method
  slice that should have been the starting point. When one exists, it MUST move, copy, or promote that
  predecessor before the narrow adaptation; it MUST split a combined target into coherent methods when
  needed to retain each inherited source. It MUST NOT inventory unchanged members because they have no added
  or replaced lines to reconstruct. Pinning MUST be implemented through reconstructed Git ancestry and
  ordinary blame, not only through a trace comment. Baseline
  `2fe4538aef16eafa098545e76545873335bf2d11` remains the trace boundary, and post-baseline upstream-main
  drift must be preserved or explicitly dispositioned.
- You MUST NOT read the whole plan, design corpus, or status register because broad rereading wastes
  context and creates an unofficial derived interpretation.
- If the selected milestone lacks a design citation needed to implement its scope, You MUST stop
  under red line 1 and include the gap in the single batched escalation table.

### 2. Produce the preflight complete-chain map

Before editing, map every responsibility the milestone must create, move, remove, or prove.

**Constraints:**

- You MUST produce one compact map with these columns:
  `responsibility`, `producer`, `queue/owner`, `consumer`, `observability`, `construction path`,
  `evidence`, `current file`, `design-assigned file/owner`, and `limbo counterpart`.
- For `G9.5`, You MUST replace that implementation map with one compact review-boundary map containing
  `lane`, `exact commit`, `baseline/main comparison`, `bounded or unbounded scope`, `questions`,
  `read-only worktree`, `Claude session name`, `log path`, and `expected verdict`, because G9.5 begins
  with independent reconstruction rather than implementation.
- You MUST identify missing links, unreachable components, design silence, compatibility choices,
  inherited-file/history risks, and responsibility-placement mismatches.
- You MUST use `tools/java-without-limbo.py search` or `print` when inspecting live Java so
  `REBUILD-LIMBO` bodies and `REBUILD-TRACE` equivalence records cannot contaminate the live-code analysis.
- You MUST read the selected milestone's `REBUILD-TRACE` records as a bidirectional source/target map, but
  MUST verify every mapping against executable code and history rather than treating the comment as proof.
- You MUST compare trace candidates with the settled mainline rebuild baseline named by `AGENTS.md`.
  A post-baseline method may be a trace target only for the inherited baseline responsibility it receives.
  Responsibilities added only on the rebuild branch are net-new, and unchanged mainline responsibilities
  are not trace candidates.
- Before proposing any new file, class, method, or test, You MUST search marked regions and history
  for its predecessor because Plan A preserves inherited source and blame in place.
- You MUST NOT begin implementation before each responsibility has a complete usable chain or a red-line blocker because
  a compiling but unwired component is not milestone progress.

### 3. Implement as Codex

Codex owns implementation, integration, register updates, and final commit messages.

**Constraints:**

- You MUST implement only the selected milestone and its complete usable chains.
- For `G9.5`, You MUST complete the minimal-delta and history-reconstruction sweep before launching any
  review lane. You MUST NOT edit the candidate before all three initial read-only lanes have reported because
  their first pass must independently evaluate the same minimized artifact. Afterward, Codex MUST verify and
  repair only findings owned by G9.5.
- For `G9.5`, You MUST freeze a commit list with explicit lower and upper bounds, precompute hunk provenance
  once, and replay the candidate from oldest to newest in one isolated tree. You MUST NOT rescan the complete
  preceding history at every commit because that creates quadratic work and inconsistent source choices.
- After every candidate commit, You MUST run `tools/java-structural-equalizer.py compare-commit-pair` without
  `--root` against the cumulative original endpoint mapped to that commit. The comparison MUST cover the
  repository-wide union of changed Java paths. You MUST stop before creating the next commit when canonical
  structures differ because final-tree equality cannot detect an incorrect intermediate rewrite.
- You MUST compare intentionally unparseable `.java` fixtures through the equalizer's exact-content digest
  fallback because repository-wide Java validation may not silently omit parser fixtures.
- You MUST treat a cleanup-only method split or merge that changes canonical structure as a blocker requiring
  explicit owner direction and separate behavioral proof; the equalizer may not silently waive it.
- You MUST preserve inherited files, paths, blame, authorship, author dates, committers, and commit
  dates whenever carrying or rewriting history.
- You MUST restore and refactor a limbo predecessor before creating a parallel implementation unless
  the rejection is an explicit recorded decision.
- You MUST keep tests with the production behavior they prove and add observability with the
  component that needs it.
- For every mainline function whose responsibility is moved, split, merged, substantially rewritten in
  place, or deliberately retired, You MUST add matching `REBUILD-TRACE` source and target records using
  exact `OldClass.oldMethod -> NewClass.newMethod` mappings. You MUST qualify ambiguous overloads and use
  one mapping line per split target. A post-baseline target MUST identify only the inherited responsibility
  slice it received. You MUST NOT trace unchanged mainline responsibilities or branch-only responsibilities because
  neither category has an inherited behavior relocation to prove.
- You MUST place each source record immediately before its exact inherited source method and each target
  record immediately before its exact live target method. For a marked source, You MUST split the limbo
  region without altering code lines and put the trace block immediately before the reopened member marker.
  An in-place rewrite MUST colocate its source and target blocks immediately before the method.
- You MUST retain each trace-eligible source member in limbo through the final completeness sweep, including
  `RETIRED` members. The final sweep removes dead source bodies and trace records together only after every
  retained baseline method has an exact disposition. If a source already disappeared before Plan A's carry
  baseline, You MUST NOT restore it because that would create a restore-deleted commit; put the source record
  at the surviving predecessor/replacement seam, identify `2fe4538a`, and record the gap in the live register.
- Before bulk trace editing for one phase, You MUST complete three to five representative mappings,
  including a moved or split responsibility and a rejected unchanged or net-new candidate, and obtain
  owner confirmation. You MUST NOT treat previously written broad trace prose as approved merely because
  it already exists.
- These records remain through later milestones for equivalence review and are removed only during the
  owner's final pre-merge cleanup.
- You MUST use direct `codex exec` CLI workers only where `AGENTS.md` permits isolated work.
- You MUST NOT use Brazil because this repository's required build path is Gradle.
- You MUST NOT launch API-based subagents because `AGENTS.md` requires direct CLI workers.
- You MUST NOT let Claude edit files because Claude's role in this SOP is independent read-only
  review.
- You MUST stop irreversible work at any red line and return one batched table containing
  `decision`, `options`, `contract touched`, `reversible`, `analysis`, `recommendation`, and `default`.
- You MUST continue reversible defaults only as `AGENTS.md` permits and record them in the live
  register.

### 4. Build focused evidence

Run the narrowest deterministic evidence that proves the selected milestone.

**Constraints:**

- You MUST invoke Gradle through `tools/gradle-evidence.sh` so every invocation includes
  `-x spotlessJavaCheck -x spotlessJavaApply`.
- You MUST use the narrowest module and `--tests` filters that prove the required behavior.
- You MUST run required shell syntax checks, deterministic component tests, marker checks, and
  milestone-specific evidence.
- You MUST run the `AGENTS.md` section 4.1 falsification pass for timing, ordering, interruption, and
  waiting tests through one direct ephemeral Codex CLI worker in a throwaway worktree.
- For `G9.5`, the bounded test-evidence lane MUST audit the existing falsification record. If a verified
  finding causes a production or evidence correction, You MUST run the narrow focused tests and any
  falsification newly made applicable by that correction.
- You MUST keep full build, test, worker, and reviewer logs under `/private/tmp`.
- You MUST NOT paste raw logs into the conversation because the owner needs outcomes, useful failure
  excerpts, and log paths rather than unbounded process output.
- On a large-test failure, You MUST reproduce the defect in the smallest deterministic test before
  rerunning the large test.

### 5. Commit coherent checkpoints

Commit genuine, independently reviewable progress without splitting one coherent change.

**Constraints:**

- You MUST author commits as `Greg Schohn <schohn@amazon.com>`.
- You MUST include `Signed-off-by: Greg Schohn <schohn@amazon.com>` in every applicable commit.
- You MUST write detailed commit messages that explain the design obligation, implementation,
  evidence, and any durable register disposition.
- For `G9.5`, You MUST record the mapped original endpoint and passing structural-equalizer digest for every
  candidate commit. A contiguous group of temporary-fix commits MAY map to one candidate commit only when the
  equalizer compares against the final original endpoint of that complete group.
- You MUST run `tools/verify-design-authorization.sh` and `tools/verify-commit-scope.sh` before the
  milestone handoff.
- An isolated worker MUST NOT push, merge, force-update a branch, or rewrite shared history. The coordinator
  follows `AGENTS.md`'s regular push and PR-ledger cadence after verified integration, unless the owner directs
  otherwise.

### 6. Run the milestone-scoped Claude review lanes

Run the review topology selected by the plan: one lane for ordinary milestones and three parallel lanes
for G9.5. Resume each lane in its own original named session.

**Constraints:**

- For a milestone other than `G9.5`, You MUST create one fresh unique name containing the milestone and
  a collision-resistant suffix, for example `capture-replay-G5-20260924T153012-18421`, and retain that
  exact name until the milestone closes.
- For `G9.5`, You MUST create three fresh unique names containing `G9.5`, the lane
  (`migration`, `test-evidence`, or `whole-design`), and a collision-resistant suffix. You MUST retain
  each exact name for every resumed pass in that lane.
- For `G9.5`, You MUST create three separate read-only worktrees under `/private/tmp`, pin all of them
  to the same exact `production_complete_ref`, and launch the three direct Claude CLI processes in
  parallel. You MUST NOT use API subagents because the execution contract requires direct CLI review.
- You MUST NOT launch those G9.5 lanes against the original noisy G9 commit when the minimal-delta sweep
  produced a different accepted candidate. The purpose of the sweep is to reduce duplicated inherited code
  and blame churn before reviewers spend context on the implementation.
- The bounded migration/disposition prompt MUST independently reconstruct carried, dropped, refactored,
  unchanged, retired, and net-new responsibilities from
  `2fe4538aef16eafa098545e76545873335bf2d11`; reconcile trace, limbo, and register claims with live
  reachability; and inspect move/copy history plus ordinary and copy-diagnostic blame.
- The bounded test-evidence prompt MUST compare assertions and behavior with both baseline
  `2fe4538aef16eafa098545e76545873335bf2d11` and the exact current upstream-main SHA; map the finite
  design/register test obligations without coverage-percentage targets; and inspect weakened or dropped
  tests, fake fidelity, failability, harness isolation, and falsification.
- The whole-design prompt MUST run the sole unbounded review authorized by `AGENTS.md` section 3.2
  against the complete implementation and the G9.5 cited designs and obligation inventories.
- For the first pass, You MUST invoke Claude directly with this shape, substituting the milestone,
  lane, exact commit, comparisons, design references, relevant register rows, unique name, and prompt:

  ```bash
  claude -p --name <fresh-unique-name> \
    '<milestone-scoped review prompt>' \
    --permission-prompts none \
    --output-format text \
    --tools 'Read,Grep,Glob,Bash' \
    --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git status *),Bash(git show *),Bash(git log *),Bash(git blame *),Bash(git rev-parse *),Bash(tools/java-without-limbo.py *)'
  ```

- The review prompt MUST establish read-only behavior, the exact diff, selected milestone, cited
  design sections, relevant prior-register verdicts, named invariants, out-of-scope work, and the
  Class A/B/C rules in `tools/review-prompt-design-conformance.md`.
- For every successive pass, You MUST use `claude -p --resume <fresh-unique-name>` and repeat the
  read-only `--tools` and `--allowedTools` flags on every invocation:

  ```bash
  claude -p --resume <fresh-unique-name> \
    '<next-pass prompt describing the verified fixes and current diff>' \
    --permission-prompts none \
    --output-format text \
    --tools 'Read,Grep,Glob,Bash' \
    --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git status *),Bash(git show *),Bash(git log *),Bash(git blame *),Bash(git rev-parse *),Bash(tools/java-without-limbo.py *)'
  ```

- You MUST omit `--no-session-persistence` because successive passes resume the milestone session.
- You MUST NOT use `--continue` because concurrent lanes can make "most recent" select the wrong
  review session.
- You MUST NOT use `--fork-session` for successive passes because fixes and dispositions must remain
  in one milestone review history.
- You MUST NOT use `--permission-mode plan`, `--restricted`, or `--safe-mode` because the repository
  contract forbids those review modes here.
- You MUST poll a running review at least every 60 seconds. Configure the invoking host to retain raw
  output under `/private/tmp`; do not add shell redirections, heredocs, command substitutions, or
  generated shell commands to the reusable invocation because they defeat stored approvals.
- If a G9.5 production correction changes the reviewed commit, You MUST resume every affected lane in
  its existing session. Before exit, every lane's final verdict MUST name one identical corrected
  production commit; resume an otherwise stale lane far enough to verify that the intervening diff is
  outside its bounded scope.

### 7. Verify findings and close the review loop

Verify every finding before accepting it, fix every real design-conformance defect, and re-review
every production change.

**Constraints:**

- You MUST require a verbatim design quote with document and section for every Class A finding.
- You MUST classify design silence as Class C and escalate it without inferring intended behavior.
- You MUST trace live reachability with limbo omitted and check the proposed fix against the design.
- You MUST triage non-conformance findings once as blocker, register item, or won't-fix.
- If a review fix changes production, You MUST resume the affected named Claude session or G9.5 lane
  sessions for another pass.
- You MUST continue until a pass has no unfixed Class A finding.
- On the third revision of one concern, or two consecutive rounds finding defects in the previous
  round's fix for that concern, You MUST ask the reviewer for a patch and verify rather than blindly
  applying it.

### 8. Deliver the milestone handoff

Finish with durable evidence and a clean primary checkout.

**Constraints:**

- You MUST update only the plan/register artifacts authorized by `AGENTS.md` and the selected
  milestone; if authorization is missing, You MUST stop instead of silently omitting required
  bookkeeping.
- You MUST verify the responsibility-placement map at the milestone boundary.
- For `G9.5`, You MUST instead verify that all three lane reports target the same exact final production
  commit and that every disposition is represented in the live register.
- You MUST finish with no milestone-owned uncommitted change; only unrelated changes recorded at
  preflight may remain because they belong to the user.
- You MUST report exactly these final sections: `Commits`, `Evidence`, `Findings and dispositions`,
  and `Status`.
- `Commits` MUST list each hash and coherent purpose.
- `Evidence` MUST list commands, outcomes, falsification results, and `/private/tmp` log paths without
  reproducing raw logs.
- `Findings and dispositions` MUST summarize impact, mitigation, Class A closure, Class B triage,
  Class C escalations, and any owner decision still required.
- `Status` MUST state branch, final `HEAD`, `git status --short`, whether the checkout is clean, and
  what integration, push, merge, or PR-ledger action occurred. An isolated worker reports that none
  occurred; the coordinator reports the actions required by `AGENTS.md` or the owner's direction.

## Examples

### Plan A milestone

**Input:**

- milestone: `G5`
- repository_path: the isolated checkout assigned to the task

**Expected behavior:** Codex reads only `G5`, its cited designs, and relevant register rows; maps the
connection/request ownership chains; implements and proves them; and runs all Claude passes in one
fresh session named for `G5`.

### Production-complete review milestone

**Input:**

- milestone: `G9.5`
- production_complete_ref: exact G9 production checkpoint

**Expected behavior:** Codex remains coordinator, launches the bounded migration/disposition, bounded
test-evidence delta, and sole unbounded whole-design Claude lanes in parallel against the exact minimal-delta
candidate, repairs verified findings, resumes affected lane sessions, updates the register, commits and pushes
the candidate branch, supplies the guarded active-branch replacement command, and stops before G10.

### Proxy milestone

**Input:**

- milestone: `PA2`

**Expected behavior:** Codex reads the proxy responsibilities needed to understand `PA2`, the `PA2`
repair backlog and exit, cited protocol sections, and relevant register rows. It does not read or
start a `G` milestone.

## Troubleshooting

### The milestone has no sufficient design reference

Stop under red line 1 and put the missing authority in the batched escalation table. Do not infer
behavior from plan prose or inherited code.

### A Claude pass cannot be resumed

Confirm the exact unique lane `--name` used on the first pass and that the first invocation omitted
`--no-session-persistence`. For G9.5, do not resume a different lane's session. Do not substitute
`--continue` or fork a new review history.

### Gradle output is too large

Use `tools/gradle-evidence.sh`; report its concise excerpt and `/private/tmp` path. Do not paste the
full log.
