# Migration Companion

A minimalist, agent-runnable skill that walks a user end-to-end
through migrating an **Elasticsearch** or **OpenSearch** cluster to
**OpenSearch** (self-managed), **Amazon OpenSearch Service** (AOS),
or **Amazon OpenSearch Serverless** (AOSS).

The skill adapts to the user's starting point — from "I know nothing
yet" to "here are credentials, do it" — and points the user at this
repository's existing Migration Assistant tooling (helm chart,
docker-compose dev sandboxes, `migration-console` CLI) for execution.
It does **not** ship its own infrastructure.

## How an agent uses this

Point any SKILL.md-aware runner (Claude Code, Kiro, the AIAdvisor
loader, a custom skill loader, etc.) at this directory. The runner
reads `SKILL.md` and follows its phase map.

## Layout

```
migration-companion/
├── SKILL.md                          # Entry point. Start here.
├── README.md                         # This file.
├── pitfalls.md                       # Non-discoverable facts (P1..P18).
├── steering/
│   ├── 00-intent.md                  # Pick path: LEARN / POC / ANALYZE / MIGRATE.
│   ├── 01-interview.md               # Persona + source/target pair.
│   ├── 02-probe.md                   # Read source cluster (read-only).
│   ├── 03-evaluate.md                # Pros/cons across 7 approaches → pick one.
│   ├── 04-execute.md                 # Run via the in-repo MA tooling.
│   ├── 05-validate.md                # Doc count + sample query parity.
│   └── 06-report.md                  # Write report.md.
└── packs/                            # Loaded on demand by phases 2-5
    ├── source-elasticsearch.md
    ├── source-opensearch.md
    ├── target-self-managed.md
    ├── target-aws-managed.md
    ├── target-aws-serverless.md
    └── techniques.md                 # 7 migration approaches reference
```

## Four user paths

| Path     | Trigger                                    | Phases run        |
| -------- | ------------------------------------------ | ----------------- |
| LEARN    | "What's involved in migrating?"            | 0 → 1 → 6         |
| POC      | "Show me how it works locally"             | 0 → 1 → 3..6      |
| ANALYZE  | "Here's a snapshot, tell me what'll break" | 0 → 1 → 2 → 3 → 6 |
| MIGRATE  | "Here are creds, do the migration"         | 0 → 1 → 2..6      |

## Where execution happens

This skill is the *guide*. The infrastructure lives elsewhere in this
repo:

| Need                            | Path                                                        |
| ------------------------------- | ----------------------------------------------------------- |
| MA on a real / local k8s        | `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/` |
| Local docker-compose dev loop   | `TrafficCapture/dockerSolution/`                            |
| Operator CLI (`console …`)      | `migrationConsole/`                                         |
| Argo workflow templates         | `orchestrationSpecs/`                                       |
| Solr → OS (sibling skill)       | `AIAdvisor/skills/solr-opensearch-migration-advisor/`       |

## Design notes

- **Single SKILL.md entry point.** ~3.5 KB. Everything else is
  on-demand.
- **One question at a time.** No 5-question dumps in Phase 1.
- **Pull, don't push.** The 5 packs are not loaded until the
  source/target pair is known (Phase 2+).
- **Evaluate, don't prescribe.** Phase 3 lays out 7 approaches with
  honest pros/cons, then narrows to one. The user signs off.
- **Capability profiles, not parity claims.** Each target pack lists
  what works and what doesn't. AOSS's `snapshot_restore: false` is
  the load-bearing example — believe the pack, don't try to be
  clever.
- **One-pass validation.** Doc counts + 3-5 sample queries. No
  N-pass review framework.
- **No state file.** Working memory plus a final `report.md` is
  enough.
- **No bundled POC.** This skill does not duplicate
  `TrafficCapture/dockerSolution` or the helm chart. It points at
  them and walks the user through driving the `migration-console`
  CLI.

## Heritage

Combines a layered source/target packs idea with the stepwise
persona-tailored interview model from
`AIAdvisor/skills/solr-opensearch-migration-advisor/`, scoped to the
ES/OS → OS/AOS/AOSS domain. The Solr → OS workflow stays where it
already lives.
