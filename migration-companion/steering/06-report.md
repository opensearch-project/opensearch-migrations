# Phase 6 — Report

**Goal:** Write `report.md` in the working directory. One file.
Plain markdown. No spec versioning, no claim-trace, no nested
`<details>` accordion forest.

Always runs (every path).

## Length target

- LEARN: ~1 page, planning-only.
- POC: ~1 page, "what we built and how to tear it down".
- ANALYZE: ~1-2 pages, gap analysis.
- MIGRATE: 1-2 pages, what happened + what to do next.

If you're past 2 pages on any path, you're padding.

## Sections (use these names verbatim)

1. **Verdict** — One sentence at the top. Examples:
   - "ES 7.17 → AOS 2.13 migrated cleanly, 12.4M docs, 100% count
     parity, top-10 query parity on 5/5 sample queries."
   - "ES 7.17 → AOSS migration is feasible via RFS. One blocker
     (custom analyzer X uses a plugin not on AOSS)."
   - "Local POC stood up successfully in 4m32s. Tear-down at the
     bottom."
2. **What we did** — bullets. What the user saw, in order.
3. **Findings** — if any. One row per finding. Severity (block /
   warn / note), what it is, what to do about it.
4. **Open items** — anything pending the user's decision.
5. **Cleanup / next steps** — if POC, the tear-down command. If
   MIGRATE, the cutover checklist (DNS, app config, monitoring).

## What goes in *Findings*

A finding is anything from Phase 2-5 the user needs to act on:
- An incompatibility from a target pack (e.g. AOSS doesn't support
  snapshot restore — restate it once, in plain English).
- A doc-count mismatch from Phase 5.
- A plugin on the source that has no target-side equivalent.
- A query whose top-10 differed from source.

Don't list things that "happen to be different" without consequence.
A different `_seq_no` is not a finding.

## What does NOT go in this report

- Version numbers of this skill.
- Self-praise about the rigor of the analysis.
- A re-statement of the plan from Phase 3 (cite it, don't repeat it).
- Empirical "we ran this on N=200K docs" claims unless you literally
  ran it on N=200K docs.

## Exit criteria

`report.md` exists, the user has read it, you've answered any
follow-up questions. Done.
