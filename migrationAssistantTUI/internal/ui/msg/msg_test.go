package msg_test

import (
	"errors"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
)

func TestLayoutMsg_HoldsContentDimensions(t *testing.T) {
	// LayoutMsg is the post-chrome page-content size, not raw terminal.
	m := msg.LayoutMsg{ContentWidth: 100, ContentHeight: 30}
	require.Equal(t, 100, m.ContentWidth)
	require.Equal(t, 30, m.ContentHeight)
}

func TestPageID_Enum_Stringer(t *testing.T) {
	require.Equal(t, "welcome", msg.PageWelcome.String())
	require.Equal(t, "intent", msg.PageIntent.String())
	require.Equal(t, "wizard", msg.PageWizard.String())
	require.Equal(t, "review", msg.PageReview.String())
	require.Equal(t, "deploy", msg.PageDeploy.String())
	require.Equal(t, "handoff", msg.PageHandoff.String())
	// Unknown id falls back to a clearly-debuggable string.
	require.Contains(t, msg.PageID(99).String(), "unknown")
}

func TestNavigateMsg_TargetsAPage(t *testing.T) {
	m := msg.NavigateMsg{To: msg.PageWizard}
	require.Equal(t, msg.PageWizard, m.To)
}

// NavigateBackMsg is a distinct zero-value type — root Model uses a
// type switch, so "go back" is unambiguous from "go forward to whatever
// Welcome happens to be".
func TestNavigateBackMsg_IsDistinctType(t *testing.T) {
	var m tea.Msg = msg.NavigateBackMsg{}
	_, ok := m.(msg.NavigateBackMsg)
	require.True(t, ok)

	// And it is NOT a NavigateMsg — important for the root's switch.
	_, isFwd := m.(msg.NavigateMsg)
	require.False(t, isFwd, "back must not type-collide with forward")
}

func TestSeverity_Levels(t *testing.T) {
	// Four severities; ordered by gravity.
	require.True(t, msg.SeverityWarn < msg.SeverityError)
	require.True(t, msg.SeverityInfo < msg.SeverityWarn)
	require.True(t, msg.SeverityError < msg.SeverityFatal,
		"Fatal must be the highest severity")
}

func TestSeverity_Stringer(t *testing.T) {
	require.Equal(t, "info", msg.SeverityInfo.String())
	require.Equal(t, "warn", msg.SeverityWarn.String())
	require.Equal(t, "error", msg.SeverityError.String())
	require.Equal(t, "fatal", msg.SeverityFatal.String())
}

func TestErrorMsg_WrapsErrorAndCarriesSeverity(t *testing.T) {
	root := errors.New("connection refused")
	m := msg.ErrorMsg{Sev: msg.SeverityError, Err: root, Source: "preflight"}

	require.Equal(t, msg.SeverityError, m.Sev)
	require.Equal(t, "preflight", m.Source)
	require.True(t, errors.Is(m.Err, root))
}

func TestErrorMsg_String_IncludesSourceAndSeverity(t *testing.T) {
	m := msg.ErrorMsg{
		Sev:    msg.SeverityWarn,
		Source: "imageops",
		Err:    errors.New("ECR mirror partial"),
	}
	s := m.String()
	require.Contains(t, s, "warn")
	require.Contains(t, s, "imageops")
	require.Contains(t, s, "ECR mirror partial")
}

func TestHandoffMsg_TargetCommandAndArgs(t *testing.T) {
	m := msg.HandoffMsg{
		Bin:       "/usr/local/bin/claude",
		Args:      []string{"--cwd", "/tmp/work"},
		Env:       []string{"FOO=bar"},
		BriefPath: "/tmp/work/.ma/handoff/brief.md",
	}
	require.Equal(t, "/usr/local/bin/claude", m.Bin)
	require.Equal(t, []string{"--cwd", "/tmp/work"}, m.Args)
	require.Equal(t, []string{"FOO=bar"}, m.Env)
	require.Equal(t, "/tmp/work/.ma/handoff/brief.md", m.BriefPath,
		"BriefPath is needed by cmd/tui/main.go to retain the handoff record post-Quit")
}

func TestDeployEventMsg_PhaseAndProgress(t *testing.T) {
	m := msg.DeployEventMsg{
		Phase:    "preflight",
		Step:     "subnet check",
		Done:     2,
		Total:    7,
		Terminal: false,
	}
	require.Equal(t, "preflight", m.Phase)
	require.Equal(t, 2, m.Done)
	require.Equal(t, 7, m.Total)
	require.False(t, m.Terminal)
}

func TestDeployEventMsg_TerminalFlagSignalsCompletion(t *testing.T) {
	m := msg.DeployEventMsg{Phase: "done", Terminal: true}
	require.True(t, m.Terminal)
}

func TestBrokerEnvelopeMsg_CarriesTopicAndPayload(t *testing.T) {
	// Payload is `any` because the broker is in-process — no
	// serialization boundary, callers carry typed Go values.
	m := msg.BrokerEnvelopeMsg{
		Topic:   "deploy/log",
		Payload: msg.DeployEventMsg{Phase: "helm", Step: "install"},
	}
	require.Equal(t, "deploy/log", m.Topic)
	got, ok := m.Payload.(msg.DeployEventMsg)
	require.True(t, ok, "payload must round-trip a typed Go value")
	require.Equal(t, "helm", got.Phase)
}
