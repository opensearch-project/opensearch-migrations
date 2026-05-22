// Package teatest provides a thin harness around common.Page for
// table-driven page tests. It removes three categories of boilerplate:
//
//  1. Building tea.KeyPressMsg payloads from runes / named key codes.
//  2. Draining a tea.Cmd into the message it produces (returning that
//     message to the caller for type-asserting).
//  3. Resizing the page (LayoutMsg) before View() is asserted on.
//
// It is intentionally NOT a full-program harness — it does NOT spin
// up tea.NewProgram. Use it for unit tests of individual pages. End-
// to-end tests of the integrated Model + Program live in golden tests
// (f19) and CI smoke tests.
//
// # Why not charm.land/x/exp/teatest
//
// Charm v2 has no published teatest equivalent yet (as of v2.0.6).
// Even when it ships, that harness is built around byte-stream
// terminal emulation, which is overkill for page-level tests where
// we just want to feed messages and inspect View() output.
//
// # Usage
//
//	func TestMyPage_Enter(t *testing.T) {
//	    p := mypage.New(mypage.Config{...})
//	    h := teatest.New(t, p)
//	    out := h.PressKey(tea.KeyEnter)
//	    nav, ok := out.(msg.NavigateMsg)
//	    require.True(t, ok)
//	    require.Equal(t, msg.PageOther, nav.To)
//	}
package teatest

import (
	"regexp"
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
)

// Default page-content size used by View() when the caller does not
// call Resize first. Sized to comfortably fit every page without
// triggering "(too small)" fallbacks.
const (
	DefaultWidth  = 100
	DefaultHeight = 30
)

// Harness wraps a common.Page for testing. The zero value is invalid;
// always construct via New.
type Harness struct {
	t    testing.TB
	page common.Page

	resized bool // tracks whether the caller explicitly Resize'd
	w, h    int
}

// New constructs a Harness around p. The page is NOT initialized
// here — call Init() (or any other helper) to drain the start cmd.
//
// The returned Harness owns the *common.Page reference; tests should
// not mutate the page directly between harness calls (it would
// desynchronize the harness's internal layout state from the page's).
func New(t testing.TB, p common.Page) *Harness {
	t.Helper()
	if p == nil {
		t.Fatal("teatest.New: page is nil")
	}
	return &Harness{
		t:    t,
		page: p,
		w:    DefaultWidth,
		h:    DefaultHeight,
	}
}

// Page returns the wrapped page. Useful for assertions on page
// internal state after a sequence of harness calls.
func (h *Harness) Page() common.Page { return h.page }

// Init drains the page's start cmd (Page.Init()) and returns the
// resulting tea.Msg, or nil if the cmd is nil or returns nil. This
// matches Bubble Tea's "a Cmd is a func() Msg" contract.
func (h *Harness) Init() tea.Msg {
	h.t.Helper()
	cmd := h.page.Init()
	return drain(cmd)
}

// Send delivers an arbitrary tea.Msg to the page and returns whatever
// the resulting cmd produced (or nil). Useful for non-key messages
// (LayoutMsg, BrokerEnvelopeMsg, custom feature messages).
func (h *Harness) Send(m tea.Msg) tea.Msg {
	h.t.Helper()
	newPage, cmd := h.page.Update(m)
	h.page = newPage
	return drain(cmd)
}

// PressRune builds a tea.KeyPressMsg from a single rune (printable
// character) and delivers it via Send.
func (h *Harness) PressRune(r rune) tea.Msg {
	h.t.Helper()
	return h.Send(tea.KeyPressMsg(tea.Key{
		Code: r,
		Text: string(r),
	}))
}

// PressKey builds a tea.KeyPressMsg from a named key code (one of the
// tea.Key* constants like tea.KeyEnter, tea.KeyTab, tea.KeyEsc) and
// delivers it via Send.
func (h *Harness) PressKey(code rune) tea.Msg {
	h.t.Helper()
	return h.Send(tea.KeyPressMsg(tea.Key{Code: code}))
}

// Resize delivers a LayoutMsg to the page. Subsequent View() calls
// reflect the new size. Calling Resize multiple times is allowed.
func (h *Harness) Resize(width, height int) {
	h.t.Helper()
	h.w, h.h = width, height
	h.resized = true
	h.Send(msg.LayoutMsg{ContentWidth: width, ContentHeight: height})
}

// View renders the page. If Resize was never called, a default
// LayoutMsg is delivered first so View() doesn't observe a zero-size
// content rect.
func (h *Harness) View() string {
	h.t.Helper()
	if !h.resized {
		h.Send(msg.LayoutMsg{ContentWidth: h.w, ContentHeight: h.h})
		h.resized = true
	}
	return h.page.View()
}

// PlainView returns View() with ANSI escape sequences stripped. Use
// this for substring assertions that should not be brittle to
// lipgloss color output.
func (h *Harness) PlainView() string {
	h.t.Helper()
	return stripANSI(h.View())
}

// AssertViewContains fails the test (via t.Fatalf) if PlainView()
// does not contain sub. Comparison is case-insensitive.
func (h *Harness) AssertViewContains(sub string) {
	h.t.Helper()
	got := h.PlainView()
	if !strings.Contains(strings.ToLower(got), strings.ToLower(sub)) {
		h.t.Fatalf("AssertViewContains: substring %q not found in view:\n%s", sub, got)
	}
}

// ---------------------------------------------------------------------
// internals
// ---------------------------------------------------------------------

// drain runs a tea.Cmd to completion and returns its message, handling
// the nil-cmd and nil-msg cases. Bubble Tea v2 cmds are non-blocking
// goroutine-spawning closures in production; in test we just call
// them synchronously and pull the result.
func drain(cmd tea.Cmd) tea.Msg {
	if cmd == nil {
		return nil
	}
	return cmd()
}

// ansiSeq matches ANSI CSI/OSC escape sequences. Adequate for
// lipgloss output (SGR + cursor control); not a full ECMA-48 parser.
var ansiSeq = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*\x07`)

func stripANSI(s string) string { return ansiSeq.ReplaceAllString(s, "") }
