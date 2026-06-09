---
name: migration-assistant-cli-reference
description: Reference index for the `migration-assistant` deploy CLI's bash source — what each `lib/*.sh` module does, when to read which one, and how the deploy pipeline (CloudFormation → ECR mirror → Helm chart → kubectl exec) is wired. Load this when troubleshooting a failed `migration-assistant` invocation, or when the agent needs to know exactly what command the CLI will run before suggesting an operator wait or retry. The goal is "look up the CLI's actual behavior" — never to reimplement the deploy by hand. The CLI is idempotent and on PATH; if a deploy step needs to run, run `migration-assistant` itself.
---

# Migration Assistant CLI — bash source reference

The `migration-assistant` CLI is a modular bash program at
`deployment/k8s/aws/cli/` in the opensearch-migrations repo. Its
runtime layout (which lands on operator machines under
`~/.opensearch-migrate/cli/<version>/`):

```
bin/migration-assistant      # 80-line dispatcher: parses subcommand, sources lib/, calls cmd_*
lib/_common.sh               # shell hardening, EXIT/INT traps, on_signal_track_pid, die
lib/ui.sh                    # stderr-only UI: ui_step / ui_info / ui_ok / ui_warn / ui_err / ui_dim
                             # ui_prompt + ui_confirm (read /dev/tty, fall back to stdin)
                             # ui_select (numbered picker), ui_banner, ui_table
lib/log.sh                   # log file at $STAGE_DIR/log/migrate.log; log_stream tees stdout+stderr
                             #   from a long-running command into the log AND to operator stderr
                             # log_announce_exit dumps the last 30 ERROR/WARN + last 100 lines on
                             #   non-zero exit (visible in CI / Jenkins)
lib/state.sh                 # per-stage state.env (sourceable) + state.json (jq-canonical)
                             # state_get / state_set / state_load / state_save / state_archive
lib/version.sh               # CLI_VERSION baked at build time; ma_default_version() resolves
                             #   from MA_VERSION env → state cache → GH releases API
lib/discover.sh              # detect_os, detect_pkg_mgr, discover_aws (sts get-caller-identity),
                             # discover_resources (lists MigrationAssistant-* CFN stacks +
                             #   EKS clusters)
lib/install_tools.sh         # ensure_tool kubectl/helm/crane/aws/jq — auto-install via brew/apt/dnf
                             #   or static binary fallback (sha256-pinned)
lib/artifacts.sh             # SHA-pinned download + content-addressable cache at
                             #   $STAGE_DIR/artifacts/.cache/<sha>/<name>
                             # artifacts_fetch (release asset), artifacts_fetch_raw (raw repo file)
lib/wizard.sh                # 3 inputs: STAGE_NAME, MIRROR_IMAGES (Y/N), MA_VERSION
                             # adopt-existing-stack flow: if discover_resources found a
                             #   MigrationAssistant-* stack, offer to adopt it (skips CFN deploy)
lib/dashboard.sh             # generic sticky-panel TUI primitives (header + progress bar +
                             #   counts + capped row table). Used by cfn.sh, reusable for helm.
lib/cfn.sh                   # `aws cloudformation deploy` for the Migration-Assistant-Infra-*
                             #   templates (Create-VPC variant default; Import-VPC honored).
                             # tails describe-stack-events into the dashboard.
                             # cfn_outputs() reads the stack's MigrationsExportString blob and
                             #   re-emits it as flat KEY=VALUE pairs for downstream lookup.
lib/crane.sh                 # mirror images from public.ecr.aws + quay.io + etc. into the
                             #   stack-published private ECR. Image manifest comes from the
                             #   chart's privateEcrManifest.sh (vendored at assemble time;
                             #   raw.githubusercontent.com fallback for forks).
                             # _ecr_login refreshes both private + public.ecr.aws auth.
                             # _crane_copy_retry: 5 attempts, 5/10/20/40s exp backoff, bails
                             #   early on NAME_UNKNOWN / UNAUTHORIZED / MANIFEST_UNKNOWN.
                             # _crane_mirror_ma_images: 5 MA images → single repo with
                             #   migrations_<name>_<ver> disambiguating tags (chart contract).
lib/helm.sh                  # helm upgrade --install + watchers. Routes every kubectl/helm call
                             #   through ${HELM[@]} / ${KUBECTL[@]} arrays threading
                             #   --kube-context throughout. helm_recover_if_stuck handles the
                             #   pending-install / pending-upgrade / failed / uninstalling /
                             #   pending-rollback cases (with a [4] reconcile-in-place option).
                             # helm_watch_pods is a backgrounded poller emitting
                             #   STREAM[pods] lines on summary changes.
                             # helm_watch_installer_logs follows the chart's pre-install Job
                             #   pod logs ("`<release>-helm-installer`") so the per-sub-chart
                             #   install progress is visible during the otherwise-silent
                             #   --wait window. Dumps the Job pod's events when the pod stays
                             #   Pending past INSTALLER_LOG_PENDING_WARN_S=30.
                             # _helm_explain_failure (called from helm_recover_if_stuck) pulls
                             #   helm's description, names the failing Job, dumps its events
                             #   + pod logs (or, when GC'd, prints a re-run recipe).
                             # _helm_workloads_healthy: post-install probe — looks at
                             #   migration-console-0's phase + container readiness.
lib/console.sh               # exec into migration-console-0 (cmd_console subcommand AND
                             #   tail-end of resume flow). Translates 130/131/143 to 0
                             #   so Ctrl-C in the pod's shell doesn't surface as an error.
lib/agent.sh                 # discover_agents (claude / codex / kiro), agent_setup (drops
                             #   Startup.md + skills/ + agent-specific config), agent_exec
                             #   (replaces process). Also writes a project-scope
                             #   .claude/settings.json with safe-read-only permissions
                             #   pre-approved (so the operator isn't trust-prompted for every
                             #   `aws sts get-caller-identity`).
lib/cleanup.sh               # cmd_cleanup: helm uninstall + cfn delete-stack + state archive.
                             # cmd_clear: wipe local state + per-cwd agent session storage
                             #   (no AWS / k8s touched).
lib/diag.sh                  # cmd_diag: standalone "dump the same diagnostics helm_dump_*
                             #   emits on failure". For post-mortem after a successful deploy.
lib/resume.sh                # cmd_resume (the default subcommand): top-level controller.
                             # Two-pass flag parser (first pass = --stage only, then state_load,
                             #   then full parser, then state_save) — never clobbers loaded
                             #   state with an empty in-memory map.
                             # _select_mode: AI vs Manual picker (gated behind
                             #   MIGRATE_ENABLE_AGENT=1).
                             # manual_path: discover → ensure_tools → wizard → cfn → crane →
                             #   helm → console_exec. Fast-path skips the whole pipeline when
                             #   state shows last_step ∈ {ready, console_handoff,
                             #   agent_handoff} AND helm release status == deployed.
                             # cmd_help: the 80-line user-facing flag reference.
```

