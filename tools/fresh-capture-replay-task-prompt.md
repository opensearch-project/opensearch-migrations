# Fresh capture/replay task prompt

Replace `<MILESTONE>` and paste this as the first message in each fresh task:

```text
Implement exactly milestone <MILESTONE> in this repository.

Read AGENTS.md completely before taking any repository action, then follow
tools/fresh-capture-replay-milestone.sop.md with milestone=<MILESTONE>. Codex is the implementer and
coordinator. Claude is a direct-CLI, read-only reviewer. Do not use Brazil or API-launched subagents.

Read only the selected milestone, its cited authoritative design sections, and relevant live-register
rows. Before editing, map every responsibility through producer, queue, owner, consumer,
observability, construction path, and evidence. Search REBUILD-LIMBO and history before creating or
replacing anything; preserve inherited paths and blame. Read REBUILD-TRACE records as navigation aids,
but use tools/java-without-limbo.py for executable-code searches and verify every mapping against code.
Trace only behavior executable at the settled mainline rebuild baseline: use exact old-method → new-method
mappings for moved, split, rewritten, or retired responsibilities. A post-baseline method may be the target
only for the inherited responsibility slice it receives; do not trace unchanged responsibilities or
branch-only responsibilities with no mainline predecessor.
Before bulk trace editing, show three to five representative decisions and obtain owner confirmation.

Stop at red lines with one batched decision table. Otherwise implement the complete usable chains,
their observability, wiring, and focused evidence. Invoke Gradle only through
tools/gradle-evidence.sh. Keep raw logs outside the conversation.

Start this milestone's Claude reviews with one fresh unique --name. Omit
--no-session-persistence. Resume successive review passes with claude -p --resume <name>, repeating
the complete read-only tool restrictions every time. Do not use --continue or --fork-session.

Commit coherent progress as Greg Schohn <schohn@amazon.com> with DCO. Isolated workers do not push or
merge; the coordinator follows AGENTS.md's integration, push, and PR-ledger rules. Finish with Commits,
Evidence, Findings and dispositions, and Status, including exact validation outcomes and clean-worktree
state.
```
