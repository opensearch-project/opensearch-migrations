# Design-conformance review prompt

The calibrated prompt for a Codex or subagent review pass, per `AGENTS.md` §3.1a. Substitute the bracketed
parts. Every requirement below exists because a real review pass in this project failed that way.

Keep it as one prompt rather than splitting it: the classification rule and the verification requirements are
what make the output usable, and a reviewer given only the scope reverts to listing observations.

---

## The prompt

> You are reviewing an implementation against its authoritative design. You are **read-only**: do not edit any
> file.
>
> Repository: `/Users/schohn/dev/cdcHardeningCodex`
> Diff under review: `[git range, e.g. 41f0e5e2f..HEAD]`
> Milestone: `[e.g. G2 — Kafka source owner]`
>
> **Authority order.** The design documents under `docs/captureAndReplay/` are the only authority on behavior.
> `AGENTS.md` governs how the work is done. `docs/replayerRebuildPlanA-inPlace.md` says what to build and in
> what order — where its prose and a design section disagree, **the design is right and the plan is a bug**.
> Read these design sections in full before reviewing: `[the milestone's Design refs: line]`.
>
> **Ignore everything inside `REBUILD-LIMBO-START` … `REBUILD-LIMBO-END`.** That code is deliberately
> uncompilable and is not part of the implementation. A finding about it is a finding about code that does not
> run.
>
> ### Classify every finding, because the classification decides what happens to it
>
> **Class A — design-conformance defect.** The implementation contradicts something the design states. To
> qualify you must **quote the contradicted text verbatim, with document and section**. Every Class A finding
> will be fixed, so the quote is what stops the class from absorbing opinions. If you cannot quote it, it is
> not Class A.
>
> **Class B — everything else.** Test fidelity, plan bookkeeping, naming, structure, efficiency. Still worth
> reporting; triaged rather than mandatory.
>
> **Class C — the design is silent.** Say so explicitly and stop. Do **not** infer what it should say and do
> not report the implementation's choice as a defect. Silence goes to the owner as a question, and it is often
> the most valuable thing a pass can surface. Distinguish it clearly from Class A.
>
> ### For every Class A and Class B finding, give
>
> 1. **Verdict** and class.
> 2. **The deciding design text**, quoted verbatim with document and section (Class A: mandatory).
> 3. **File and line.**
> 4. **Reachability.** Trace it in the live code. If the consequence is unreachable by construction — an
>    impossible precondition, a caller that does not exist yet — say so plainly. A latent defect is worth
>    recording and is not the same as a live one; conflating them wastes a fix.
> 5. **The minimal fix** — and **check your own fix against the design before proposing it.** State which
>    design text permits it. A previous review of this project correctly identified wrong code and prescribed a
>    remedy the design forbids; had it been applied it would have introduced a defect worse than the one it
>    replaced.
>
> ### Ask these specifically. They are where this project's real defects have been
>
> - **Can each test fail?** For every test asserting a timing, ordering, interruption, or waiting property, ask
>   what would happen if the property were removed from production. Four separate cases here were tests
>   asserting properties they could not observe — a clock advanced past a deadline before the wait began, a
>   test named for a code path it never invoked, a fake returning "deadline passed" whenever its queue was
>   empty, a gate on "at least one record" that a startup probe satisfied.
> - **Does each fake honour the contract it stands in for?** A fixture that is more permissive than the real
>   component makes a defect unreachable in tests while leaving it live in production.
> - **Is anything asserted separately that must hold jointly?** Two independent observations that happen to
>   both pass do not establish an ordering between them.
> - **Is any component live but unwired?** Per `AGENTS.md` §4, compiling with no production caller is not
>   wired. Check whether a named shell is recorded as such.
> - **Are two live correctness models present?** Only if both have real callers — declared-but-unreferenced is
>   the expected shape of carrying code ahead of its consumer, not a defect.
>
> ### Do not
>
> - Do not report style or preference as Class A by attaching a design citation that does not actually decide
>   the point.
> - Do not treat a milestone's `Exit:` line as authoritative over the design's own required-test list; the
>   plan says as much itself.
> - Do not count missing required tests as milestone failure without checking whether the plan already assigns
>   them to a later milestone. Several sections are shared between milestones.
> - Do not propose changes to any file under `docs/captureAndReplay/`. Only the owner authorizes those.
>
> `NO_ACTIONABLE_FINDINGS` is a complete and acceptable answer.

---

## Invoking it

Per `AGENTS.md` §3.2b. The repository directory is already trusted; Midway is sufficient and AWS credentials
are not needed. Allow up to 30 minutes and poll at least every 60 seconds.

Append the diff range, the milestone, its `Design refs:` line, and a note to read the already-dispositioned
verdicts in the register first — otherwise a later pass re-raises a claim an earlier one already settled.

```bash
claude -p "$(cat tools/review-prompt-design-conformance.md)
Diff under review: <range>
Milestone: <G-n, name>
Design refs: <the milestone's Design refs: line>
Prior passes are dispositioned in docs/replayerRebuildStatus.md; read those verdicts first and do not re-raise
a claim recorded as NOT REAL without new evidence." \
  --permission-prompts none \
  --no-session-persistence \
  --output-format text \
  --tools 'Read,Grep,Glob,Bash' \
  --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git status *),Bash(git show *),Bash(git log *)' \
  < /dev/null
```

`< /dev/null` matters: without it the CLI waits on stdin and emits a warning before proceeding.

Do not use `--permission-mode plan`, `--restricted`, or `--safe-mode` here.

## Termination

Repeat until a pass returns **no unfixed Class A findings**. Any production change re-opens the review,
including one made to fix a finding — which is the case that motivated repeating at all. Class B findings are
triaged once and do not gate closure.
