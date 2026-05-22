package keys_test

import (
	"testing"

	"charm.land/bubbles/v2/key"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/keys"
)

// TestGlobal_AllBindingsHaveHelp asserts every Global binding has a
// non-empty help label — the help line is the user's only discovery
// surface for shortcuts, so a missing label is a UX bug.
func TestGlobal_AllBindingsHaveHelp(t *testing.T) {
	g := keys.Global
	bindings := []struct {
		name string
		b    key.Binding
	}{
		{"Quit", g.Quit},
		{"Back", g.Back},
		{"Next", g.Next},
		{"Help", g.Help},
		{"Tab", g.Tab},
		{"Enter", g.Enter},
		{"Up", g.Up},
		{"Down", g.Down},
	}
	for _, tc := range bindings {
		h := tc.b.Help()
		require.NotEmpty(t, h.Key, "%s missing key label", tc.name)
		require.NotEmpty(t, h.Desc, "%s missing description", tc.name)
	}
}

// TestGlobal_ShortHelp_HasMinimumBindings — the always-on help row
// must show at least Back/Next/Quit/Help. Pages may add their own
// row above this.
func TestGlobal_ShortHelp_HasMinimumBindings(t *testing.T) {
	short := keys.Global.ShortHelp()
	require.GreaterOrEqual(t, len(short), 4,
		"ShortHelp must include at least Back/Next/Quit/Help")
}

// TestGlobal_FullHelp_GroupsBindings — full help is multi-row;
// row 0 is navigation, row 1 is global actions.
func TestGlobal_FullHelp_GroupsBindings(t *testing.T) {
	full := keys.Global.FullHelp()
	require.GreaterOrEqual(t, len(full), 2,
		"FullHelp must group bindings into at least 2 rows")
}

// TestQuit_BoundToCtrlC — Ctrl+C is the universally-expected quit
// in CLI tools; we keep it bound at every page.
func TestQuit_BoundToCtrlC(t *testing.T) {
	require.Contains(t, keys.Global.Quit.Keys(), "ctrl+c",
		"Quit must accept ctrl+c at every page")
}

// TestNext_AllowsTabAndEnter — both Tab and Enter advance the wizard.
func TestNext_AllowsTabAndEnter(t *testing.T) {
	keysAccepted := keys.Global.Next.Keys()
	require.Contains(t, keysAccepted, "tab")
	require.Contains(t, keysAccepted, "enter")
}

// TestBack_BoundToEscOrShiftTab — esc is the conventional cancel,
// shift+tab the conventional reverse-advance.
func TestBack_BoundToEscOrShiftTab(t *testing.T) {
	keysAccepted := keys.Global.Back.Keys()
	require.Contains(t, keysAccepted, "esc")
	require.Contains(t, keysAccepted, "shift+tab")
}

// TestHelp_BoundToQuestionMark — `?` is the conventional help key
// in interactive CLIs (less, k9s, lazygit, charm-stack tools).
func TestHelp_BoundToQuestionMark(t *testing.T) {
	require.Contains(t, keys.Global.Help.Keys(), "?")
}
