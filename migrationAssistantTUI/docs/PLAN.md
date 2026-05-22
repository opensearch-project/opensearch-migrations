# PLAN — Package responsibilities & dependency order

This is the implementation plan that drives the package layout under
`internal/`. Pages and feature subpackages are independently testable; the
dependency arrows below are deliberately one-way.

## Dependency direction

```
cmd/tui  ──▶  internal/app  ──▶  internal/ui  ──▶  internal/ui/msg
                                           │
                                           └─▶  internal/feature/* (via per-feature interfaces)
                                                  │
                                                  └─▶  internal/launch, internal/marelease,
                                                        internal/workdir, internal/log,
                                                        internal/pubsub, internal/versioncheck,
                                                        internal/handoffbrief, internal/skillkit
```

`internal/app` is the *only* place where a concrete `internal/feature/*`
implementation is wired into a UI interface. UI pages declare narrow
per-feature interface dependencies; tests construct fakes per feature.

## Build order (for TDD)

Each row is "implement RED → GREEN → REFACTOR before moving on." Earlier rows
must not import later rows.

  1. `internal/log`            — file-only slog writer; truncate on launch.
  2. `internal/pubsub`         — Broker, Subscribe/Unsubscribe, Publish.
  3. `internal/config`         — env-var parsing, build-time constant accessors.
  4. `internal/workdir`        — workdir naming, atomic write helpers, state file.
  5. `internal/marelease`      — SHA-256 pinned artifact resolver + CAS cache (Adjustment B).
  6. `internal/versioncheck`   — semver compare; reads MAVersion from build flag.
  7. `internal/launch`         — workspace prep state machine; uses workdir + marelease.
  8. `internal/feature`        — top-level feature interface aggregate (Adjustment C).
  9. `internal/feature/aws`    — STS/CFN/EC2/EKS read-only callers.
 10. `internal/feature/artifacts` — release-asset listing (delegates to marelease for fetch).
 11. `internal/feature/agents`    — claude/kiro/etc. detection.
 12. `internal/feature/tools`     — kubectl/helm/aws presence + version.
 13. `internal/feature/deploy`    — Driver iface, RealDriver, FakeDriver (Adjustment E).
 14. `internal/handoffbrief`      — generate HANDOFF.md from wizard state.
 15. `internal/skillkit`          — skill-bundle template assembly.
 16. `internal/ui/msg`            — cross-cutting messages.
 17. `internal/ui/keys`           — key bindings.
 18. `internal/ui/styles`         — lipgloss style sheet.
 19. `internal/ui/common`         — shared sub-widgets (header bar, status line, banner).
 20. `internal/ui/dialog`         — modal dialog stack.
 21. `internal/ui/workspace`      — aggregate Workspace interface used by the root model.
 22. `internal/ui/pages/welcome`  — first page, smallest, used as the test pattern reference.
 23. `internal/ui/pages/intent`
 24. `internal/ui/pages/wizard`
 25. `internal/ui/pages/review`
 26. `internal/ui/pages/deploy`   — the most-bug-prone page; uses `tea.Sub` (Adjustment A).
 27. `internal/ui/pages/handoff`
 28. `internal/ui/`               — root Model + page state machine.
 29. `internal/app`               — composition root.
 30. `cmd/tui`                    — main, syscall.Exec handoff, ldflags Version/MAVersion.
 31. `internal/testutil`          — teatest harness shared across page tests (built incrementally as pages need it).

## §11 — Test layout

### §11.1 — Unit tests live next to the code

Every `*.go` file with non-trivial logic has a `*_test.go` sibling. Tests use
`testing` + `github.com/stretchr/testify` for ergonomic asserts.

### §11.2 — Page tests use teatest

Page tests construct a model with a fake Workspace (per-feature stubs only),
drive it via `teatest.NewTestModel`, and assert against a golden snapshot.
Golden files live under `internal/ui/pages/<page>/testdata/*.golden`.

### §11.3 — Testdata budget ≤ 1 MB

The combined size of all `testdata/` directories under `internal/` MUST stay
under 1 MB. `make testdata-budget` runs in CI. If a new test needs more than
the budget, the test should be re-shaped to fixture-build inline rather than
ship a large blob.

### §11.4 — Race detector + 2m timeout

`make test` runs with `-race -timeout=2m -count=1`. The 2m timeout catches
goroutine leaks; `-race` catches the channel/goroutine bugs that the broker
pattern is designed to prevent.

### §11.5 — Deploy phase has its own deterministic test layer

Adjustment E ships `feature/deploy.NewFakeDriver(events, finalErr)`. This is
the layer that tests "what does the deploy page render when helm fails after
CFN succeeds?" without spinning up real AWS or real helm. The FakeDriver is
the test boundary for the deploy phase.

## §6.4 — Top-level recover

`cmd/tui/main.go` installs a deferred recover that:

  1. Restores the terminal (alt-screen off, cursor visible).
  2. Writes the panic + stack to the file log.
  3. Re-raises with `panic(r)` so the user sees the trace.

A CI fixture (`cmd/tui/panic_recover_test.go`) injects a panic before
`tea.Program.Run()` returns and asserts the terminal-restore sequence was
emitted.

## §11.6 — Goroutine leak guard

A package-level `TestMain` in `internal/ui` and `internal/feature/deploy`
captures the goroutine count at startup and asserts at teardown that no
more than 2 lingering goroutines exist (the Go runtime baseline). Prevents
the most common "I forgot to close the channel" bug.
