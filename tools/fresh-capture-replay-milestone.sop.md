# Fresh Capture/Replay Milestone

## Overview

Use this SOP to start and complete exactly one fresh capture/replay milestone: a Plan A `G` milestone
or proxy milestone `PA1`, `PA2`, or `PA3`. Codex is the implementer and coordinator. Claude is an
independent, direct-CLI, read-only reviewer whose session persists only across review passes for the
selected milestone.

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
- You MUST identify missing links, unreachable components, design silence, compatibility choices,
  inherited-file/history risks, and responsibility-placement mismatches.
- You MUST use `tools/java-without-limbo.py search` or `print` when inspecting live Java so
  `REBUILD-LIMBO` bodies and `REBUILD-TRACE` equivalence records cannot contaminate the live-code analysis.
- You MUST read the selected milestone's `REBUILD-TRACE` records as a bidirectional source/target map, but
  MUST verify every mapping against executable code and history rather than treating the comment as proof.
- Before proposing any new file, class, method, or test, You MUST search marked regions and history
  for its predecessor because Plan A preserves inherited source and blame in place.
- You MUST NOT begin implementation before each responsibility has a complete usable chain or a
  red-line blocker because a compiling but unwired component is not milestone progress.

### 3. Implement as Codex

Codex owns implementation, integration, register updates, and final commit messages.

**Constraints:**

- You MUST implement only the selected milestone and its complete usable chains.
- You MUST preserve inherited files, paths, blame, authorship, author dates, committers, and commit
  dates whenever carrying or rewriting history.
- You MUST restore and refactor a limbo predecessor before creating a parallel implementation unless
  the rejection is an explicit recorded decision.
- You MUST keep tests with the production behavior they prove and add observability with the
  component that needs it.
- For every function whose responsibility is rewritten, split, moved, or deliberately retired, You MUST
  add a bidirectional `REBUILD-TRACE` source/target record. These records remain through later milestones
  for equivalence review and are removed only during the owner's final pre-merge cleanup.
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
- You MUST run `tools/verify-design-authorization.sh` and `tools/verify-commit-scope.sh` before the
  milestone handoff.
- An isolated worker MUST NOT push, merge, force-update a branch, or rewrite shared history. The coordinator
  follows `AGENTS.md`'s regular push and PR-ledger cadence after verified integration, unless the owner directs
  otherwise.

### 6. Run the milestone-scoped Claude review

Start one fresh named Claude session for this milestone, then resume that same session for every
successive design-conformance pass.

**Constraints:**

- You MUST create a fresh unique name containing the milestone and a collision-resistant suffix,
  for example `capture-replay-G5-20260924T153012-18421`, and retain that exact name until the
  milestone closes.
- For the first pass, You MUST invoke Claude directly with this shape, substituting the milestone,
  diff, design references, relevant register rows, unique name, and prompt:

  ```bash
  claude -p --name <fresh-unique-name> \
    '<milestone-scoped review prompt>' \
    --permission-prompts none \
    --output-format text \
    --tools 'Read,Grep,Glob,Bash' \
    --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git status *),Bash(git show *),Bash(git log *),Bash(tools/java-without-limbo.py *)'
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
    --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git status *),Bash(git show *),Bash(git log *),Bash(tools/java-without-limbo.py *)'
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

### 7. Verify findings and close the review loop

Verify every finding before accepting it, fix every real design-conformance defect, and re-review
every production change.

**Constraints:**

- You MUST require a verbatim design quote with document and section for every Class A finding.
- You MUST classify design silence as Class C and escalate it without inferring intended behavior.
- You MUST trace live reachability with limbo omitted and check the proposed fix against the design.
- You MUST triage non-conformance findings once as blocker, register item, or won't-fix.
- If a review fix changes production, You MUST resume the same named Claude session for another pass.
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

Confirm the exact unique `--name` used on the first pass and that the first invocation omitted
`--no-session-persistence`. Do not substitute `--continue` or fork a new review history.

### Gradle output is too large

Use `tools/gradle-evidence.sh`; report its concise excerpt and `/private/tmp` path. Do not paste the
full log.
