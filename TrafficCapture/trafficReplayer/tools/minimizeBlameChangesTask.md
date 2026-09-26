Perform the Plan A minimal-delta and history-reconstruction sweep.

Read AGENTS.md completely before acting. Do not use Brazil or API subagents. Do not edit authoritative files under docs/captureAndReplay/.

Run this after the commit-compression phase in `../../../tools/compress-history-then-minimize-blame.sop.md`
has produced a validated candidate and the owner has explicitly approved its exact SHA for blame cleanup.
This is the final mutating preparation inside G9.5 before any of the three read-only review lanes. Create a
separate candidate branch and worktree under `/private/tmp/cdc-workers/`. Never rewrite or force-push the
active branch.

Objective

Rewrite the implementation and commit history so inherited code was changed as surgically as reasonably possible, while preserving the proved behavior and design.

Resolve and record the exact current upstream-main SHA. Enumerate every surviving added line or replaced line
range in every changed Java file in the repository against that commit, including replayer and proxy
production code, tests, Java fixtures, and Java tooling. Proxy files are in scope only for source shaping and
history preservation: this task does not implement PA work, change proxy behavior, or make proxy design or
contract decisions. The unit of analysis is a changed hunk, not every unchanged method and not only work
assigned to G9, named by a
REBUILD-TRACE record, or assigned to a particular Plan A generation. Use baseline
2fe4538aef16eafa098545e76545873335bf2d11 only to establish the rebuild trace/disposition boundary; use
current upstream main to find the latest inherited implementation and history that the branch must not
silently overwrite or duplicate.

Generate the comparison with rename and copy detection enabled. Treat its added-line ranges as the
completeness index, not as proof that the lines are net-new: file moves, method moves, splits, merges, and
small edits can all hide inherited origins. “Pin back to the source” means reconstructing the move, copy, or
promotion in Git history so unchanged lines retain their original blame; a trace comment alone does not pin
history.

For every surviving added or replaced hunk:

1. Search for the implementation that should have been its starting point, in this order:
    - the same file on current upstream main, including a neighboring or differently named method;
    - retained REBUILD-LIMBO members;
    - code deleted or moved elsewhere by the rebuild;
    - REBUILD-TRACE mappings;
    - Git history and other files.

2. Decide whether the hunk is:
    - wholly inherited behavior;
    - a narrow adaptation of inherited behavior;
    - a merge of slices from multiple inherited implementations;
    - or genuinely net-new behavior.

3. When inherited code exists, reconstruct from the original method or smallest coherent method slice instead
   of keeping a rewritten copy. Move, copy, or promote that inherited implementation into the design-correct
   live location before adapting it so ordinary blame can remain attached to the original source lines.

4. When one new method combines inherited code from multiple places, split it into the smallest coherent
   methods needed to let each inherited slice originate from its own predecessor. Then change only the lines
   required by the new types, ownership model, design, or construction path and remove duplicate live copies.

5. Leave a hunk as net-new only when no inherited implementation or coherent inherited slice is a valid
   starting point. Record that conclusion; do not manufacture a source mapping merely to reduce the diff.

6. Classify post-baseline upstream-main drift explicitly. Do not silently discard it, automatically merge it,
   or call it net-new merely because it arrived after the settled trace baseline. Preserve it when it is still
   applicable; otherwise report the exact intentional design or owner decision that supersedes it.

For example, if an old method and a new method have mostly the same body, promote the old method and alter only its changed parameters and data-access expressions. Do not recreate its unchanged body as new code.

Important limits

- Do not change behavior merely to obtain a smaller diff.
- Do not preserve legacy types, compatibility adapters, obsolete ownership, or incorrect responsibility placement merely to obtain a smaller diff.
- The authoritative design wins even when it requires a larger change.
- Do not delete retained limbo sources or trace records until their disposition and history have been verified.
- Do not change public contracts, configuration, metrics, exit codes, serialization, or observable behavior without stopping for owner approval.
- If source-to-target correspondence is ambiguous, report it instead of guessing.

