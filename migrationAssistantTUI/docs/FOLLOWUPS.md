# MA-TUI follow-ups (post-greenfield)

Defects discovered during greenfield development that we intentionally
deferred so they didn't grow the initial PR. None of these block the
draft PR; each gets its own narrow follow-up PR after greenfield merges.

## 1. wizard textinputs ignore LayoutMsg width

`internal/ui/pages/wizard/wizard.go:138-140` stores `LayoutMsg.ContentWidth`
on `p.width` but never propagates it to the underlying
`charm.land/bubbles/v2/textinput.Model` instances in `p.inputs`. As a
result `wizard_default.golden` shows 1-char placeholder rendering
(`> h`, `> e`, `> 7`...) — interactive use renders correctly only
because the root model paints over the page area.

**Fix**: in the `case msg.LayoutMsg:` branch, loop over `p.inputs` and
call `.SetWidth(p.width - margin)` on each. Add a wizard test that
resizes to 100x30 and asserts each input renders ≥ 5 chars of its
configured placeholder. Then `go test ./internal/ui/golden -run
TestGolden -update` and review the wizard_default.golden diff.

## 2. main.go does not issue tea.EnterAltScreen

`cmd/tui/main.go` documents that v2 dropped `tea.WithAltScreen()` and
that the root model is responsible for issuing `tea.EnterAltScreen` from
`Init`. The root model in `internal/ui/ui.go` does not currently do
this. Effect: the TUI runs inline rather than in alt-screen, so
quitting leaves rendered frames in scrollback.

**Fix**: in `internal/ui/ui.go` `Model.Init()`, prepend
`tea.EnterAltScreen` to whatever cmd is already returned. Verify by
running the binary and confirming the screen clears on quit.

## 3. wizard padding/spacing review

The current wizard golden shows two-space indentation and blank-line
separators between fields. Once #1 lands and inputs render at full
width, the visual rhythm may need tightening. Capture before/after
goldens during the #1 PR and decide whether to adjust spacing.
