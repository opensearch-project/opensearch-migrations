# Compress History, Then Minimize Blame

## Overview

Use this SOP after production implementation is complete and before G9.5 review. It first creates a
behavior- and tree-preserving candidate commit stack that removes abandoned implementation churn, folds
fixups into the commits they repair, and corrects DCO trailers. It then stops for owner approval. Only after
the owner approves the exact compressed candidate does it run the repository-wide minimal-delta and blame
cleanup.

This SOP never rewrites or force-pushes the active branch. It creates isolated candidate branches under
`/private/tmp/cdc-workers/`.

## Parameters

- **source_branch** (required): Active source branch whose history will be reconstructed.
- **source_tip** (required): Exact immutable commit SHA supplied by the launch prompt as the source-stack tip.
- **g9_production_ref** (required): Exact G9 production-code checkpoint retained for milestone accounting.
- **upstream_ref** (required): Exact lower comparison ref or remote ref that resolves to the branch base.
- **protected_author_commit** (required): Commit to preserve standalone with its original author and metadata.
- **trace_baseline** (required): Mainline behavior baseline used by the later blame cleanup.
- **compression_branch** (optional, default: `codex/history-compression-candidate`): Compressed candidate branch.
- **compression_worktree** (optional, default: `/private/tmp/cdc-workers/history-compression-candidate`): Compression worktree.
- **blame_branch** (optional, default: `codex/history-blame-candidate`): Later source-shaped candidate branch.
- **blame_worktree** (optional, default: `/private/tmp/cdc-workers/history-blame-candidate`): Blame-cleanup worktree.
- **report_dir** (optional): Review directory; if omitted, use `mktemp -d /private/tmp/cdc-history-compression-review.XXXXXX`.

**Constraints for parameter acquisition:**

- If all required parameters are already provided, You MUST proceed to the Steps.
- If any required parameters are missing, You MUST ask for them before proceeding.
- When asking for parameters, You MUST request all parameters in a single prompt.
- When asking for parameters, You MUST use the exact parameter names as defined.

## Steps

### 1. Freeze the source stack

Resolve immutable inputs and create the bounded source inventory.

**Constraints:**

- You MUST read `AGENTS.md` completely before taking repository action.
- You MUST require `source_tip` to be a full commit SHA and use it unchanged throughout the task.
- You MUST resolve and record the full SHA of every other ref parameter.
- You MUST verify that `source_branch` resolves to `source_tip` at task start; if it differs, You MUST stop
  because the launch prompt is stale and the owner must choose the intended tip.
- You MUST verify that `g9_production_ref` is an ancestor of `source_tip`.
- You MUST use an explicit `upstream_ref..source_tip` lower and upper bound for every commit
  enumeration because an omitted lower bound previously expanded this work to unrelated history.
- You MUST record the first-parent commit count, first SHA, last SHA, tree SHA, author, author date, committer,
  committer date, subject, body, and DCO trailers in files under `/private/tmp`.
- You MUST verify that `protected_author_commit` is in the bounded range.
- You MUST NOT use Brazil or API-launched subagents because this repository uses direct CLI workers only.
- You MUST NOT edit files under `docs/captureAndReplay/` because those designs are authoritative.

### 2. Build the compression map

Partition the source stack into contiguous ranges that either remain one-to-one, combine into one candidate
commit, or disappear because their cumulative tree effect is empty.

**Constraints:**

- You MUST preserve `protected_author_commit` as a one-to-one standalone commit with its original author,
  author date, committer, committer date, message, and tree effect.
- You MUST preserve history-carry, move, rename, and cross-file-copy commits as standalone commits whenever
  combining them with later edits could weaken ordinary Git rename/copy detection or GitHub blame.
- You MUST preserve retained design commits separately from implementation commits.
- With the owner's explicit authorization for the exact reconstruction, You MAY combine design and
  implementation inside a contiguous abandoned or superseded history wave. Its subject MUST begin
  `History wave (abandoned):` or `History wave (superseded):`, its message MUST contain the matching
  `History treatment:` line, and Step 4 MUST validate it through a temporary full-SHA allowlist. This
  exception exists only to make discarded ideas occupy one obvious historical turn; it never applies to
  retained design evolution or ordinary implementation commits.
- You MUST combine only contiguous ranges; every source commit MUST appear exactly once in the map.
- You SHOULD fold temporary fixes, review corrections, and abandoned implementation experiments into the
  coherent change that introduced or removed them.
- You SHOULD drop an abandoned range whose cumulative tree effect is empty rather than retain a historical
  tombstone.
