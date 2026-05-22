package msg_test

import (
	"errors"
	"testing"

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

func TestSeverity_Levels(t *testing.T) {
	// Three severities; ordered by gravity.
	require.True(t, msg.SeverityWarn < msg.SeverityError)
	require.True(t, msg.SeverityInfo < msg.SeverityWarn)
}

func TestSeverity_Stringer(t *testing.T) {
	require.Equal(t, "info", msg.SeverityInfo.String())
	require.Equal(t, "warn", msg.SeverityWarn.String())
	require.Equal(t, "error", msg.SeverityError.String())
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
		Bin:  "/usr/local/bin/claude",
		Args: []string{"--cwd", "/tmp/work"},
		Env:  []string{"FOO=bar"},
	}
	require.Equal(t, "/usr/local/bin/claude", m.Bin)
	require.Equal(t, []string{"--cwd", "/tmp/work"}, m.Args)
	require.Equal(t, []string{"FOO=bar"}, m.Env)
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
	m := msg.BrokerEnvelopeMsg{
		Topic:   "deploy/log",
		Payload: []byte("helm install ok"),
	}
	require.Equal(t, "deploy/log", m.Topic)
	require.Equal(t, []byte("helm install ok"), m.Payload)
}
