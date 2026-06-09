# migration-assistant bundle

Extracted at install time into `~/.opensearch-migration-assistant/` by
`migration-tui-install.sh` (POSIX) or `migration-tui-install.ps1` (Windows).

## Contents

```
~/.opensearch-migration-assistant/
├── README.md                              # this file
├── manifest.yaml                          # schema-v1 manifest of bundled artifacts
├── helm/
│   └── migrationAssistantWithArgo/        # Helm chart for the deployment
│       ├── Chart.yaml
│       ├── values.yaml
│       ├── valuesEks.yaml                 # EKS preset
│       ├── valuesForLocalK8s.yaml         # kind/minikube preset
│       ├── valuesForLocalK8sWithEnvSubst.yaml
│       └── templates/                     # helm templates
├── samples/                               # workflow samples (TUI ConfigStore)
│   ├── backfillOnly.wf.yaml
│   ├── basicSnapshotMigration.wf.yaml
│   ├── fullMigrationWithTraffic.wf.yaml
│   └── …
└── skills/                                # agent skills (Claude / Codex / Kiro)
    ├── Startup.md                         # entry-point bootstrap
    ├── manifest.json                      # skill registry
    ├── migration-assistant-cli-reference/SKILL.md
    ├── migration-assistant-operator/SKILL.md
    └── sops/                              # per-product SOPs
```

## How to use

### Helm chart (deployment)

```sh
cd ~/.opensearch-migration-assistant
# EKS:
helm install migration-assistant ./helm/migrationAssistantWithArgo \
    -f ./helm/migrationAssistantWithArgo/valuesEks.yaml
# Local kind/minikube:
helm install migration-assistant ./helm/migrationAssistantWithArgo \
    -f ./helm/migrationAssistantWithArgo/valuesForLocalK8s.yaml
```

The TUI itself doesn't `helm install` for you; it walks you through
choosing values and prints the exact command to run. The chart and its
values files are bundled here so you have them locally without a separate
`git clone` of the monorepo.

### Workflow samples

`migration-tui` discovers these via `ConfigStore::list_samples()`. They
appear in the wizard's "Start from sample" picker. Each `.wf.yaml` is a
self-contained Argo Workflow spec for one migration shape (backfill-only,
basic-snapshot, full-migration-with-traffic, …).

You can also feed them directly to the workflow tooling without the TUI:

```sh
argo submit ~/.opensearch-migration-assistant/samples/backfillOnly.wf.yaml
```

### Agent skills

The `skills/` tree is consumed by AI coding agents (Claude Code, OpenAI
Codex CLI, Kiro CLI). It contains the `migration-assistant-operator` SOP
(the runbook your agent should follow), the `migration-assistant-cli-reference`
(commands the agent should use), and per-product SOPs under `sops/`.

```sh
# Claude Code: point it at the skills tree
claude --skills ~/.opensearch-migration-assistant/skills

# Kiro CLI auto-loads when running from the directory with `.kiro/`;
# the agent-skills subproject also publishes a kiro-specific bundle.
```

`Startup.md` is the agent entry point — it tells the agent how to read
`manifest.json` and discover the available skills.

## Bundle provenance

The contents are staged at release time by Gradle from authoritative
monorepo locations (`deployment/k8s/charts/aggregates/migrationAssistantWithArgo/`
and `orchestrationSpecs/packages/config-processor/scripts/samples/`).
This directory in source control carries only this README — the rest is
generated. See `tui/release/build.gradle.kts` `stageBundle` task.
