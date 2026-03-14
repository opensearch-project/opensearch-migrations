# AGENTS.md - Agent Guidance for `opensearch-migrations`

This repo mixes product code, deployment tooling, and workflow specs for the OpenSearch Migration Assistant. If you are an agent working here, optimize for correctness and operator safety over speed.

## What to Read First

Choose the narrowest relevant docs before editing:

- Root overview: `README.md`
- Developer/build layout: `DEVELOPER_GUIDE.md`
- Migration console UX and commands: `migrationConsole/README.md`
- Workflow concepts: `docs/MigrationAsAWorkflow.md`
- Argo workflow generation and schema: `orchestrationSpecs/README.md`
- Kiro agent steering: `kiro-cli/README.md` and `kiro-cli/kiro-cli-config/steering/*.md`

## High-Signal Repo Areas

- `orchestrationSpecs/`: source of truth for workflow templates and workflow config/schema generation
- `migrationConsole/`: user-facing CLI for migration operations
- `deployment/`: install/bootstrap paths for local, K8s, and AWS deployments
- `kiro-cli/`: AI-assistant prompts and steering docs
- `docs/`: architecture and workflow intent

## Guardrails

- Treat live-cluster changes as high risk. Prefer documenting or proposing commands over silently changing destructive behavior.
- Do not weaken migration safety checks, approval gates, or validation steps without explicit justification.
- Preserve the distinction between:
  - `console` commands: cluster/tool operations
  - `workflow` commands: workflow-based orchestration
- Avoid introducing docs that imply unsupported features. Check `README.md` and current workflow/schema files first.
- When editing workflow docs, keep them aligned with the actual config schema and scripts under `orchestrationSpecs/packages/`.

## Agent Guidance Improvements Worth Preferring

When updating agent-facing docs in this repo, bias toward:

1. Safer operator defaults
   - confirm target state before migration
   - estimate time/throughput before submit
   - verify doc counts after completion
2. Clear source-of-truth pointers
   - point from steering docs to schema/workflow docs rather than duplicating drifting examples
3. Fewer hidden assumptions
   - call out namespace assumptions (`ma`), console pod naming, generated workflow names, and approval behavior
4. Concrete commands over vague advice
   - especially for workflow config generation, template rendering, and migration status checks

## Useful Local Validation

From repo root:

```bash
./gradlew test
./gradlew build
```

For orchestration specs:

```bash
cd orchestrationSpecs
npm run type-check
npm run make-templates -- --createResources false --show
npm run make-sample
```

Prefer validating only the touched surface when making doc/spec edits.

## Known Documentation Drift Risks

- Some agent steering lives in `kiro-cli/kiro-cli-config/steering/` while broader product docs live in root/docs/wiki.
- Workflow examples can drift from the generated schema. If examples disagree with `orchestrationSpecs/packages/schemas/` or config-processor scripts, update the docs.
- Historical docs sometimes describe aspirational workflow UX. Distinguish clearly between current behavior and future direction.
