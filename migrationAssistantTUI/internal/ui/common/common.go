// Package common holds the small shared types every wizard page
// imports. Two key items live here:
//
//   - Page — the interface every page implements. Pages return a
//     string from View() (not tea.View); the root Model wraps the
//     string in tea.NewView() once when satisfying tea.Model. This
//     keeps page-level tests trivial (compare strings, not struct
//     fields) and confines the bubbletea-v2 view-struct detail to
//     one place.
//
//   - Chrome / LayoutFromWindow — the helper that converts a raw
//     terminal size into a msg.LayoutMsg by subtracting header,
//     footer, and dialog overlays the root Model owns.
package common

import (
	tea "charm.land/bubbletea/v2"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
)

// Page is the interface every wizard page implements. It mirrors
// tea.Model except View() returns plain string.
type Page interface {
	// ID identifies the page (used by the root Model for routing
	// and by NavigateMsg). Stable across the page's lifetime.
	ID() msg.PageID

	// Init runs once when the page is created. Return any startup
	// commands (kicking off async preflights, focusing inputs, etc.).
	Init() tea.Cmd

	// Update handles one Msg and returns the (possibly mutated)
	// page plus an optional Cmd. Pages are typically pointer
	// receivers and return themselves.
	Update(tea.Msg) (Page, tea.Cmd)

	// View renders the page-content area as a styled string. The
	// string may contain ANSI escapes (lipgloss output is fine).
	// Returning empty string is allowed only when the page is
	// blurred or transitioning out.
	View() string

	// Focus is invoked when the page becomes the active page; it
	// may return a startup Cmd (e.g. focus a textinput).
	Focus() tea.Cmd

	// Blur is invoked when the page is being torn down or another
	// page becomes active. Pages should release resources (close
	// channels, cancel subs) here.
	Blur()
}

// Rect is a width-by-height pair in cells.
type Rect struct {
	Width  int
	Height int
}

// Chrome describes the row budget the root Model reserves for its
// own decoration: a fixed-height header, a fixed-height footer
// (always-on help), and an optional reserved-rows count for dialogs.
type Chrome struct {
	HeaderHeight int
	FooterHeight int
	Reserved     int // extra rows reserved (dialogs, status banners)
}

// LayoutFromWindow converts a raw terminal size into a LayoutMsg
// after subtracting chrome. ContentHeight is clamped to zero so
// pages never receive a negative dimension.
func LayoutFromWindow(termWidth, termHeight int, c Chrome) msg.LayoutMsg {
	h := termHeight - c.HeaderHeight - c.FooterHeight - c.Reserved
	return msg.LayoutMsg{
		ContentWidth:  ClampInt(termWidth, 0, termWidth),
		ContentHeight: ClampInt(h, 0, termHeight),
	}
}

// ClampInt returns v clamped to [lo, hi]. If lo > hi it returns lo.
func ClampInt(v, lo, hi int) int {
	if lo > hi {
		return lo
	}
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