- You MUST identify the Mailbox-era and other superseded ownership experiments by their actual changed paths
  and surviving tree effect; names in commit subjects are hints, not proof.
- You MUST preserve the detailed intent of every combined commit in the candidate message and mapping report.
- You MUST record every original author, author date, committer, and committer date in the mapping report.
  When one candidate commit represents multiple original commits, You MUST also record which original
  metadata value is used by Git and why the remaining values cannot occupy the single Git metadata slot.
- You MUST write the proposed contiguous mapping to `report_dir/compression-map.tsv` with columns
  `source_start`, `source_end`, `source_count`, `source_subjects`, `disposition`, `candidate_subject`,
  `protected_boundary`, and `reason`.
- You MUST use the one-line source commit subjects as the initial summaries and keep them editable in the
  workbook created in Step 4.
- You MUST stop with one batched owner-decision table if a proposed group crosses authors, design and
  implementation, noncontiguous history, or a history-preserving carry boundary because those are not
  mechanical squash choices.

### 3. Construct the compressed candidate

Replay the approved-by-rule compression map into one isolated candidate stack without source shaping.

**Constraints:**

- You MUST create `compression_branch` only in `compression_worktree` under `/private/tmp/cdc-workers/`.
- You MUST start from the exact parent represented by `upstream_ref`.
- You MUST construct candidate commits in ascending source order.
- After every candidate commit, You MUST compare its complete tree byte-for-byte with the cumulative original
  endpoint represented by its mapped contiguous range.
- You MUST stop before the next commit if the trees differ because phase 1 changes history shape only.
- Every non-merge candidate commit authored as Greg Schohn MUST contain
  `Signed-off-by: Greg Schohn <schohn@amazon.com>`.
- You MUST retain original non-owner authorship and valid signoffs rather than replacing them with owner
  authorship.
- You MUST NOT perform minimal-delta source edits in this phase because the owner must review compression
  independently from blame cleanup.
- You MUST NOT update or force-push `source_branch` because the candidate remains reversible.

### 4. Validate and present the compressed candidate

Prove that compression changed only commit boundaries and metadata representation.

**Constraints:**

- You MUST prove that the final candidate tree SHA equals the `source_tip` tree SHA.
- You MUST identify the candidate commit whose cumulative tree corresponds to `g9_production_ref`, while
  retaining every later steering/tool commit through `source_tip`.
- You MUST run `git range-diff`, DCO validation, commit-scope validation, and applicable repository history
  checks.
- If the approved compression map contains mixed abandoned or superseded history waves, You MUST write their
  exact candidate SHAs to `report_dir/mixed-history-wave-allowlist.txt` and run
  `tools/verify-design-authorization.sh <mapped-design-baseline> --allow-history-wave-mixes <file>`.
  The default no-allowlist guard MUST remain strict.
- You MUST leave `compression_worktree` intact and write these review files:
  - `report_dir/compression-review.md`: compact summary and approval checklist.
  - `report_dir/history-compression-review.xlsx`: editable Excel review workbook.
  - `report_dir/compression-map.tsv`: one row per source range with its final candidate SHA.
  - `report_dir/source-log.txt`: chronological full source log with metadata and messages.
  - `report_dir/candidate-log.txt`: chronological full candidate log with metadata and messages.
  - `report_dir/range-diff.txt`: complete source-versus-candidate `git range-diff`.
  - `report_dir/tree-check.txt`: source endpoint and candidate endpoint commit and tree SHAs.
  - `report_dir/dco-check.txt`: DCO result for every candidate commit.
  - `report_dir/preserved-boundaries.txt`: Aman, design, carry, move, rename, and copy boundaries deliberately
    kept separate.
- You MUST build `history-compression-review.xlsx` with these worksheets:
  - `Summary`: source and candidate refs, old and new commit counts, final tree equality, DCO status,
    preserved-boundary count, dropped-range count, candidate worktree, and an editable owner decision.
  - `Candidate view`: chronological source commits with one source commit per row. Vertically merge the
    `Group`, `Action`, `Candidate subject`, `Candidate SHA`, and `Reason` cells across every proposed squash
    group so the compression is visually obvious. Keep each source commit's short SHA and one-line summary
    on its own unmerged row.
  - `Edit plan`: one unmerged row per source commit with editable `Group`, `Action`, `Candidate subject`,
    `Reason`, `Owner notes`, and `Owner decision` columns. This is the authoritative editable view when the
    owner wants to alter grouping.
  - `Source details`: immutable chronological source metadata and full commit messages.
