# migration-assistant CLI

The OpenSearch Migration Assistant deployment CLI — a guided, resumable flow
that provisions the CloudFormation stack + EKS cluster, mirrors images, installs
the helm chart, and hands off to the migration console (or an AI agent).

## Written in Amber, ships as Bash

The CLI is written in **[Amber](https://amber-lang.com/)** — typed source that
**compiles to portable Bash**. The source of truth is `src/*.ab`; the build
compiles the entrypoint to a single self-contained Bash script that runs on
**Bash 3.2+** (the macOS / minimal-distro floor) with no Amber dependency on the
operator's host.

```
src/        Amber modules (*.ab) — the CLI source of truth
  main.ab     entrypoint: subcommand dispatch (the compiled bin/migration-assistant)
  install.ab  the curl-pipe installer (compiled install.sh)
  core/term/ui/dashboard/timeline   terminal UI layer (VT100, no tput/ncurses)
  std/common/state/log/...          foundation
  cfn_deploy/crane/build/helm_*/... deploy + orchestration
  resume.ab                         the top-level controller
test/       Amber test suite (amber test) + verify-bash32.sh (runs compiled
            output under a real bash 3.2 interpreter) + fixtures/
Makefile    check / build / test / verify targets
```

`amber build` inlines every imported module into ONE Bash file, so the compiled
`migration-assistant` is fully standalone — there is no runtime `lib/` to source.

## Build & test (requires the Amber toolchain)

```bash
# Install Amber 0.6 once:
brew install amber-lang/amber/amber-lang
#   or: bash <(curl -sL https://github.com/amber-lang/amber/releases/download/0.6.0-alpha/install.sh)

make check     # parse + type-check every module (amber check)
make test      # run the amber test suite (one `amber test` per file — the race-safe gate)
make build     # compile every module to bash-3.2 under build/
make verify    # compile to bash-3.2 AND run the compiled output under bash 3.2.57
make all       # check + build + test + verify
```

The release tarball + curl-pipe `install.sh` are produced by Gradle:

```bash
./gradlew :deployment:k8s:aws:packageMigrationAssistantCli -PcliVersion=<ver>
#   → build/migrate-cli-dist/migration-assistant-cli-<ver>.tar.gz
#   → build/migrate-cli-dist/install.sh
```

## Install (operators)

```bash
curl -fsSL https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/install.sh | bash
# then, from your migration project directory:
migration-assistant
```

`MIGRATE_INSTALL=workspace` switches to a project-local install that writes an
`activate` script and auto-launches. See `src/install.ab` for all env overrides.

## Subcommands

`migration-assistant [flags]` (default: resume/deploy) · `console` · `agent` ·
`diag` · `clear` · `cleanup` · `pack` · `version` · `help`.

## Extending without forking (`pack`)

`migration-assistant pack` repackages the CLI with custom skills, MCP servers,
and branding declared in a `manifest.json`. Skills are auto-discovered from the
bundled `skills/` tree; the agent layer registers MCP servers per agent
(claude/codex/kiro). See `src/manifest.ab` + `src/pack.ab`.

## Notes for contributors

`AMBER_IDIOMS.md` captures the compiler-verified Amber 0.6 patterns + gotchas
this codebase relies on (reserved keywords, module-array typing, the ESC-byte
TUI technique, the test-runner cold-cache flake, …). `MODULE_APIS.md` lists every
module's public API. Read both before adding a module.
