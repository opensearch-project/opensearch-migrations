# Fresh capture/replay task prompt

Replace `<MILESTONE>` and paste this as the first message in each fresh task:

```text
Execute exactly milestone <MILESTONE> in this repository.

Read AGENTS.md completely before taking any repository action, then follow
tools/fresh-capture-replay-milestone.sop.md with milestone=<MILESTONE>. Codex is the implementer and
coordinator. Claude is a direct-CLI, read-only reviewer. Do not use Brazil or API-launched subagents.

Read only the selected milestone, its cited authoritative design sections, and relevant live-register
rows. Before editing, map every responsibility through producer, queue, owner, consumer,
observability, construction path, and evidence. For G9.5, use the SOP's three-lane review-boundary map
instead. First run tools/compress-history-then-minimize-blame.sop.md from the completed G9 commit. Complete
its byte-identical compression phase and stop for owner confirmation. Only after the owner approves the exact
compressed candidate SHA may you run the repository-wide minimal-delta phase. Review the resulting
blame-cleaned candidate before asking the owner to replace the active branch. Do not edit that candidate
before all three initial lanes report. Search REBUILD-LIMBO and history before creating or replacing
anything; preserve inherited paths and blame. Read REBUILD-TRACE records as navigation aids, but use
tools/java-without-limbo.py for executable-code searches and verify every mapping against code.
The minimal-delta sweep must enumerate every surviving added or replaced hunk in every changed Java file
repository-wide, including replayer and proxy production code, tests, Java fixtures, and Java tooling,
against one exact current upstream-main SHA. Proxy Java is included only for source shaping and history
preservation; do not implement PA work, change proxy behavior, or make proxy design or contract decisions.
Enable rename and copy detection and use the added-line ranges as a completeness index, not proof of net-new
code. For each hunk, search the same file, limbo, deleted or moved rebuild code, other files, and history for
the inherited method or coherent method slice that should have been its starting point. Move, copy, or
promote that predecessor before adapting it; split a combined target into coherent methods when needed to
retain each inherited source. Do not inventory unchanged members. Pin through reconstructed Git ancestry and
ordinary blame, not only through a trace comment. Use baseline `2fe4538a` only as the trace boundary and
explicitly disposition every applicable post-baseline upstream-main change.
Freeze the exact original commit list with both lower and upper bounds. Precompute hunk provenance once, then
build one isolated candidate tree from oldest to newest. After every candidate commit, run
tools/java-structural-equalizer.py compare-commit-pair without `--root` against the cumulative original
endpoint represented by that commit; the comparison must cover the repository-wide union of changed Java
paths. Do not create the next commit unless the canonical structures match. The equalizer ignores comments
and safe declaration ordering but preserves initialization, enum, record, and method-body order.
Intentionally unparseable `.java` fixtures use its exact-content digest fallback and remain covered. Record
the original endpoint and equalizer digest for every candidate commit. A cleanup-only split or merge that
changes canonical structure requires owner direction rather than an equalizer waiver.
Trace only behavior executable at the settled mainline rebuild baseline: use exact old-method → new-method
mappings for moved, split, rewritten, or retired responsibilities. A post-baseline method may be the target
only for the inherited responsibility slice it receives; do not trace unchanged responsibilities or
branch-only responsibilities with no mainline predecessor.
Place each trace immediately before the exact method it describes. Keep eligible source methods in limbo
through the final completeness sweep, including sources mapped to RETIRED; remove source and trace together
only after that sweep. Do not restore a source already absent before Plan A's carry baseline—anchor its source
record to `2fe4538a` at the surviving replacement seam and record the gap.
Before bulk trace editing, show three to five representative decisions and obtain owner confirmation.

Stop at red lines with one batched decision table. Otherwise implement the complete usable chains,
their observability, wiring, and focused evidence. Invoke Gradle only through
tools/gradle-evidence.sh. Keep raw logs outside the conversation.

For milestones other than G9.5, start Claude review with one fresh unique --name. For G9.5, pin three
parallel direct-Claude read-only lanes to the same exact minimal-delta candidate commit: a bounded
migration/disposition audit from baseline `2fe4538a`, a bounded test-evidence delta audit against that
baseline and exact current upstream main, and the sole unbounded whole-design review under AGENTS.md
section 3.2. Give each lane a fresh unique name. Omit --no-session-persistence. Resume every affected
lane with claude -p --resume <name> after production corrections, repeating the complete read-only tool
restrictions every time, and require every final verdict to name the same exact commit. Do not use
--continue or --fork-session.

Commit coherent progress as Greg Schohn <schohn@amazon.com> with DCO. Isolated workers do not push or
merge; the coordinator follows AGENTS.md's integration, push, and PR-ledger rules. For G9.5, push only the
reviewed candidate branch and provide the guarded replacement command; do not force-update the active branch
without owner approval. Finish with Commits, Evidence, Findings and dispositions, and Status, including exact
validation outcomes and clean-worktree state.
```
