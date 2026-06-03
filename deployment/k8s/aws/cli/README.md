# migrate-cli

> ⚠️ **PREVIEW — opt-in only.** The production deploy path remains
> `aws-bootstrap.sh` (curl-pipe from the GitHub release). This CLI is
> being baked through CI for a few releases before being promoted. You
> can opt in two ways:
>
> 1. **Locally**: install via the curl-pipe below and run
>    `migration-assistant`. State is per-project; nothing leaks into
>    your global env.
> 2. **Through Jenkins**: leave `USE_RELEASE_BOOTSTRAP=false` (the
>    default for source-checkout pipeline runs). Jenkins runs this CLI
>    as the deploy backend, which is how the team gathers stability
>    coverage. Setting `USE_RELEASE_BOOTSTRAP=true` falls back to the
>    released `aws-bootstrap.sh` for that run.
>
> Production releases ship BOTH `aws-bootstrap.sh` (the stable production
> artifact) AND `install.sh` + `migration-assistant-cli-<ver>.tar.gz`
> (the preview CLI artifact) so operators can choose.

A bash-native CLI for the OpenSearch Migration Assistant. Replaces the
14k-line Go TUI proposed in
[opensearch-project/opensearch-migrations#3008](https://github.com/opensearch-project/opensearch-migrations/pull/3008)
with a single-binary install of pure shell scripts.

## Install

```sh
curl -fsSL https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/install.sh | bash
```

Default (`path` mode): installs a versioned tree at
`~/.opensearch-migrate/cli/<version>/` and symlinks
`~/.local/bin/migration-assistant` onto your PATH. Nothing is dropped in
the current directory and the CLI is **not** auto-launched — you then
`cd` into the directory you want to use as your migration project and run
`migration-assistant` there.

Workspace mode (project-local, auto-launches the deploy flow):

```sh
curl -fsSL .../install.sh | MIGRATE_INSTALL=workspace bash
```

This unpacks under `./migration-assistant-workspace/`, writes an
`activate` script, and launches the CLI.

## Use

```sh
cd ~/my-solr-migration                 # this dir becomes the project
migration-assistant                    # default: resume (or start) for stage 'default'
                                        #   state -> ./migration-assistant-workspace/default/
migration-assistant --stage staging    # named stage
migration-assistant --switch           # re-prompt Manual / Agent
migration-assistant cleanup            # tear down + archive
migration-assistant help
```

Per-project state lives in `./migration-assistant-workspace/<stage>/`
under the directory you run from. Set `MIGRATE_HOME` to share one state
root across projects (e.g. the old global `~/.opensearch-migrate`).

## What it does

Discovery → wizard → CFN deploy (Create-VPC EKS template) → optional crane
image mirror → helm install → either `kubectl exec migration-console-0`
(Manual mode) **or** replaces itself with `claude|q|kiro|…` (Agent mode) and
installs a `Startup.md` skill the agent reads first.

State lives at `./migration-assistant-workspace/<stage>/` under the
directory you run from (override with `MIGRATE_HOME`) and survives
terminal restarts. Resume from any step; switch modes any time.

## Layout

```
bin/migration-assistant  dispatcher
lib/_common.sh       shell hardening + traps
lib/ui.sh            color/prompt/spinner
lib/log.sh           append-only log + rotation
lib/state.sh         state.env + state.json I/O
lib/discover.sh      OS / pkg-mgr / AWS / EKS / CFN
lib/install_tools.sh kubectl/helm/crane/aws/jq install
lib/artifacts.sh     SHA-256-pinned download + cache
lib/wizard.sh        4-field deploy params
lib/cfn.sh           CFN deploy + event tail
lib/crane.sh         image mirror loop
lib/helm.sh          helm install + readiness wait
lib/console.sh       kubectl exec migration-console-0
lib/agent.sh         agent discover/setup/exec
lib/cleanup.sh       teardown
lib/resume.sh        controller + mode select
lib/version.sh       version constants
skills/Startup.md    the agent's first read
test/                bats-core tests
```

## Develop

```sh
make lint       # shellcheck
make test       # bats-core
make install    # install from CURDIR via install.sh
```

## License

Apache-2.0 (matches opensearch-project).
