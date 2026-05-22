package teatest_test

// teatest_test.go — RED tests for the testutil/teatest harness.
//
// The harness wraps a common.Page to remove three categories of
// boilerplate from per-page tests:
//
//   1. Building tea.KeyPressMsg{} payloads from runes / key codes.
//   2. Draining a tea.Cmd into the message it produces (and asserting
//      on the type).
//   3. Resizing the page (LayoutMsg) before View() is asserted on.
//
// The harness is page-test-shaped on purpose. End-to-end tests of the
// full root Model + tea.Program go through golden tests in f19; this
// file does NOT spin up a Program.

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/testutil/teatest"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
)

// stubPage is a minimal common.Page used to exercise the harness
// without depending on any of the real pages (dependency cycles
// would otherwise force teatest to live in `internal/ui/testutil`).
type stubPage struct {
	id       msg.PageID
	w, h     int
	lastKey  tea.KeyPressMsg
	keyCount int
	focused  bool
}

func newStub() *stubPage { return &stubPage{id: msg.PageWelcome} }

func (p *stubPage) ID() msg.PageID { return p.id }

func (p *stubPage) Init() tea.Cmd {
	return func() tea.Msg { return nil }
}

func (p *stubPage) Update(in tea.Msg) (common.Page, tea.Cmd) {
	switch t := in.(type) {
	case msg.LayoutMsg:
		p.w, p.h = t.ContentWidth, t.ContentHeight
	case tea.KeyPressMsg:
		p.lastKey = t
		p.keyCount++
		if t.Code == tea.KeyEnter {
			return p, func() tea.Msg { return msg.NavigateMsg{To: msg.PageIntent} }
		}
	}
	return p, nil
}

func (p *stubPage) View() string {
	return "stub:" + p.id.String()
}

func (p *stubPage) Focus() tea.Cmd { p.focused = true; return nil }
func (p *stubPage) Blur()          { p.focused = false }

// ---------------------------------------------------------------------
// PressKey helper — turns a rune into a tea.KeyPressMsg
// ---------------------------------------------------------------------

func TestPressRune_DeliversAsKeyPress(t *testing.T) {
	p := newStub()
	h := teatest.New(t, p)

	h.PressRune('q')

	require.Equal(t, 1, p.keyCount)
	require.Equal(t, rune('q'), p.lastKey.Code)
}

func TestPressKey_DeliversNamedKey(t *testing.T) {
	p := newStub()
	h := teatest.New(t, p)

	h.PressKey(tea.KeyEnter)

	require.Equal(t, 1, p.keyCount)
	require.Equal(t, tea.KeyEnter, p.lastKey.Code)
}

func TestPressKey_ReturnsCmd(t *testing.T) {
	// Enter must produce a NavigateMsg via the cmd; harness must
	// surface the cmd output for inspection.
	p := newStub()
	h := teatest.New(t, p)

	out := h.PressKey(tea.KeyEnter)

	nav, ok := out.(msg.NavigateMsg)
	require.True(t, ok, "Enter must yield NavigateMsg via cmd, got %T", out)
	require.Equal(t, msg.PageIntent, nav.To)
}

func TestPressKey_NoCmd_ReturnsNil(t *testing.T) {
	// 'x' is ignored by the stub (no cmd). PressKey must return nil
	// rather than panic.
	p := newStub()
	h := teatest.New(t, p)

	out := h.PressRune('x')
	require.Nil(t, out, "no-cmd path must yield nil, not panic")
}

// ---------------------------------------------------------------------
// Resize helper — sends a LayoutMsg
// ---------------------------------------------------------------------

func TestResize_SetsContentDims(t *testing.T) {
	p := newStub()
	h := teatest.New(t, p)

	h.Resize(80, 24)

	require.Equal(t, 80, p.w)
	require.Equal(t, 24, p.h)
}

// ---------------------------------------------------------------------
// View helper — runs Resize → Init → returns View()
// ---------------------------------------------------------------------

func TestView_DefaultsToReasonableSize(t *testing.T) {
	p := newStub()
	h := teatest.New(t, p)

	v := h.View()
	require.Contains(t, v, "stub:")
	// Default size must be non-zero so pages that gate on width
	// don't render "(too small)".
	require.GreaterOrEqual(t, p.w, 40, "default width must be ≥ 40")
	require.GreaterOrEqual(t, p.h, 10, "default height must be ≥ 10")
}

func TestView_RespectsExplicitResize(t *testing.T) {
	p := newStub()
	h := teatest.New(t, p)
	h.Resize(120, 30)

	_ = h.View()
	require.Equal(t, 120, p.w)
	require.Equal(t, 30, p.h)
}

// ---------------------------------------------------------------------
// Send helper — generic msg injection (Layout/Navigate/etc.)
// ---------------------------------------------------------------------

func TestSend_DeliversArbitraryMsg(t *testing.T) {
	p := newStub()
	h := teatest.New(t, p)

	out := h.Send(msg.LayoutMsg{ContentWidth: 50, ContentHeight: 12})
	require.Nil(t, out, "stub returns no cmd for LayoutMsg")
	require.Equal(t, 50, p.w)
	require.Equal(t, 12, p.h)
}

// ---------------------------------------------------------------------
// AssertView helper — substring assertion convenience
// ---------------------------------------------------------------------

func TestAssertView_ContainsCaseInsensitive(t *testing.T) {
	p := newStub()
	h := teatest.New(t, p)

	// Should NOT fatal — substring is present.
	h.AssertViewContains("STUB")
}

func TestAssertView_FailsCleanly_OnMissing(t *testing.T) {
	// Run the assertion in a sub-test so we can observe its
	// failure without failing the parent.
	p := newStub()

	innerFailed := false
	mockT := &recordingT{TB: t, fail: func() { innerFailed = true }}
	h := teatest.New(mockT, p)
	h.AssertViewContains("absolutely-not-in-the-output")

	require.True(t, innerFailed, "AssertViewContains must call t.Fatal on miss")
}

// recordingT is a minimal testing.TB shim for asserting that the
// harness fails the test on a missing substring without actually
// failing the outer test.
type recordingT struct {
	testing.TB
	fail func()
}

func (r *recordingT) Helper()                            {}
func (r *recordingT) Fatalf(format string, args ...any)  { r.fail() }
func (r *recordingT) Fatal(args ...any)                  { r.fail() }
func (r *recordingT) Errorf(format string, args ...any)  { r.fail() }

// ---------------------------------------------------------------------
// Init helper — drains the start cmd
// ---------------------------------------------------------------------

func TestInit_DrainsStartCmd(t *testing.T) {
	p := newStub()
	h := teatest.New(t, p)

	out := h.Init()
	// stub Init returns nil msg via cmd.
	require.Nil(t, out)
}

// ---------------------------------------------------------------------
// View output is ANSI-stripped helper
// ---------------------------------------------------------------------

func TestPlainView_StripsANSI(t *testing.T) {
	// Some pages return lipgloss-styled ANSI; PlainView must strip
	// it so substring tests don't have to dodge color codes.
	p := &stubPage{id: msg.PageWelcome}
	h := teatest.New(t, p)
	plain := h.PlainView()
	require.False(t, strings.Contains(plain, "\x1b["),
		"PlainView must strip ANSI escapes")
}
