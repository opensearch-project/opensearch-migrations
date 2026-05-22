package welcome_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/welcome"
)

func TestPage_ID(t *testing.T) {
	p := welcome.New(welcome.Config{MAVersion: "1.2.3"})
	require.Equal(t, msg.PageWelcome, p.ID())
}

func TestPage_View_RendersTitleAndCallToAction(t *testing.T) {
	p := welcome.New(welcome.Config{MAVersion: "1.2.3"})
	v := p.View()
	require.Contains(t, strings.ToLower(v), "migration assistant",
		"welcome view must render the product name")
	require.Contains(t, strings.ToLower(v), "1.2.3",
		"welcome view must render the MAVersion passed in")
	require.Contains(t, strings.ToLower(v), "enter",
		"welcome view must hint at the Enter key to advance")
}

func TestPage_View_DevVersionWhenEmpty(t *testing.T) {
	p := welcome.New(welcome.Config{})
	v := p.View()
	require.Contains(t, strings.ToLower(v), "dev",
		"empty MAVersion must render as a (dev) marker, not blank")
}

func TestPage_Update_EnterEmitsNavigateToIntent(t *testing.T) {
	p := welcome.New(welcome.Config{})
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter}))
	require.NotNil(t, cmd, "Enter must produce a navigation command")

	out := cmd()
	nav, ok := out.(msg.NavigateMsg)
	require.True(t, ok, "Enter must yield NavigateMsg, got %T", out)
	require.Equal(t, msg.PageIntent, nav.To,
		"welcome -> intent on Enter")
}

func TestPage_Update_IgnoresOtherKeys(t *testing.T) {
	p := welcome.New(welcome.Config{})
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: 'x'}))
	require.Nil(t, cmd, "non-Enter keys are ignored at welcome page")
}

func TestPage_FocusBlur_AreSafe(t *testing.T) {
	p := welcome.New(welcome.Config{})
	require.NotPanics(t, func() {
		_ = p.Focus()
		p.Blur()
		_ = p.Focus()
	}, "Focus/Blur must be idempotent")
}

func TestPage_Init_NonNil(t *testing.T) {
	p := welcome.New(welcome.Config{})
	c := p.Init()
	require.NotNil(t, c, "Init returns a non-nil tea.Cmd (callers can detect init)")
}
