# AGENTS — `internal/ui/`

This subtree renders the TUI. Three rules.

## Rule 1 — No I/O. Ever.

You cannot `import "net/http"`, `os/exec`, `database/sql`, `github.com/aws/aws-sdk-go-v2`,
or anything that talks to the outside world. The lint rule `tui-no-io` will
reject the build before the test run starts.

If you need an external value (an AWS account ID, a kubectl version, a
release SHA), you depend on a per-feature interface from `internal/feature/*`
and the aggregate `Workspace` injected at construction time.

## Rule 2 — No `fmt.Print*`

The alt-screen owns stdout while `tea.Program.Run()` is executing. Any print
corrupts the rendering and is nearly impossible to triage from a screenshot.
Use the file logger via `internal/log` for diagnostics.

If you genuinely need to surface something to the user, route it as one of:

  - `msg.ErrorMsg{Sev: ...}` — a modal or toast.
  - A status-line update (via `msg.StatusMsg`).
  - The handoff brief written to disk.

## Rule 3 — `LayoutMsg`, not `WindowSizeMsg`

The root model owns the chrome (header, status line). Pages receive a
`msg.LayoutMsg` with the *page-content* dimensions. Do not subtract chrome
height yourself; do not handle `tea.WindowSizeMsg` outside the root model.

## Page contract

Every page is a `tea.Model` with these properties:

  - `Init() tea.Cmd` returns either `nil` or a single command that fetches
    the data the page needs from the workspace.
  - `Update(msg tea.Msg) (tea.Model, tea.Cmd)` switches on the small set of
    messages declared in `internal/ui/msg`.
  - `View() string` is a pure function of the page's state. No I/O, no time,
    no random — these are inputs to the model.

## Test contract

Page tests live next to the page in `<page>_test.go` and use `teatest`. They:

  1. Construct a fake of the per-feature interface(s) the page declares.
  2. Wrap it in a thin Workspace stub.
  3. Drive the page via `teatest.NewTestModel` + key sends.
  4. Assert against `testdata/<page>_<state>.golden`.

If a page needs to test against AWS, you have the wrong test boundary — push
the test down to `internal/feature/aws_test.go`.
