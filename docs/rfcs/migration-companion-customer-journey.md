# OpenSearch Migration Companion: A Fully Autonomous Migration Experience

> RFC / Vision Document — March 2026
>
> This document describes the vision for **OpenSearch Migration Companion** — an AI-driven system that guides customers from initial assessment through final cutover with minimal manual intervention. The AI connects to clusters, analyzes schemas, generates transforms, builds playbooks, deploys infrastructure, executes workflows, reads telemetry, and adapts — all within a single continuous conversation. The customer remains in control through approval gates and playbook review, but the cognitive load shifts from the operator to the system.

---

## Product Overview

**OpenSearch Migration Companion** is the rebranded name for the migration toolkit formerly known as Migration Assistant. The rebrand reflects a fundamental shift in what the product is: not a passive set of tools waiting to be operated, but an active, intelligent partner that walks alongside the customer through their entire migration journey.

### What's in the Box

The Migration Companion is delivered as a **package** — a directory of tools, docs, steering configs, and an AI CLI that can be run from a container or cloned into a local workspace. It contains:

| Component | What It Does |
|-----------|-------------|
| **The Advisor** | AI-guided assessment — analyzes source clusters, identifies risks, generates transforms, produces migration plans |
| **Playbook Engine** | The existing workflow DSL (`orchestrationSpecs/`) — generates and executes declarative migration playbooks with approval gates. A "playbook" is a `workflow-config.yaml` file that the config-processor transforms into Argo workflows. |
| **Deployment Tooling** | Scripts and configs to bootstrap EKS + Argo |
| **Steering Docs** | AI personality, safety guardrails, migration best practices — configures any AI agent |
| **Knowledge Base** | Versioned docs, compatibility matrix, sizing calculator — ships with the release |
| **AI CLI** | Built-in Claude CLI (default). Customers using an IDE agent bring their own AI. |

### How to Run It

The Migration Companion runs in three modalities:

#### 1. Docker Container

```bash
docker run -it \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -v ~/.migration-companion:/workspace/state \
  migration-companion
```

Ships with a built-in Claude CLI. The customer converses in a terminal. State persists across sessions via mounted volume.

#### 2. AWS CloudShell

```bash
curl -sSL https://releases.opensearch.org/migration-companion/bootstrap.sh | bash
```

Bootstraps the companion tooling into CloudShell. AWS credentials are already present. AI CLI connects to Bedrock by default — no separate API key needed.

#### 3. IDE Agent (Cursor, VSCode + Cline, Kiro)

```bash
git clone https://github.com/opensearch-project/opensearch-migrations.git
cd opensearch-migrations
```

The repo IS the companion. The IDE's own AI agent (Cursor, Cline, Kiro, etc.) becomes the migration companion. Steering docs configure the agent's behavior. The customer brings their own AI — the package provides tools, knowledge, and workspace.

This is the most powerful modality: full IDE features, preferred AI agent, complete toolkit, and SDLC capabilities (mount app code, edit transforms, run tests).

### Two AI Models

| Modality | AI Source | Interface |
|----------|----------|-----------|
| Docker / CloudShell | **Built-in Claude CLI** | Conversation in a terminal |
| IDE Agent | **Bring your own** (Cursor, Cline, Kiro) | Agent-assisted workspace |

Both use the same steering docs, tools, and knowledge base. The difference is who provides the AI and how the customer interacts.

---

## The Three Phases

```
┌──────────────────────────────────────────────────────────────────────┐
│                    MIGRATION COMPANION                               │
│                    (container or cloned workspace)                   │
│                                                                      │
│  ┌──────────────┐    ┌───────────────┐    ┌───────────────────────┐  │
│  │  PHASE 1     │    │  PHASE 2      │    │  PHASE 3              │  │
│  │  Assessment  │───▶│  Deployment   │───▶│  Execution            │  │
│  │  (Advisor)   │    │  (EKS + Argo) │    │  (Playbooks + Argo)   │  │
│  └──────────────┘    └───────────────┘    └───────────┬───────────┘  │
│                                                       │              │
└───────────────────────────────────────────────────────┼──────────────┘
                                                        │
                           ┌────────────────────────────┘
                           ▼
              ┌──────────────────────────┐
              │  EKS CLUSTER             │
              │  ┌────────────────────┐  │
              │  │ Migration Console  │  │   ← runs metadata migrations,
              │  │ (inside K8s)       │  │     hosts console API
              │  │ • console_link API │  │     (driven by Companion)
              │  │ • workflow CLI     │  │
              │  └────────────────────┘  │
              │  ┌────────────────────┐  │
              │  │ Argo Workflows     │  │   ← orchestrates execution
              │  └────────────────────┘  │
              │  ┌────────────────────┐  │
              │  │ Migration Services │  │   ← RFS, Replayer, Capture
              │  └────────────────────┘  │
              │  ┌────────────────────┐  │
              │  │ OTEL → CloudWatch  │  │   ← observability
              │  │    &/or OpenSearch │  │
              │  └────────────────────┘  │
              └──────────────────────────┘
```

