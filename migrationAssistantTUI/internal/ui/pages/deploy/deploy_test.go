package deploy_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	deployfeat "github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/deploy"
)

func keyCode(c rune) tea.KeyPressMsg { return tea.KeyPressMsg(tea.Key{Code: c}) }

func runCmd(c tea.Cmd) tea.Msg {
	if c == nil {
		return nil
	}
	return c()
}

func samplePlan() deployfeat.Plan {
	return deployfeat.Plan{Items: []deployfeat.PlanItem{
		{Phase: "cfn", Description: "Deploy CFN bootstrap stack"},
		{Phase: "helm", Description: "Install helm release"},
		{Phase: "smoke", Description: "Run smoke tests"},
	}}
}

func newPage(t *testing.T) common.Page {
	t.Helper()
	p := deploy.New(deploy.Config{Plan: samplePlan()})
	require.NotNil(t, p)
	return p
}

// envelope wraps a deploy.PhaseEvent the way the UI broker pump does.
func envelope(ev deployfeat.PhaseEvent) msg.BrokerEnvelopeMsg {
	return msg.BrokerEnvelopeMsg{Topic: "deploy", Payload: ev}
}

// ----------------------------------------------------------------------------
// Surface
// ----------------------------------------------------------------------------

func TestNew_ImplementsPage(t *testing.T) {
	var _ common.Page = deploy.New(deploy.Config{})
}

func TestPage_ID(t *testing.T) {
	p := newPage(t)
	require.Equal(t, msg.PageDeploy, p.ID())
}

func TestPage_Init_NonNilSentinel(t *testing.T) {
	p := newPage(t)
	require.NotNil(t, p.Init(), "Init must return a non-nil sentinel cmd")
}

func TestPage_Init_RunsStarter(t *testing.T) {
	// When a Starter cmd is provided, Init returns it (or a tea.Batch
	// that includes it) so the deploy actually kicks off.
	called := false
	starter := func() tea.Msg {
		called = true
		return nil
	}
	p := deploy.New(deploy.Config{Plan: samplePlan(), Starter: starter})
	c := p.Init()
	require.NotNil(t, c)
	_ = c() // evaluate
	require.True(t, called, "Init's cmd must invoke the configured Starter")
}

// ----------------------------------------------------------------------------
// Layout
// ----------------------------------------------------------------------------

func TestPage_AcceptsLayoutMsg(t *testing.T) {
	p := newPage(t)
	out, cmd := p.Update(msg.LayoutMsg{ContentWidth: 80, ContentHeight: 24})
	require.Same(t, p, out)
	require.Nil(t, cmd)
}

// ----------------------------------------------------------------------------
// Plan rendering
// ----------------------------------------------------------------------------

func TestView_RendersPlanItemsBeforeAnyEvent(t *testing.T) {
	p := newPage(t)
	v := p.View()
	require.Contains(t, v, "Deploying migration assistant")
	require.Contains(t, v, "cfn")
	require.Contains(t, v, "helm")
	require.Contains(t, v, "smoke")
	// All phases start pending.
	require.Equal(t, 3, strings.Count(v, "pending"))
}

// ----------------------------------------------------------------------------
// PhaseEvent → status updates
// ----------------------------------------------------------------------------

func TestEvent_RunningTransitionsPhase(t *testing.T) {
	p := newPage(t)
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{
		Phase:  "cfn",
		Status: deployfeat.PhaseRunning,
	}))
	v := p.View()
	require.Contains(t, v, "running")
	// Other phases still pending.
	require.Contains(t, v, "pending")
}

func TestEvent_CompletedTransitionsPhase(t *testing.T) {
	p := newPage(t)
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: "cfn", Status: deployfeat.PhaseRunning}))
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: "cfn", Status: deployfeat.PhaseCompleted}))
	v := p.View()
	require.Contains(t, v, "completed")
}

func TestEvent_UnknownPhase_Ignored(t *testing.T) {
	// A PhaseEvent referencing a phase not in the plan must not panic
	// and must not corrupt the rendered plan.
	p := newPage(t)
	before := p.View()
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: "ghost", Status: deployfeat.PhaseRunning}))
	require.Equal(t, before, p.View(), "unknown phase must not change the view")
}