History reconstruction

After shaping the source:

1. Freeze an explicitly bounded original commit list. Never invoke `git rev-list` without both the lower and
   upper bound. Precompute hunk provenance once; do not rescan the full preceding history for every commit.
2. Create one separate candidate tree from the exact parent of the first commit being rewritten.
3. Replay the bounded original list in ascending order. Each candidate commit maps to one contiguous original
   commit or commit range; the candidate parent must already be validated before the next commit is built.
4. Immediately after creating every candidate commit, run `tools/java-structural-equalizer.py
   compare-commit-pair` against the original cumulative endpoint represented by that commit. The tool compares
   the repository-wide union of Java paths edited by the original range and candidate commit. Do not pass
   `--root` during this sweep; narrowing the comparison could omit proxy or other changed Java files.
5. The equalizer strips comments, normalizes Java tokens, and ignores import, method, uninitialized-field, and
   nested-type declaration order. It preserves initialized-field, initializer-block, enum-constant, record
   component, and method-body order because changing them can change behavior. Intentionally unparseable
   `.java` fixtures use an exact-content digest fallback and remain part of the comparison.
6. A structural mismatch blocks the replay before the next commit. Inspect the canonical outputs under
   `/private/tmp`; do not waive the mismatch as a formatting or blame-only change. A cleanup-only method split
   or merge that changes canonical structure is not eligible for this automatic equivalence path and requires
   a separate explicit owner decision and behavioral proof.
7. Squash temporary fixes and review corrections into the milestone commit they repair by mapping that
   contiguous original range to one candidate commit and comparing the candidate against the range endpoint.
8. Keep history-preserving carry, move, and cross-file-copy commits separate when combining them with later
   edits would weaken Git rename/copy detection.
9. Do not collapse the entire rebuild into one commit.
10. Preserve original author, author date, committer information, detailed intent, and owner DCO requirements
    as required by AGENTS.md.
11. Produce an old-commit to candidate-commit mapping and record the equalizer result for every candidate row.
12. Do not update or force-push the real branch. Stop with a candidate branch for owner review.

Verification

- Compare the old production-complete result with the candidate and list every final-tree difference.
- Every difference must be attributable to source placement, removal of duplication, or a more surgical expression of the same proved behavior.
- Run ordinary git blame and demonstrate that unchanged inherited lines retain their earlier authorship.
- Run git range-diff across the old and candidate commit stacks.
- Verify that every candidate commit has a passing structural-equalizer record against its mapped original
  cumulative endpoint; final-tree equivalence is not a substitute for a missing per-commit result.
- Run the applicable limbo, design-authorization, compilation, deterministic test, and milestone evidence checks.
- Invoke Gradle only through tools/gradle-evidence.sh.
- Run one bounded direct Claude CLI read-only review of the candidate’s source-shaping and history preservation. Do not request a new whole-design review.

Deliver

- Original production-complete G9 SHA.
- Exact current upstream-main SHA.
- Candidate branch and final SHA.
- Commit mapping.
- Per-commit structural-equalizer results and canonical-output paths for any failed attempt.
- Files and methods reshaped.
- Complete repository-wide inventory of surviving added or replaced Java hunks, with each inherited starting point
  or explicit net-new disposition.
- Post-baseline upstream-main drift and its disposition.
- Duplicate implementations removed.
- Before/after blame results.
- Final-tree differences and justification.
- Test and validation evidence.
- Ambiguous mappings or owner decisions still required.
- Exact proposed force-with-lease command, but do not run it.

Return the candidate to the G9.5 coordinator. The three G9.5 read-only review lanes must use that candidate
commit, not the original noisy G9 commit. The candidate may be reviewed and corrected before owner
acceptance, but neither this sweep nor the coordinator may force-update the active branch. Do not start G10
or G11.
