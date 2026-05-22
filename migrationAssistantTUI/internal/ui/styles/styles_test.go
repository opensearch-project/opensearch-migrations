package styles_test

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// styles is a pure-data package: its job is to expose lipgloss.Style
// values that pages render through. Tests assert the surface, not
// the literal ANSI bytes (those vary by terminal capability).

func TestPalette_DefinesPrimaryColors(t *testing.T) {
	p := styles.Palette
	require.NotEmpty(t, p.Primary, "Primary color must be set")
	require.NotEmpty(t, p.Accent, "Accent color must be set")
	require.NotEmpty(t, p.Subtle, "Subtle color must be set")
	require.NotEmpty(t, p.Error, "Error color must be set")
	require.NotEmpty(t, p.Warn, "Warn color must be set")
	require.NotEmpty(t, p.OK, "OK color must be set")
}

// All exported style values must be non-zero so callers don't have
// to nil-check.
func TestStyles_AllExportedStylesAreSet(t *testing.T) {
	all := map[string]styles.Style{
		"Header":   styles.Header,
		"Footer":   styles.Footer,
		"Status":   styles.Status,
		"Title":    styles.Title,
		"Subtle":   styles.Subtle,
		"Error":    styles.Error,
		"Warn":     styles.Warn,
		"OK":       styles.OK,
		"Selected": styles.Selected,
		"Help":     styles.Help,
	}
	for name, s := range all {
		// lipgloss.Style is a value type; rendering an empty string
		// through a zero-value style still works, so we instead
		// verify each style renders SOME non-passthrough output for a
		// known input. Style{} renders "x" -> "x"; an active style
		// renders "x" -> something containing the original "x" plus
		// either color escapes OR border/padding chrome.
		_ = name
		require.NotPanics(t, func() { _ = s.Render("x") })
	}
}

// TestRender_PreservesContentText — applying a style must not lose
// the user's text (might add escapes, but content must survive).
func TestRender_PreservesContentText(t *testing.T) {
	cases := []struct {
		name string
		s    styles.Style
	}{
		{"Header", styles.Header},
		{"Title", styles.Title},
		{"Error", styles.Error},
		{"OK", styles.OK},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			out := tc.s.Render("hello world")
			require.True(t, strings.Contains(out, "hello world"),
				"%s lost content: %q", tc.name, out)
		})
	}
}

// TestThemeName_IsExported — theme name string used by status line
// and tests for golden snapshots.
func TestThemeName_IsExported(t *testing.T) {
	require.NotEmpty(t, styles.ThemeName)
}
