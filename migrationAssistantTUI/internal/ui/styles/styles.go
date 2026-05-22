// Package styles centralizes the lipgloss palette and styles used
// across every page of the wizard. Pages MUST render through the
// exported Style values rather than calling lipgloss.NewStyle()
// inline, so a single edit re-themes the whole TUI.
//
// The package re-exports lipgloss.Style as styles.Style so callers
// don't need to import lipgloss directly for typical usage.
package styles

import "charm.land/lipgloss/v2"

// Style is the same value type as lipgloss.Style, re-exported so
// pages can declare style fields without an extra import.
type Style = lipgloss.Style

// ThemeName identifies the active theme. Used by the status line
// and golden tests so a theme switch is visible in snapshots.
const ThemeName = "ma-default"

// PaletteSpec holds the named colors the rest of the package
// composes into Style values. Strings are ANSI-256 / hex inputs to
// lipgloss.Color, kept as strings so tests can assert non-empty.
type PaletteSpec struct {
	Primary string
	Accent  string
	Subtle  string
	Error   string
	Warn    string
	OK      string
	Bg      string
	Fg      string
}

// Palette is the active palette. Single source of truth for theme.
//
// Colors are chosen to render acceptably on both 16-color and
// truecolor terminals; lipgloss falls back automatically.
var Palette = PaletteSpec{
	Primary: "#7D56F4", // OpenSearch purple
	Accent:  "#00D7AF", // sea-green
	Subtle:  "#7C7C7C",
	Error:   "#FF5F87", // pink-red
	Warn:    "#FFAF00", // amber
	OK:      "#5FAF5F", // green
	Bg:      "#1A1A1A",
	Fg:      "#E4E4E4",
}

// ----------------------------------------------------------------------------
// Exported styles — pages render through these only.
// ----------------------------------------------------------------------------

// Header — the page-title row at the top of the screen.
var Header = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Bg)).
	Background(lipgloss.Color(Palette.Primary)).
	Bold(true).
	Padding(0, 1)

// Footer — the always-on help line.
var Footer = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Subtle)).
	Padding(0, 1)

// Status — the right-aligned status line (theme name, MA version,
// active workdir).
var Status = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Subtle))

// Title — section titles inside a page.
var Title = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Primary)).
	Bold(true)

// Subtle — de-emphasized helper text.
var Subtle = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Subtle))

// Error — error toasts and failure rows in the deploy log.
var Error = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Error)).
	Bold(true)

// Warn — warning rows.
var Warn = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Warn))

// OK — success rows, the deploy "phase done" lines.
var OK = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.OK))

// Selected — the highlighted item in lists and pickers.
var Selected = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Bg)).
	Background(lipgloss.Color(Palette.Accent)).
	Bold(true)

// Help — the bubbles/help component renders through this.
var Help = lipgloss.NewStyle().
	Foreground(lipgloss.Color(Palette.Subtle))
