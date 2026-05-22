package intent_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/intent"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

func TestPage_ID(t *testing.T) {
	p := intent.New(intent.Config{})
	require.Equal(t, msg.PageIntent, p.ID())
}

func TestPage_View_DefaultHighlightsFirstOption(t *testing.T) {
	p := intent.New(intent.Config{})
	v := p.View()

	require.True(t, strings.Contains(v, "Deploy a new migration stack"),
		"first option text must appear in the view")

	// Compute the Selected style ANSI sentinel at test time by
	// rendering the empty string through the same lipgloss style.
	// The empty render is "<prefix>\x1b[m"; we look for the prefix
	// (the actual style-application escape) inside the view.
	emptyRender := styles.Selected.Render("")
	require.NotEmpty(t, emptyRender,
		"Selected style must render some ANSI prefix/suffix even on empty input")
	// Strip the trailing reset (\x1b[m) so we can match the prefix
	// inside arbitrary styled text.
	sentinel := strings.TrimSuffix(emptyRender, "\x1b[m")
	require.NotEqual(t, emptyRender, sentinel,
		"empty Selected render must end with the lipgloss reset escape")
	require.True(t, strings.Contains(v, sentinel),
		"Selected style ANSI sequence must be present in the view (default selection = option 1)")
}

func TestPage_Update_DownArrow_AdvancesSelection(t *testing.T) {
	p := intent.New(intent.Config{})
	var pg common.Page = p
	pg, _ = pg.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyDown}))
	_, cmd := pg.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter}))
	require.NotNil(t, cmd, "Enter must produce a navigation command")

	out := cmd()
	nav, ok := out.(msg.NavigateMsg)
	require.True(t, ok, "Enter must yield NavigateMsg, got %T", out)
	require.Equal(t, msg.PageReview, nav.To,
		"after one KeyDown, Enter navigates to PageReview")
}

func TestPage_Update_UpArrow_DoesNotUnderflow(t *testing.T) {
	p := intent.New(intent.Config{})
	var pg common.Page = p
	for i := 0; i < 5; i++ {
		pg, _ = pg.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyUp}))
	}
	_, cmd := pg.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter}))
	require.NotNil(t, cmd)
	nav, ok := cmd().(msg.NavigateMsg)
	require.True(t, ok)
	require.Equal(t, msg.PageWizard, nav.To,
		"clamped at index 0, Enter navigates to PageWizard")
}

func TestPage_Update_DownArrow_DoesNotOverflow(t *testing.T) {
	p := intent.New(intent.Config{})
	var pg common.Page = p
	for i := 0; i < 20; i++ {
		pg, _ = pg.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyDown}))
	}
	_, cmd := pg.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter}))
	require.NotNil(t, cmd)
	nav, ok := cmd().(msg.NavigateMsg)
	require.True(t, ok)
	require.Equal(t, msg.PageHandoff, nav.To,
		"clamped at index 2, Enter navigates to PageHandoff")
}

func TestPage_Update_EnterEmitsCorrectNavigateMsg_AllThreeIndices(t *testing.T) {
	cases := []struct {
		name  string
		downs int
		want  msg.PageID
	}{
		{"index0_wizard", 0, msg.PageWizard},
		{"index1_review", 1, msg.PageReview},
		{"index2_handoff", 2, msg.PageHandoff},
	}
	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			p := intent.New(intent.Config{})
			var pg common.Page = p
			for i := 0; i < tc.downs; i++ {
				pg, _ = pg.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyDown}))
			}
			_, cmd := pg.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter}))
			require.NotNil(t, cmd)
			nav, ok := cmd().(msg.NavigateMsg)
			require.True(t, ok, "got %T", cmd())
			require.Equal(t, tc.want, nav.To)
		})
	}
}

func TestPage_Update_LayoutMsgUpdatesDimensions(t *testing.T) {
	p := intent.New(intent.Config{})
	pg, cmd := p.Update(msg.LayoutMsg{ContentWidth: 80, ContentHeight: 24})
	require.Nil(t, cmd, "LayoutMsg produces no Cmd")
	require.NotPanics(t, func() {
		v := pg.View()
		require.NotEmpty(t, v, "view must remain non-empty after LayoutMsg")
	})
}

func TestPage_Update_IgnoresUnknownKeys(t *testing.T) {
	p := intent.New(intent.Config{})
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: 'x'}))
	require.Nil(t, cmd, "unknown keys must not produce a Cmd")
}

func TestPage_Init_NonNil(t *testing.T) {
	p := intent.New(intent.Config{})
	c := p.Init()
	require.NotNil(t, c, "Init returns a non-nil tea.Cmd")
	require.Nil(t, c(), "Init sentinel cmd yields nil msg")
}

func TestPage_FocusBlur_AreSafe(t *testing.T) {
	p := intent.New(intent.Config{})
	require.NotPanics(t, func() {
		_ = p.Focus()
		p.Blur()
		_ = p.Focus()
		p.Blur()
	}, "Focus/Blur must be idempotent")
}
