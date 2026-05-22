package common_test

import (
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
)

// fakePage is the smallest possible Page implementation. Tests use
// it to verify the interface shape without dragging in a real page.
type fakePage struct {
	id          msg.PageID
	updates     int
	views       int
	focuses     int
	blurs       int
	lastLayout  msg.LayoutMsg
	lastMessage tea.Msg
}

func (p *fakePage) ID() msg.PageID { return p.id }

func (p *fakePage) Init() tea.Cmd { return nil }

func (p *fakePage) Update(m tea.Msg) (common.Page, tea.Cmd) {
	p.updates++
	p.lastMessage = m
	if lm, ok := m.(msg.LayoutMsg); ok {
		p.lastLayout = lm
	}
	return p, nil
}

func (p *fakePage) View() string {
	p.views++
	return "fake"
}

func (p *fakePage) Focus() tea.Cmd { p.focuses++; return nil }
func (p *fakePage) Blur()          { p.blurs++ }

// Compile-time assertion: fakePage satisfies common.Page.
var _ common.Page = (*fakePage)(nil)

// ----------------------------------------------------------------------------

func TestPage_InterfaceContract(t *testing.T) {
	p := &fakePage{id: msg.PageWelcome}

	require.Equal(t, msg.PageWelcome, p.ID())
	require.Nil(t, p.Init())

	// Update returns the page (possibly a new instance) plus a Cmd.
	got, cmd := p.Update(msg.LayoutMsg{ContentWidth: 80, ContentHeight: 24})
	require.Equal(t, p, got)
	require.Nil(t, cmd)
	require.Equal(t, 1, p.updates)
	require.Equal(t, 80, p.lastLayout.ContentWidth)

	// View() must always return a renderable string (never empty
	// while focused — empty would draw a blank screen).
	require.Equal(t, "fake", p.View())

	// Focus / Blur must be idempotent-safe.
	require.Nil(t, p.Focus())
	p.Blur()
	require.Equal(t, 1, p.focuses)
	require.Equal(t, 1, p.blurs)
}

func TestRect_HoldsDimensions(t *testing.T) {
	r := common.Rect{Width: 100, Height: 30}
	require.Equal(t, 100, r.Width)
	require.Equal(t, 30, r.Height)
}

// TestClampInt — used by pages that subtract chrome and must never
// hand the page a negative width.
func TestClampInt(t *testing.T) {
	require.Equal(t, 5, common.ClampInt(5, 0, 10))
	require.Equal(t, 0, common.ClampInt(-3, 0, 10))
	require.Equal(t, 10, common.ClampInt(99, 0, 10))
}

// TestLayoutFromWindow — the helper the root Model uses to convert
// a tea.WindowSizeMsg into a msg.LayoutMsg, subtracting chrome.
func TestLayoutFromWindow(t *testing.T) {
	got := common.LayoutFromWindow(120, 40, common.Chrome{
		HeaderHeight: 1,
		FooterHeight: 2,
	})
	require.Equal(t, 120, got.ContentWidth)
	require.Equal(t, 37, got.ContentHeight) // 40 - 1 - 2
}

// TestLayoutFromWindow_TinyTerminal — when the terminal is tiny the
// helper must clamp to 0 rather than return a negative dimension.
func TestLayoutFromWindow_TinyTerminal(t *testing.T) {
	got := common.LayoutFromWindow(2, 1, common.Chrome{
		HeaderHeight: 1,
		FooterHeight: 2,
	})
	require.GreaterOrEqual(t, got.ContentHeight, 0,
		"ContentHeight must never go negative")
}
