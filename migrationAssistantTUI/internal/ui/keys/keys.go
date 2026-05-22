// Package keys defines the global key.Bindings used across every
// page of the wizard. Pages may add page-local bindings on top, but
// the Global set is invariant — Quit, Back, Next, Help, Tab, Enter,
// Up, Down — so the help line is consistent at every step.
//
// Global also implements help.KeyMap (ShortHelp / FullHelp) so the
// bubbles/help component can render it directly.
package keys

import "charm.land/bubbles/v2/key"

// KeyMap groups the always-on bindings. A package-level Global
// instance is the single source of truth; tests assert its shape.
type KeyMap struct {
	Quit  key.Binding
	Back  key.Binding
	Next  key.Binding
	Help  key.Binding
	Tab   key.Binding
	Enter key.Binding
	Up    key.Binding
	Down  key.Binding
}

// Global is the always-on key map. Page Update() handlers should
// match against these bindings via key.Matches before any
// page-local handling so Ctrl+C, Esc, ?, etc. behave identically
// at every page.
var Global = KeyMap{
	Quit: key.NewBinding(
		key.WithKeys("ctrl+c"),
		key.WithHelp("ctrl+c", "quit"),
	),
	Back: key.NewBinding(
		key.WithKeys("esc", "shift+tab"),
		key.WithHelp("esc/⇧tab", "back"),
	),
	Next: key.NewBinding(
		key.WithKeys("tab", "enter"),
		key.WithHelp("tab/enter", "next"),
	),
	Help: key.NewBinding(
		key.WithKeys("?"),
		key.WithHelp("?", "help"),
	),
	Tab: key.NewBinding(
		key.WithKeys("tab"),
		key.WithHelp("tab", "next field"),
	),
	Enter: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "confirm"),
	),
	Up: key.NewBinding(
		key.WithKeys("up", "k"),
		key.WithHelp("↑/k", "up"),
	),
	Down: key.NewBinding(
		key.WithKeys("down", "j"),
		key.WithHelp("↓/j", "down"),
	),
}

// ShortHelp returns the row of bindings shown in the always-on
// help line at the bottom of the screen.
func (m KeyMap) ShortHelp() []key.Binding {
	return []key.Binding{m.Back, m.Next, m.Help, m.Quit}
}

// FullHelp returns the multi-row layout shown when the user
// presses `?`. Row 0 is navigation, row 1 is global actions.
func (m KeyMap) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{m.Up, m.Down, m.Tab, m.Enter},
		{m.Back, m.Next, m.Help, m.Quit},
	}
}
