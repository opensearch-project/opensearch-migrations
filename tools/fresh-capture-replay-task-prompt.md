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
instead. For G9.5, review the exact current `stableAndScalableLiveReplay` tip after G9. Do not squash,
reconstruct, minimize, or otherwise rewrite Git history. History and ordinary blame may be inspected as
supporting evidence, but attribution-only findings do not gate G9.5 and do not authorize a rewrite. Do not
edit production before all three initial lanes report.
Search REBUILD-LIMBO and history before creating or replacing anything; preserve inherited paths and blame.
Read REBUILD-TRACE records as navigation aids, but use tools/java-without-limbo.py for executable-code
searches and verify every mapping against code.
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
parallel direct-Claude read-only lanes to the same exact current production-complete commit: a bounded
migration/disposition audit from baseline `2fe4538a`, a bounded test-evidence delta audit against that
baseline and exact current upstream main, and the sole unbounded whole-design review under AGENTS.md
section 3.2. Give each lane a fresh unique name. Omit --no-session-persistence. Resume every affected
lane with claude -p --resume <name> after production corrections, repeating the complete read-only tool
restrictions every time, and require every final verdict to name the same exact commit. Do not use
--continue or --fork-session.

Commit coherent progress as Greg Schohn <schohn@amazon.com> with DCO. Isolated workers do not push or
merge; the coordinator follows AGENTS.md's integration, push, and PR-ledger rules. For G9.5, push only the
reviewed ordinary forward commits on `stableAndScalableLiveReplay`; do not create a replacement history or
force-update the branch. Finish with Commits, Evidence, Findings and dispositions, and Status, including
exact validation outcomes and clean-worktree state.
```
