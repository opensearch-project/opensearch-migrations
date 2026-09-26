# History compression and blame-cleanup task prompt

Paste this into a fresh task:

```text
Prepare the Plan A history-compression candidate, then stop for my approval before blame cleanup.

Read AGENTS.md completely before taking repository action. Follow
tools/compress-history-then-minimize-blame.sop.md with these inputs:

source_branch=stableAndScalableLiveReplay
source_tip=<SOURCE_TIP>
g9_production_ref=f4be4f7b3071e7f02c569c0346765de5208dd57e
upstream_ref=a960887ba76eb0d3c6d2a252bc553fee339d3318
protected_author_commit=945f71cdbad5bc87ad878e30ecd2468bbc6d2b50
trace_baseline=2fe4538aef16eafa098545e76545873335bf2d11
compression_branch=codex/history-compression-candidate
compression_worktree=/private/tmp/cdc-workers/history-compression-candidate
blame_branch=codex/history-blame-candidate
blame_worktree=/private/tmp/cdc-workers/history-blame-candidate

Codex is the coordinator. Do not use Brazil or API-launched subagents. Use direct CLI processes only when
the SOP requires them. Never rewrite or force-push the active branch.

Complete Steps 1-4 only. Preserve Aman Kumar's commit exactly and preserve design, carry, move, rename, and
copy boundaries whenever combining them could weaken attribution. Compress abandoned Mailbox-era and other
superseded implementation churn only when the mapped contiguous range has the proved cumulative tree effect.
Fold fixups and review corrections into the coherent commit they repair. Correct DCO trailers.

Before launching, replace `<SOURCE_TIP>` with the exact full SHA at the tip of
stableAndScalableLiveReplay. Do not resolve a moving branch name inside the task. Verify that source_branch
still points to that exact SHA and that it contains the G9 checkpoint
f4be4f7b3071e7f02c569c0346765de5208dd57e. The compressed candidate's final tree must be byte-identical to
the supplied source tip, not merely to the older G9 checkpoint. Validate every candidate commit against its
mapped cumulative original endpoint before advancing.

Create the editable Excel review workbook required by the SOP. The Candidate view must use vertically merged
group cells while retaining one unmerged row per original commit and one-line commit summary. The Edit plan
must remain unmerged and editable so I can change group IDs, actions, candidate subjects, reasons, notes, and
decisions. Render and visually inspect every worksheet.

Present the candidate branch, exact SHA, preserved worktree, workbook path, report directory, compact
source-to-candidate map, old/new commit counts, dropped ranges, preserved attribution boundaries, tree
equality, DCO results, and inspection commands. Then stop. Do not begin the repository-wide blame cleanup
until I explicitly approve that exact candidate SHA in a later message.
```