- You MUST use restrained formatting, freeze headers, enable filters on unmerged tables, wrap summaries, and
  mark editable cells with a light amber fill.
- You MUST NOT merge the editable `Edit plan` rows because the owner must be able to regroup individual
  commits without manually unmerging cells.
- You MUST render and visually inspect every worksheet before delivery because clipped summaries or ambiguous
  merged groups would make the approval artifact unreliable.
- If the owner edits and returns the workbook, You MUST treat `Edit plan` as proposed input, validate that
  every group is contiguous and respects protected boundaries, rebuild the compressed candidate if needed,
  and regenerate `Candidate view` before requesting approval again.
- You MUST show the owner a compact chronological table in the final response with columns
  `original range`, `original commits`, `action`, `candidate commit`, and `reason`.
- You MUST call out dropped net-zero ranges, preserved carry/move commits, metadata disposition, old and new
  commit counts, exact validation results, candidate branch, candidate SHA, candidate worktree, `report_dir`,
  and the exact workbook path.
- You MUST include direct commands for inspecting the candidate log and full range-diff from the preserved
  worktree.
- You MUST provide an exact guarded replacement command but MUST NOT run it because only the owner may replace
  the active branch.
- You MUST stop after presenting the compressed candidate.
- You MUST NOT begin Step 5 until the owner explicitly approves the exact `compression_branch` SHA for blame
  cleanup because source shaping should target the commit stack the owner intends to keep.
- When the owner approves, You MUST require the approval to name the exact candidate SHA so a stale candidate
  cannot silently enter Step 5.

### 5. Run repository-wide minimal-delta cleanup after approval

Continue only after the owner explicitly identifies the approved compressed candidate SHA.

**Constraints:**

- You MUST use the approved compressed candidate, not the original noisy stack, as the source stack.
- You MUST follow `TrafficCapture/trafficReplayer/tools/minimizeBlameChangesTask.md`, with its repository-wide
  Java scope, using `blame_branch` and `blame_worktree`.
- You MUST cover every changed Java file in the repository, including replayer, proxy, tests, Java fixtures,
  and Java tooling.
- Proxy Java is included only for source shaping and history preservation; You MUST NOT implement PA work,
  change proxy behavior, or make proxy design or contract decisions because those remain separately owned.
- After every candidate commit, You MUST run
  `tools/java-structural-equalizer.py compare-commit-pair` without `--root`.
- Syntactically valid Java MUST use structural canonicalization. Intentionally unparseable `.java` fixtures
  MUST use the equalizer's exact-content digest fallback so they remain covered rather than silently omitted.
- You MUST stop on any structural mismatch, ambiguous predecessor, design silence, contract decision, or
  behavior change and present one batched escalation table.

### 6. Validate and deliver the blame-cleaned candidate

Prove the final source-shaped stack preserves behavior while improving attribution.

**Constraints:**

- You MUST complete every verification and deliverable in
  `TrafficCapture/trafficReplayer/tools/minimizeBlameChangesTask.md`.
- You MUST compare the final candidate against the approved compressed candidate, not the original noisy
  stack.
- You MUST report ordinary blame improvements, unresolved attribution gaps, per-commit equalizer results,
  final-tree differences, tests, and validation evidence.
- You MUST push only the candidate branch and provide the exact `--force-with-lease` replacement command.
- You MUST NOT replace the active branch because final adoption remains an owner action.
- You MUST stop before G9.5 review, G10, or G11 because this task prepares their review input only.

## Examples

### Plan A production-complete stack

```text
Follow tools/compress-history-then-minimize-blame.sop.md with:
source_branch=stableAndScalableLiveReplay
source_tip=<SOURCE_TIP>
g9_production_ref=f4be4f7b3071e7f02c569c0346765de5208dd57e
upstream_ref=upstream/main
protected_author_commit=945f71cdbad5bc87ad878e30ecd2468bbc6d2b50
trace_baseline=2fe4538aef16eafa098545e76545873335bf2d11

Complete Steps 1-4, then stop for owner approval. Do not begin Step 5 in the same turn.
```

## Troubleshooting

### A proposed squash crosses a protected boundary

Keep the commits separate and include the ambiguity in the batched decision table.

### A compressed candidate tree differs

Stop at the first mismatching candidate commit and compare that candidate with its mapped original endpoint.
Do not continue or compensate in a later commit.

### A Java fixture does not parse

Use the structural equalizer's exact-content fallback. Do not exclude the file from repository-wide
validation.