// ----------------------------------------------------------------------------
// Terminal states
// ----------------------------------------------------------------------------

// allCompleted drives every plan phase through running→completed.
func allCompleted(t *testing.T, p common.Page) common.Page {
	t.Helper()
	for _, item := range samplePlan().Items {
		p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: item.Phase, Status: deployfeat.PhaseRunning}))
		p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: item.Phase, Status: deployfeat.PhaseCompleted}))
	}
	return p
}

func TestSuccess_PromptsForEnter(t *testing.T) {
	p := allCompleted(t, newPage(t))
	v := p.View()
	require.Contains(t, v, "Press Enter to continue")
}

func TestSuccess_EnterEmitsNavigateToHandoff(t *testing.T) {
	p := allCompleted(t, newPage(t))
	_, cmd := p.Update(keyCode(tea.KeyEnter))
	require.NotNil(t, cmd)
	out := runCmd(cmd)
	nm, ok := out.(msg.NavigateMsg)
	require.True(t, ok, "expected NavigateMsg, got %T", out)
	require.Equal(t, msg.PageHandoff, nm.To)
}

func TestEnter_BeforeTerminal_NoOp(t *testing.T) {
	// Operator can't skip ahead while the deploy is mid-flight.
	p := newPage(t)
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: "cfn", Status: deployfeat.PhaseRunning}))
	_, cmd := p.Update(keyCode(tea.KeyEnter))
	if cmd != nil {
		out := runCmd(cmd)
		_, isNav := out.(msg.NavigateMsg)
		require.False(t, isNav, "Enter mid-deploy must not navigate")
	}
}

func TestFailure_RendersErrorAndPromptsForBack(t *testing.T) {
	p := newPage(t)
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: "cfn", Status: deployfeat.PhaseRunning}))
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{
		Phase:   "cfn",
		Status:  deployfeat.PhaseFailed,
		Message: "stack rollback: NoSuchBucket",
	}))
	v := p.View()
	require.Contains(t, v, "failed")
	require.Contains(t, v, "stack rollback: NoSuchBucket")
	require.Contains(t, v, "Press Esc to go back")
}

func TestFailure_EscEmitsNavigateBack(t *testing.T) {
	p := newPage(t)
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: "cfn", Status: deployfeat.PhaseFailed, Message: "boom"}))
	_, cmd := p.Update(keyCode(tea.KeyEsc))
	require.NotNil(t, cmd)
	out := runCmd(cmd)
	_, ok := out.(msg.NavigateBackMsg)
	require.True(t, ok, "expected NavigateBackMsg, got %T", out)
}

// ----------------------------------------------------------------------------
// Esc semantics
// ----------------------------------------------------------------------------

func TestEsc_MidDeploy_EmitsNavigateBack(t *testing.T) {
	// Operator can cancel an in-flight deploy. Page emits NavigateBack;
	// the host is responsible for the actual driver context cancel.
	p := newPage(t)
	p, _ = p.Update(envelope(deployfeat.PhaseEvent{Phase: "cfn", Status: deployfeat.PhaseRunning}))
	_, cmd := p.Update(keyCode(tea.KeyEsc))
	require.NotNil(t, cmd)
	out := runCmd(cmd)
	_, ok := out.(msg.NavigateBackMsg)
	require.True(t, ok, "expected NavigateBackMsg, got %T", out)
}

// ----------------------------------------------------------------------------
// Other broker payloads ignored
// ----------------------------------------------------------------------------

func TestOtherBrokerPayload_Ignored(t *testing.T) {
	p := newPage(t)
	before := p.View()
	p, _ = p.Update(msg.BrokerEnvelopeMsg{Topic: "log", Payload: "some random log line"})
	require.Equal(t, before, p.View())
}

// ----------------------------------------------------------------------------
// Focus / Blur idempotent
// ----------------------------------------------------------------------------

func TestFocusBlur_Idempotent(t *testing.T) {
	p := newPage(t)
	_ = p.Focus()
	_ = p.Focus()
	p.Blur()
	p.Blur()
}
