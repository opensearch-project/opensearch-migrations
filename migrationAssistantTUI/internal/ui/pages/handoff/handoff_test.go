package handoff_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/handoff"
)

func fullConfig() handoff.Config {
	return handoff.Config{
		AgentBin:  "/usr/local/bin/claude",
		AgentArgs: []string{"--resume", "session.json"},
		AgentEnv:  []string{"FOO=bar", "BAZ=qux"},
		BriefPath: "/tmp/work/.ma/handoff/brief.md",
		AgentName: "Claude Code",
	}
}

func TestPage_ID(t *testing.T) {
	p := handoff.New(handoff.Config{})
	require.Equal(t, msg.PageHandoff, p.ID())
}

func TestPage_View_RendersBriefPath_AgentName_Command(t *testing.T) {
	p := handoff.New(fullConfig())
	v := p.View()
	require.Contains(t, v, "Ready to hand off",
		"view must render the title")
	require.Contains(t, v, "/tmp/work/.ma/handoff/brief.md",
		"view must render the BriefPath value")
	require.Contains(t, v, "Claude Code",
		"view must render the AgentName")
	require.Contains(t, v, "/usr/local/bin/claude",
		"view must render the AgentBin in the command line")
	require.Contains(t, v, "--resume",
		"view must render args joined into the command line")
	require.Contains(t, v, "session.json",
		"view must render args joined into the command line")
	require.Contains(t, strings.ToLower(v), "enter",
		"footer must mention Enter")
	require.Contains(t, strings.ToLower(v), "esc",
		"footer must mention Esc")
}

func TestPage_View_NoAgent_RendersErrorState(t *testing.T) {
	p := handoff.New(handoff.Config{
		BriefPath: "/tmp/brief.md",
	})
	v := p.View()
	require.Contains(t, strings.ToLower(v), "no agent selected",
		"view must show an error when AgentBin is empty")
	require.NotContains(t, strings.ToLower(v), "press enter",
		"footer must NOT promise Enter when no agent is selected")
	require.Contains(t, strings.ToLower(v), "esc",
		"footer must still allow Esc to go back")
}

func TestPage_Update_Enter_WithAgent_EmitsHandoffMsg(t *testing.T) {
	cfg := fullConfig()
	p := handoff.New(cfg)
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter}))
	require.NotNil(t, cmd, "Enter with an agent must produce a command")

	out := cmd()
	h, ok := out.(msg.HandoffMsg)
	require.True(t, ok, "Enter must yield HandoffMsg, got %T", out)
	require.Equal(t, cfg.AgentBin, h.Bin)
	require.Equal(t, cfg.AgentArgs, h.Args)
	require.Equal(t, cfg.AgentEnv, h.Env)
	require.Equal(t, cfg.BriefPath, h.BriefPath)
}

func TestPage_Update_Enter_WithoutAgent_IsNoop(t *testing.T) {
	p := handoff.New(handoff.Config{BriefPath: "/tmp/brief.md"})
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter}))
	require.Nil(t, cmd, "Enter with no agent must be a no-op")
}

func TestPage_Update_EscEmitsNavigateBack(t *testing.T) {
	p := handoff.New(fullConfig())
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEsc}))
	require.NotNil(t, cmd, "Esc must produce a navigation command")

	out := cmd()
	_, ok := out.(msg.NavigateBackMsg)
	require.True(t, ok, "Esc must yield NavigateBackMsg, got %T", out)
}

func TestPage_Update_LayoutMsg_NoCmd_NoPanic(t *testing.T) {
	p := handoff.New(fullConfig())
	require.NotPanics(t, func() {
		_, cmd := p.Update(msg.LayoutMsg{ContentWidth: 80, ContentHeight: 24})
		require.Nil(t, cmd)
	})
}

func TestPage_Update_IgnoresUnknownKeys(t *testing.T) {
	p := handoff.New(fullConfig())
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: 'x'}))
	require.Nil(t, cmd, "unknown keys are ignored")
}

func TestPage_Init_NonNil(t *testing.T) {
	p := handoff.New(handoff.Config{})
	require.NotNil(t, p.Init(), "Init returns a non-nil sentinel cmd")
}

func TestPage_FocusBlur_AreSafe(t *testing.T) {
	p := handoff.New(handoff.Config{})
	require.NotPanics(t, func() {
		_ = p.Focus()
		p.Blur()
		_ = p.Focus()
		p.Blur()
	}, "Focus/Blur must be idempotent")
}
