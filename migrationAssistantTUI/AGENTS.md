# AGENTS — `migrationAssistantTUI/`

You are editing the migration-assistant TUI. Read these three rules before
making any change, then read the subtree-specific AGENTS.md if one exists in
the file you're editing.

## Rule 1 — The lint rules say no for a reason

`.golangci.yml` enforces three load-bearing rules:

  - `depguard.tui-no-io`     — `internal/ui/**` cannot import I/O packages.
  - `depguard.no-charm-v1`   — only `charm.land/...` v2 imports compile.
  - `forbidigo.no-fmt-print` — `fmt.Print*` is forbidden under `internal/ui/**`.

If `make lint` rejects your change, your design is wrong. **Do not weaken the
lint rules** to make your code pass. If you genuinely need a new exception,
document it in the commit message and reference the design rationale.

## Rule 2 — Use the per-feature interface, not the aggregate

Pages depend on the narrowest possible interface (`AgentDetector`,
`ToolDetector`, `DeployDriver`, …) — not the aggregate `Workspace`. Tests
construct a fake of just that interface. If you find yourself implementing a
12-method fake to test a single feature, you have the wrong interface
shape; split it.

## Rule 3 — Backend → UI goes through the broker

`internal/pubsub.Broker` is the only sanctioned path from a backend goroutine
into the bubbletea Update loop. Do not call `tea.Program.Send` from outside
the program. Do not start a goroutine that captures `*tea.Program`. The
broker pattern + `tea.Sub` is the answer.

## Workflow

  1. Read `docs/UX.md` (locked operational rules).
  2. Read `docs/PLAN.md` (package responsibilities).
  3. RED → GREEN → REFACTOR. Tests first. If you wrote code before the test,
     delete the code and start again. (See `test-driven-development` skill.)
  4. `make lint && make test` before every commit.
  5. Commit messages reference the rule or section number that justifies the
     change (`UX §0.7`, `DESIGN §2.R5`, `PLAN §11.3`).

## When the design doc disagrees with the code

The design doc wins. Open a PR that updates the code; do not silently update
the doc to match drift.