### Relationship Between Companion and Console

The **Migration Companion** (container or workspace) is where the customer lives. It's the entry point, the AI interface, and the management plane.

The **Migration Console** (K8s pod inside EKS) is where migration work happens. It runs metadata migrations, hosts the console_link API, and submits Argo workflows. In the autonomous model, customers should rarely need to log into the console directly — the Companion drives it via API.

**Manual operator mode:** Power users can still `kubectl exec` into the Migration Console for direct access to `console` and `workflow` commands, Kafka tools, etcd, etc. This is the escape hatch, not the primary path.

**Long-term vision:** As more operations move to Argo workflows triggered by playbooks, the need to log into the Migration Console diminishes. The Console becomes a backend service, not a user-facing shell.

---

## Phase 1: Assessment (The Advisor)

The Advisor is the customer's first interaction with Migration Companion. It runs entirely within the companion container/workspace — no EKS, no infrastructure required.

### What the Advisor Does

1. **Guided dialogue** — walks the customer through their migration scenario via steering docs
2. **Source cluster analysis** — connects to the source, pulls mappings/settings/stats, identifies risks
3. **Target cluster validation** — verifies the target is ready, has capacity, and is compatible
4. **Transform generation** — auto-generates transforms for detected issues (type mappings, deprecated APIs, analyzer changes)
5. **Transform validation** — tests transforms against the target in a tight SDLC loop
6. **Application code scanning** — customer mounts their app; AI identifies ES-specific patterns and generates patches
7. **Config generation** — produces a `workflow-config.yaml` and preliminary playbook

### Knowledge Base (3 Layers)

```
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 3: Migration Loop (deployed infrastructure)                  │
│  - End-to-end migration testing with deployed EKS + Argo            │
│  - AI-generated transforms tested against live target               │
│  - Playbook execution, telemetry feedback, approval gates           │
│  - Customer's application code mounted for query rewrite testing    │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 2: Source Connection & Query Translation                     │
│  - Source cluster analysis (mappings, versions, risks)              │
│  - Target cluster validation (health, capacity, compatibility)      │
│  - Shim-like query translation between source and target DSLs      │
│  - Generated configs, playbooks, and migration profiles             │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 1: Static Analysis                                           │
│  - Steering docs: AI personality, guardrails, safety boundaries     │
│  - Migration best practices, approval tier definitions              │
│  - Product docs, tool guides, workflow docs (versioned with release)│
│  - Compatibility matrix, sizing calculator, breaking changes        │
│  - Works with built-in CLI AND IDE agents (same docs)               │
└─────────────────────────────────────────────────────────────────────┘
```

### Phase 1 Exit

The customer has: a migration profile, a `workflow-config.yaml`, validated transforms, a deployment plan, and confidence.

---

## Phase 2: Deployment

The Companion orchestrates EKS deployment from within the container/workspace. The AI generates pre-filled deployment scripts from the assessment.

### What Happens

1. AI generates deployment configs from `workflow-config.yaml`
2. AI generates or executes `aws-bootstrap.sh` with pre-filled params
3. EKS cluster + Argo + Migration Console + OTEL collector come online
4. Workflow config is injected as a K8s ConfigMap
5. State hands off: local → S3 → K8s ConfigMaps/etcd
6. Customer continues in Phase 3 without context loss

---

## Phase 3: Execution (Playbooks + Argo)

No web UI. The Companion drives execution through **playbooks** — declarative YAML config files that the AI generates and the customer reviews.

