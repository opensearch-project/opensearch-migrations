---
name: migration-assistant-operator
description: Operate the OpenSearch Migration Assistant on a deployed EKS install. Use after the migration-assistant CLI handed off into migration-console-0. Drive migrations declaratively via `workflow`; use `console clusters` for ad-hoc connectivity checks, index inspection, and curl-style probes against source/target between phases. Agent-agnostic — works with claude, codex, and kiro.
---

# Migration Assistant Operator

This skill picks up where the `migration-assistant` deploy CLI ends.
Once the chart is installed and `migration-console-0` is Running, you
drive the migration from inside that pod:

```bash
kubectl exec -n ma -it migration-console-0 -- /bin/bash
```

## Read these in order

The skill is split into five files. Read this SKILL.md for the orientation
you have now, then read the other four for the content:

- **[`product.md`](product.md)** — what the Migration Assistant product is
  and isn't. Read first when starting a new engagement so you know what's
  in/out of scope.
- **[`migration-prompt.md`](migration-prompt.md)** — the intake structure.
  What to ask the operator before doing anything (source / target / auth /
  goals / approvals).
- **[`workflow.md`](workflow.md)** — the full Workflow CLI reference and
  safety checklist. Configure / submit / status / output / show / manage /
  approve / reset commands; pre-migration estimation; cluster check;
  debug-connection-issues recipes; snapshot-cleanup ordering. **This is
  the file you reference most.**
- **[`deployment.md`](deployment.md)** — chart deploy flow, image
  resolution, NodePool wiring. Mostly historical context (the deploy CLI
  handles this), but useful when troubleshooting an install that the CLI
  couldn't recover.

Read **`workflow.md`** before suggesting any operating command. It is
the source of truth for both what to run and what NOT to run without
explicit operator confirmation (FGAC changes, security-group edits,
IAM policy changes, S3 bucket policy changes, snapshot deletes).

## When to reach for which CLI

Two CLIs live on the migration-console pod:

- **`workflow`** — modern, declarative, Argo-orchestrated. The
  workflow owns ordering, gates, retry, and reset for every phase
  (snapshot → metadata → backfill → replay → cutover). This is how
  you drive a migration end-to-end. Full surface in
  [`workflow.md`](workflow.md).

- **`console clusters`** — connectivity + inspection. Use this to
  confirm what's true on either cluster between workflow phases:

  | Command | Use |
  |---|---|
  | `console clusters connection-check` | Source + target answer? Auth wired? Versions? |
  | `console -v clusters connection-check` | Same, verbose: full request/response |
  | `console clusters cat-indices` | `_cat/indices?v` against both, side-by-side |
  | `console clusters curl source <PATH>` | Curl source with auth (e.g. `/`, `/_cluster/health`, `/_cat/templates?v`) |
  | `console clusters curl target <PATH>` | Same against target |
  | `console clusters clear-indices --cluster target` | DESTRUCTIVE: drop indices |
  | `console clusters run-test-benchmarks` | Load OSB workloads on a basic-auth source |

  Typical post-phase probes:
  ```bash
  # After deploy — confirm chart wired source + target.
  console clusters connection-check

  # After workflow snapshot — confirm source's indices.
  console clusters cat-indices

  # After workflow metadata — confirm templates landed on target.
  console clusters curl target_cluster '/_cat/templates?v'

  # After workflow backfill — compare doc counts.
  console clusters cat-indices --refresh

  # After workflow replay — spot-check a doc.
  console clusters curl target_cluster '/<index>/_doc/<id>'
  ```

## What NOT to use

The legacy per-stage `console` subcommands —
`console snapshot create`, `console metadata migrate`,
`console backfill start`, `console replayer start`,
`console kafka …` — exist on the pod for backwards compatibility, but
when the chart is workflow-managed (the default since MA 3.x), running
those manually fights the workflow:

- They write to resources the workflow owns. Next `workflow status`
  call sees inconsistent state.
- They don't trip the workflow's approval gates, so safety guardrails
  are bypassed.
- `workflow reset` won't clean up after them cleanly.

If you find yourself reaching for `console <stage> <action>`, stop and
either submit a workflow that includes that stage, or use
`workflow reset` to fall back to a clean state and re-submit.

## Authoritative external docs

- [Workflow CLI Overview](https://github.com/opensearch-project/opensearch-migrations/wiki/Workflow-CLI-Overview)
- [Workflow CLI Getting Started](https://github.com/opensearch-project/opensearch-migrations/wiki/Workflow-CLI-Getting-Started)
- [Backfill Workflow](https://github.com/opensearch-project/opensearch-migrations/wiki/Backfill-Workflow)
- [Capture and Replay Workflow](https://github.com/opensearch-project/opensearch-migrations/wiki/Capture-and-Replay-Workflow)
- [Migration console pod](https://github.com/opensearch-project/opensearch-migrations/wiki/Migration-console)
