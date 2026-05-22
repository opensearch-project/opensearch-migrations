# AGENTS — `cmd/tui/`

This is the only file outside the tooling-enforced safety net. Review this
file paranoidly. Three rules.

## Rule 1 — `main` is dumb on purpose

`main` does five things, in this order:

  1. `log.Setup()` — file-only slog with truncate-on-launch.
  2. `app.New(cfg)` — composition root that wires the Workspace into the
     root model.
  3. `tea.NewProgram(model, ...).Run()` — block until the model returns
     `tea.Quit`.
  4. Inspect the returned model for a `Handoff` value.
  5. If `Handoff != nil`: run `PreExec`, `os.Chdir(workdir)`, then
     `syscall.Exec(bin, argv, os.Environ())`.

That is the entire program. No business logic in `main`.

## Rule 2 — `fmt.Print*` is allowed here, but only after Run() returns

The alt-screen is gone by the time `tea.Program.Run()` returns. Prints are
safe. *Before* `Run()`, prints to stdout race the alt-screen alloc and
corrupt the first frame. Be careful.

The lint rule `forbidigo.no-fmt-print` excludes this file. Do not extend
that exception to other files.

## Rule 3 — `Version` and `MAVersion` are populated by `-ldflags`

```go
var (
    Version    = "dev"
    MAVersion  = "0.0.0"
)
```

The Makefile injects real values:

```
-ldflags '-X main.Version=$(git describe) -X main.MAVersion=$(cat MA_VERSION)'
```

Tests override these via `t.Setenv` + a `setVersionsForTest` helper rather
than mutating the package vars directly. See PLAN §11 + DESIGN Adjustment D.

## syscall.Exec contract

  - `bin` MUST be an absolute path. `cmd/tui/lookpath.go` resolves it before
    handoff. If lookup fails, the handoff is rejected *before* `tea.Quit`,
    so the user is never stranded with no shell.
  - `argv` MUST start with `bin` (Go convention; differs from `os.Exec`).
  - `os.Environ()` is passed verbatim. Do not strip variables. Do not add
    surprise environment.

A regression test (`cmd/tui/handoff_test.go`) asserts that handoff to a
nonexistent binary fails *before* the TUI quits.