A playbook is a `workflow-config.yaml` file — the same format already used by the `orchestrationSpecs/` config-processor pipeline. The config-processor validates the playbook against the Zod schema (`OVERALL_MIGRATION_CONFIG`), transforms it into Argo workflow parameters, and submits it. There is no separate "playbook engine" — the playbook **is** the workflow config.

### How It Works

1. AI generates a playbook (`.wf.yaml`) from the assessment — cluster configs, snapshot stages, metadata transforms, backfill settings, replay config
2. Customer reviews, edits if needed, approves
3. Companion submits the playbook to the config-processor (`createMigrationWorkflowFromUserConfiguration.sh`)
4. Config-processor validates, transforms, and submits Argo workflows
5. Argo orchestrates: snapshot → metadata → RFS backfill → replay
6. OTEL emits telemetry to CloudWatch / OpenSearch
7. AI reads telemetry, advises on progress, pauses at approval gates

### Playbook Example

The following playbook uses the actual workflow DSL from `orchestrationSpecs/`. It expresses the same production migration scenario — test run on a single index, then full backfill, then live traffic replay — using the real schema that the config-processor already understands.

```yaml
# full-migration-prod.wf.yaml
# A playbook is a workflow config — same DSL, customer-facing name.
{
  skipApprovals: false,

  sourceClusters: {
    "prod-es": {
      endpoint: "https://es-prod.internal:9200",
      allowInsecure: false,
      version: "ES 7.10",
      authConfig: {
        sigv4: { region: "us-east-1" }
      },
      snapshotRepos: {
        "migration-repo": {
          awsRegion: "us-east-1",
          s3RepoPathUri: "s3://my-migration-bucket-123456789012/snapshots"
        }
      },
      proxy: { }
    }
  },

  targetClusters: {
    "prod-os": {
      endpoint: "https://os-prod.internal:9200",
      allowInsecure: false,
      authConfig: {
        sigv4: { region: "us-east-1" }
      }
    }
  },

  migrationConfigs: [
    # ── Stage 1: Test run — single index, manual approvals ──
    {
      fromSource: "prod-es",
      toTarget: "prod-os",
      skipApprovals: false,

      snapshotExtractAndLoadConfigs: [
        {
          label: "testRun",
          snapshotConfig: {
            repoName: "migration-repo",
            snapshotNameConfig: {
              snapshotNamePrefix: "test-run"
            }
          },
          createSnapshotConfig: {
            indexAllowlist: ["access-logs-2024.01"]
          },
          migrations: [
            {
              label: "testRunValidation",
              metadataMigrationConfig: {
                indexAllowlist: ["access-logs-2024.01"],
                multiTypeBehavior: "SPLIT",
                skipEvaluateApproval: false,
                skipMigrateApproval: false
              },
              documentBackfillConfig: {
                indexAllowlist: ["access-logs-2024.01"],
                maxConnections: 2,
                documentsPerBulkRequest: 1000,
                skipApproval: false
              }
            }
          ]
        }
      ]
    },

    # ── Stage 2: Bulk backfill + live traffic capture/replay ──
    # Capture proxy and replayer run concurrently with the backfill.
    # The proxy captures live traffic from the start, the replayer
    # catches up after backfill completes, then stays in sync.
    {
      fromSource: "prod-es",
      toTarget: "prod-os",
      skipApprovals: false,

      snapshotExtractAndLoadConfigs: [
        {
          label: "bulkBackfill",
          snapshotConfig: {
            repoName: "migration-repo",
            snapshotNameConfig: {
              snapshotNamePrefix: "bulk-backfill"
            }
          },
          createSnapshotConfig: { },
          migrations: [
            {
              label: "everything",
              metadataMigrationConfig: {
                multiTypeBehavior: "SPLIT",
                skipEvaluateApproval: true,
                skipMigrateApproval: true
              },
              documentBackfillConfig: {
                maxConnections: 10,
                podReplicas: 5,
                documentsPerBulkRequest: 1000,
                skipApproval: false
              }
            }
          ]
        }
      ],

      # Replay runs alongside backfill — not after it
      replayerConfig: {
        podReplicas: 2,
        speedupFactor: 2.0
      }
    }
  ]
}
```

> **Note:** Stage sequencing (test-run completes before bulk-backfill) is handled by the config-processor — it processes `migrationConfigs` entries in order. Approval gates (`skipApproval: false`) pause execution for human review. Cutover (traffic switching) is an operational step the AI proposes after replay validation succeeds — it is not yet part of the workflow DSL.

