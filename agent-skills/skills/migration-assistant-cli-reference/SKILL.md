---
name: migration-assistant-cli-reference
description: Reference index for the `migration-assistant` deploy CLI's Amber source — what each `src/*.ab` module does, when to read which one, and how the deploy pipeline (CloudFormation → ECR mirror → Helm chart → kubectl exec) is wired. Load this when troubleshooting a failed `migration-assistant` invocation, or when the agent needs to know exactly what command the CLI will run before suggesting an operator wait or retry. The goal is "look up the CLI's actual behavior" — never to reimplement the deploy by hand. The CLI is idempotent and on PATH; if a deploy step needs to run, run `migration-assistant` itself.
---

# Migration Assistant CLI — Amber source reference

The `migration-assistant` CLI lives at `deployment/k8s/aws/cli/` in the
opensearch-migrations repo. It is written in **[Amber](https://amber-lang.com/)**
— typed source (`src/*.ab`) that **compiles to portable Bash**. `amber build`
inlines every imported module into ONE self-contained Bash entrypoint, so the
artifact operators run (under `~/.opensearch-migrate/cli/<version>/bin/migration-assistant`)
is plain Bash 3.2+ with no Amber dependency. To read behavior, read the `.ab`
source — it is the spec; the compiled bash is generated.

Source layout (`deployment/k8s/aws/cli/src/`):

```
main.ab            # entrypoint: subcommand dispatch (compiles to bin/migration-assistant)
install.ab         # the curl-pipe installer (compiles to install.sh); path + workspace modes

# --- terminal UI layer (VT100; no tput/ncurses) ---
core.ab            # ESC-byte minting, stderr emit (eprint/eprintln), TTY detection
term.ab            # cursor/wrap/panel/progress/hyperlink/spinner; UTF-8 codepoint width
ui.ab              # stderr-only chrome: ui_step/info/ok/warn/err/dim, ui_prompt/confirm/select/banner/table
dashboard.ab       # sticky-panel TUI: in-memory resource table (parallel arrays) + progress bar + counts;
                   #   mode-dispatched classifier (cfn_status_class). Used by cfn_deploy; reusable for helm.
timeline.ab        # vertical phase checklist (●done ◐last ○pending) for the resume picker

# --- foundation ---
std.ab             # pure helpers: trim/split/join/regex_capture/path_join/parse_flag/retry, is_macos/linux
common.ab          # die, require_cmd, on_exit/signal-track (pkill registry), arch_os, stage_dir
state.ab           # per-stage state.env + state.json; state_get/set/load/save/archive (parallel-array store)
log.ab             # $STAGE_DIR/log/migrate.log; log_info/warn/error, log_stream (synchronous tee),
                   #   log_announce_exit dumps ERROR/WARN + tail on non-zero exit (visible in CI/Jenkins)
version.ab         # cli_version() (CLI_VERSION env or baked default); ma_default_version() resolves
                   #   from MA_VERSION env → state cache → GH releases API (fetch, short timeout)
artifacts.ab       # SHA-pinned download + content-addressable cache; artifacts_fetch / _fetch_raw
discover.ab        # discover_os (uname_*), discover_aws (sts get-caller-identity),
                   #   discover_resources (MigrationAssistant-* CFN stacks + EKS clusters)
manifest.ab        # parse manifest.json: branding, modes, mcpServers, skills (jq-backed)

# --- deploy + orchestration ---
wizard.ab          # collect AWS_REGION/STAGE_NAME/MIRROR_IMAGES/MA_VERSION; adopt-existing-stack flow
cfn_outputs.ab     # parse `describe-stacks` outputs + the MigrationsExportString blob → flat KEY=VALUE
cfn_deploy.ab      # `aws cloudformation deploy` (Create-VPC default; Import-VPC honored); foreground
                   #   describe-stack-events poll loop → dashboard. _cfn_import_vpc_endpoint_params.
crane.ab           # mirror public images → stack-published private ECR (src/dst parallel arrays);
                   #   retry w/ exp backoff, bails early on NAME_UNKNOWN/DENIED/MANIFEST_UNKNOWN
build.ab           # optional in-cluster image build (buildkit/docker) for --build
helm_ctx.ab        # kubeconfig + context binding. helm/kubectl context flags stored in state
                   #   (helm_ctx_flags()/kubectl_ctx_flags()), threaded through every call.
helm_recover.ab    # stuck-release recovery: pending-install/upgrade/failed/uninstalling/rollback;
                   #   orphan-job cleanup; namespace settle; _helm_release_status
helm_diag.ab       # synchronous post-hoc diagnostics: explain failure, dump failed Job events+logs,
                   #   install notes, pending-pod dump (replaces the bash backgrounded watchers)
helm_install.ab    # helm upgrade --install --wait; values file (built + file_write); public vs
                   #   mirrored image flags; tls flags; disable-general-purpose-pool
console.ab         # exec into migration-console-0 (cmd_console + tail of resume); also cmd_diag.
                   #   Maps 130/131/143 → 0 so Ctrl-C in the pod shell isn't an error.
agent.ab           # discover_agents (claude/codex/kiro), agent_setup (Startup.md + skills/ + config),
                   #   mcp_install_from_manifest (per-agent MCP writers), agent_exec (process handoff)
cleanup.ab         # cmd_cleanup (helm uninstall + cfn delete-stack + state archive; term_panel preview);
                   #   cmd_clear (wipe local state + per-cwd agent session storage; no AWS/k8s)
pack.ab            # cmd_pack: repackage the CLI with custom skills/MCP/branding from a manifest.json
resume.ab          # cmd_resume (default subcommand): top-level controller.
                   #   Two-pass flag parser (--stage first → state_load → full parse → save).
                   #   _select_mode (AI vs Manual, gated behind MIGRATE_ENABLE_AGENT=1).
                   #   manual_path: discover → tools → wizard → cfn_deploy → kubeconfig → build →
                   #     crane → helm → console_exec. Fast-path skips the pipeline when last_step ∈
                   #     {ready, console_handoff, agent_handoff} AND helm release == deployed.
                   #   cmd_help: the user-facing flag reference.
```

## When to read which file

| Symptom / question | File |
|---|---|
| "Did the CFN deploy succeed?" | `cfn_deploy.ab` (`cfn_deploy_or_skip` + `_cfn_stack_healthy`) |
| "Why did `aws ecr get-login-password` fail?" | `crane.ab` (ECR login surfaces stderr) |
| "What images is the chart expecting?" | `skills/privateEcrManifest.sh` (vendored at build time), read by `crane.ab` |
| "Why is the helm install hanging?" | `helm_install.ab` + `helm_diag.ab` (post-hoc pending-pod dump) |
| "How do I recover from a stuck helm release?" | `helm_recover.ab` (`helm_recover_if_stuck`, reconcile-in-place at `_helm_clear_stuck_revision`) |
| "What does `migration-assistant cleanup` actually do?" | `cleanup.ab` |
| "Where is per-stage state?" | `~/.opensearch-migrate/<stage>/state.env` + `state.json` (managed by `state.ab`) |
| "What does the AWS MCP registration do?" | `agent.ab` (`mcp_install_from_manifest`, uvx ensure) |

## Hard rules for the agent

- **Do not reimplement the deploy.** If the CLI is on PATH (it is — install.sh
  symlinks it at `~/.local/bin/migration-assistant`), call it. The CLI is
  idempotent and resumes per-step, so re-invoking after a partial failure is the
  supported behavior.

- **Read source over guessing.** When troubleshooting, read the relevant `.ab`
  module instead of asserting from memory. Modules are short; a targeted `grep`
  or `Read` beats re-deriving the logic. (The compiled bash is generated and
  harder to read — prefer the `.ab` source.)

- **Surface, don't bury.** If `migrate.log` already names a failure (helm
  description, kubectl event, stack-event reason), quote it verbatim.

- **Don't run mutating subcommands without confirmation.** `cleanup` deletes the
  CFN stack + helm release; `clear` wipes local state + the agent session. Both
  are destructive and require explicit operator approval.

## Where to find the source

- Repo (source of truth): `deployment/k8s/aws/cli/src/*.ab` in
  `opensearch-project/opensearch-migrations`.
- Release tarball (compiled): `~/.opensearch-migrate/cli/<version>/bin/migration-assistant`
  — one self-contained Bash file (every module inlined; no `lib/`).

If a behavior in this index disagrees with the actual source, **the source
wins** — this index is best-effort guidance; tags drift.