## When to read which file

| Symptom / question | File |
|---|---|
| "Did the CFN deploy succeed?" | `cfn.sh` (look at `cfn_deploy_or_skip` + `_cfn_stack_healthy`) |
| "Why did `aws ecr get-login-password` fail?" | `crane.sh` (`_ecr_login` surfaces stderr) |
| "What images is the chart expecting?" | `skills/privateEcrManifest.sh` (vendored alongside Startup.md), referenced by `crane.sh::_crane_load_manifest` |
| "Why is the helm install hanging?" | `helm.sh` (`helm_watch_installer_logs` + `_helm_inst_dump_pending_pod`) |
| "How do I recover from a stuck helm release?" | `helm.sh::helm_recover_if_stuck` (4 options including reconcile-in-place at `_helm_clear_stuck_revision`) |
| "What does `migration-assistant cleanup` actually do?" | `cleanup.sh` |
| "Where is per-stage state?" | `~/.opensearch-migrate/<stage>/state.env` + `state.json` (managed by `lib/state.sh`) |
| "What does the AWS MCP registration do?" | `agent.sh::_agent_install_aws_mcp` + `_agent_ensure_uvx` |

## Hard rules for the agent

- **Do not reimplement the deploy.** If the CLI is on PATH (it is — the
  install.sh symlinks it at `~/.local/bin/migration-assistant`), call
  it. The CLI is idempotent and resumes per-step, so re-invoking after
  a partial failure is the supported behavior.

- **Read source over guessing.** When troubleshooting a deploy failure,
  read the CLI source for the relevant step instead of asserting how
  it works from memory. The files are short (<300 lines each); a
  targeted `grep` or `Read` is much faster than re-deriving the logic.

- **Surface, don't bury.** If the CLI's `migrate.log` already names a
  failure (helm description, kubectl event, stack-event reason),
  quote it verbatim — don't paraphrase. The CLI's diagnostics are
  the authoritative source of truth.

- **Don't run mutating CLI subcommands without confirmation.** The
  `migration-assistant cleanup` command deletes the CFN stack and the
  helm release; `migration-assistant clear` wipes local state and the
  claude session; both are destructive and require explicit operator
  approval.

## Where to find the source

- Repo: `deployment/k8s/aws/cli/` in
  `opensearch-project/opensearch-migrations` — a Rust crate. The
  `src/` modules ARE the spec (e.g. `src/cli.rs` is the dispatcher,
  `src/app.rs` the deploy orchestrator, `src/state.rs` the state store).
- Release tarball: `~/.opensearch-migrate/cli/<version>/` — ships the
  prebuilt `bin/migration-assistant-bin` plus `skills/` and a root
  `manifest.json`.

If a behavior in this skill's index disagrees with the actual source,
**the source wins** — this index is best-effort guidance; tags drift.
