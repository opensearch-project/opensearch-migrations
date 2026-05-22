# migration-assistant TUI

A terminal-UI front door for opensearch-migrations. Walks an operator from
"I have an account number and I want to migrate" to a kubectl-shell-against-
the-deployed-Migration-Assistant — without ever asking them to copy-paste a
shell script.

This binary is a peer of the other capability subdirectories in this repo
(`kiro-cli/`, `migrationConsole/`, `RFS/`, `MetadataMigration/`, …). It does
not replace them; it deploys, drives, and hands off to them.

## Status

Greenfield. Not yet wired into the upstream release machinery. Builds and
tests pass locally; CI is added in `.github/workflows/migration-assistant-tui.yml`.

## Build

```
make             # lint + vet + test + build
make build       # static binary at bin/migration-assistant
make test        # go test -race -timeout=2m
make lint        # golangci-lint v2 (depguard + forbidigo + importas + ...)
```

Build flags are pinned for reproducibility: `CGO_ENABLED=0`, `-trimpath`,
`-ldflags '-s -w -X main.Version=$(git describe) -X main.MAVersion=$(cat MA_VERSION)'`.

## Architecture in 60 seconds

```
cmd/tui/main.go             ← only place fmt.Print* and os.Exit are allowed
   |
   v
internal/app                 ← composition root (wires Workspace → root Model)
   |
   +─ internal/ui            ← NO I/O. Pages, dialogs, root state machine.
   |
   +─ internal/feature/...   ← AWS, agents, tools, artifacts, deploy driver.
   |                           Each is a narrow per-feature interface.
   |
   +─ internal/pubsub        ← the ONLY sanctioned backend → UI message channel.
```

## The six load-bearing rules (mechanical)

These are enforced by tooling, not by reviewer discipline. Removing or
weakening any of them is a design-level change and should reference this list
in the commit message.

  R1. **Workspace-façade I/O isolation.** All side effects live behind the
      per-feature interfaces in `internal/feature/...`. The UI package is
      forbidden from importing `net/http`, `os/exec`, `database/sql`, or the
      AWS SDK. Enforced by `depguard.tui-no-io` in `.golangci.yml`.

  R2. **Charm v1 hard-banned.** Only `charm.land/...` v2 imports compile.
      Enforced by `depguard.no-charm-v1`.

  R3. **No `fmt.Print*` in TUI mode.** Stray prints corrupt alt-screen
      rendering. Enforced by `forbidigo`. Allowed only in `cmd/tui/main.go`
      (which prints AFTER tea.Program returns) and `internal/log`.

  R4. **Pubsub broker is the sole backend → UI channel.** Backend goroutines
      publish to `internal/pubsub.Broker`; the UI consumes via `tea.Sub`. There
      is no other pattern. Enforced by code review + the broker being the only
      package whose imports cross the UI/feature boundary.

  R5. **TUI dies before exec.** When the UI emits `HandoffMsg`, the program
      runs `tea.Quit`, control returns to `cmd/tui/main.go`, and `main`
      performs `syscall.Exec(bin, argv, os.Environ())`. The TUI process is
      *replaced*. The user is never inside the TUI when they exit the kubectl
      shell or the agent CLI.

  R6. **`LayoutMsg`, not `WindowSizeMsg`, propagates.** Pages receive a custom
      `msg.LayoutMsg` containing page-content dimensions (after subtracting
      the root's chrome). Pages do not subtract header/status-line height
      themselves.

## Five adjustments baked in from day one

These are the production-cut changes the design doc identified, applied
during the initial scaffolding rather than retrofitted:

  A. `tea.Sub` instead of goroutine + `deployStreamCmd` pump.
  B. SHA-256 pinned release artifacts + content-addressable cache.
  C. Per-feature interfaces (`AWSService`, `AgentDetector`, `ToolDetector`,
     `ArtifactSource`, `DeployDriver`), aggregated by a thin `Workspace`.
  D. Build-time `MAVersion` injection via `-ldflags`.
  E. `feature/deploy.NewFakeDriver(events []PhaseEvent, finalErr error)` for
     deterministic deploy-phase testing.

## Subdirectory map

| Path                                | Responsibility |
| ----------------------------------- | -------------- |
| `cmd/tui/`                          | Process lifecycle, `tea.Program.Run()`, syscall.Exec handoff |
| `internal/app/`                     | Composition root: builds Workspace, wires it into root Model |
| `internal/config/`                  | Build-time constants, env-var parsing |
| `internal/log/`                     | File-only `slog` writer |
| `internal/pubsub/`                  | Broker, fan-out subscription |
| `internal/launch/`                  | Workspace prep state machine, FetchArtifacts |
| `internal/workdir/`                 | Workdir naming, atomic file writes, state file |
| `internal/marelease/`               | SHA-256 pinned release fetcher (Adjustment B) |
| `internal/versioncheck/`            | Semver compare; reads MAVersion |
| `internal/handoffbrief/`            | Generates HANDOFF.md from wizard state |
| `internal/skillkit/`                | Skill-bundle template assembly |
| `internal/feature/aws/`             | STS/CFN/EC2/EKS read paths |
| `internal/feature/artifacts/`       | Release-asset resolution |
| `internal/feature/agents/`          | claude/kiro/etc. detection |
| `internal/feature/tools/`           | kubectl/helm/aws presence |
| `internal/feature/deploy/`          | Phase orchestration + Driver iface (real & fake) |
| `internal/ui/`                      | Root Model, page state machine, dialog stack |
| `internal/ui/msg/`                  | Cross-cutting messages (LayoutMsg, NavigateMsg, ErrorMsg, …) |
| `internal/ui/keys/`                 | Key bindings (one source of truth) |
| `internal/ui/styles/`               | Lipgloss style sheet |
| `internal/ui/common/`               | Shared sub-widgets used by ≥2 pages |
| `internal/ui/dialog/`               | Modal dialog stack |
| `internal/ui/workspace/`            | UI-facing aggregate Workspace interface |
| `internal/ui/pages/{welcome,intent,wizard,review,deploy,handoff}/` | One page each |
| `internal/testutil/`                | teatest harness, golden helpers |
| `docs/`                             | UX.md, PLAN.md (locked operational rules) |

## Where the design comes from

See `docs/PLAN.md` for the package-level plan and `docs/UX.md` for the locked
user-experience invariants. The architectural rationale lives in this repo's
commit history (the foundation commit references the recommendation document).