### Observability

**CloudWatch** (default) — pre-built dashboards, alarms, CloudWatch Insights queries. The AI constructs and runs these on the customer's behalf.

**OpenSearch Observability Cluster** (optional) — for deep migration telemetry, long-term retention, and complex queries.

The AI is the dashboard. Customers ask questions; the AI queries telemetry and explains results.

### AI Safety Tiers

| Tier | What the AI Can Do | Examples |
|------|-------------------|----------|
| **Observe** | Read-only, no approval needed | Check status, query metrics, count docs |
| **Propose** | Suggest actions, explain impact | "I recommend scaling to 8 workers" |
| **Execute** | Act with customer approval or within approved playbook | Start backfill, submit workflow, scale workers |

---

## Naming Convention

| Component | Name |
|-----------|------|
| Product | **OpenSearch Migration Companion** |
| AWS Solution | **Migration Companion for Amazon OpenSearch Service** |
| Assessment AI | **The Advisor** (part of Migration Companion) |
| Entry point | **Migration Companion Console** (container) or **Migration Companion package** (cloned workspace) |
| K8s pod | **Migration Console** (stays as-is inside EKS — manual operator mode) |
| Playbook | A `.wf.yaml` file — the existing workflow config DSL from `orchestrationSpecs/` |
| Execution engine | **Argo Workflows** (unchanged) |
| CLI namespace | `companion` (or `mc`) for AI commands; `console` and `workflow` for direct commands |

---

## Implementation Roadmap

### Phase A: Migration Companion Container

1. Unify MigrationCompanionAdvisor + migration console into multi-stage Dockerfile
2. Built-in Claude CLI with Anthropic API + Bedrock support
3. CloudShell bootstrap script
4. IDE agent mode: steering docs that configure Cursor/Cline/Kiro
5. Local state persistence (SQLite in mounted volume)

### Phase B: Playbook Engine (extends existing workflow DSL)

The playbook engine already exists — it is the `orchestrationSpecs/` config-processor pipeline. Work here extends it for the companion UX:

1. Customer-facing playbook documentation and examples (`.wf.yaml` templates for common scenarios)
2. Approval gate enhancements — finer-grained `skipApproval` controls per stage, AI-readable approval status
3. Validation hooks — post-stage validation steps (doc count comparison, sample diff, index health checks)
4. Steering docs for AI safety tiers (observe / propose / execute)
5. Community playbook library — curated `.wf.yaml` files for snapshot-only, capture-replay, Solr, phased rollout

### Phase C: Deployment Bridge

1. Pre-filled `aws-bootstrap.sh` generator
2. State handoff: local → S3 → K8s ConfigMap
3. Post-deployment health checks

### Phase D: Observability Pipeline

1. CloudWatch dashboard templates (convert existing Grafana JSON)
2. CloudWatch alarms for migration thresholds
3. OTEL Collector config for optional OpenSearch observability cluster
4. AI CLI helpers for telemetry queries

### Phase E: Continuous Improvement

1. Community playbook library (snapshot-only, capture-replay, Solr, A/B test, rolling)
2. Migration confidence score
3. Post-migration monitoring and completion report
4. Migration replay archive for compliance
5. Multi-session support

---

## Summary

The Migration Companion is the customer's single entry point for their entire migration journey. It runs as a Docker container, bootstraps into CloudShell, or lives as a cloned workspace in an IDE. It ships with a built-in AI CLI (Claude) but works equally well with any IDE agent via steering docs.

The journey has three phases:

> **Assessment** — The Advisor connects to clusters, analyzes schemas, generates transforms, modernizes application code
>
> **Deployment** — The AI orchestrates EKS + Argo deployment from the companion container
>
> **Execution** — Playbooks drive Argo workflows; the AI reads CloudWatch/OpenSearch telemetry and guides the customer through approval gates to cutover

The Migration Console remains inside EKS for running metadata migrations and hosting the console API, but customers should rarely need to log into it directly. The Companion drives the Console. Manual operator mode provides an escape hatch for power users; the normal path is autonomous.

There is no web UI. There is no custom dashboard. The **AI is the interface**. The **playbook is the artifact**. **CloudWatch and OpenSearch are the observability layer**. The customer never leaves the conversation.
