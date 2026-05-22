package ui_test

import (
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/pubsub"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
)

// fakePage is a controllable Page used to drive the root Model.
// Identity is preserved (pointer comparison) so tests can detect
// "did the root re-create my page" regressions.
type fakePage struct {
	mu          sync.Mutex
	id          msg.PageID
	updates     []tea.Msg
	views       int
	focusCount  int
	blurCount   int
	updateCmd   tea.Cmd
	initCmd     tea.Cmd
	viewContent string
}

func newFakePage(id msg.PageID, view string) *fakePage {
	return &fakePage{id: id, viewContent: view}
}

func (p *fakePage) ID() msg.PageID { return p.id }
func (p *fakePage) Init() tea.Cmd  { return p.initCmd }

func (p *fakePage) Update(m tea.Msg) (common.Page, tea.Cmd) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.updates = append(p.updates, m)
	return p, p.updateCmd
}

func (p *fakePage) View() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.views++
	return p.viewContent
}

func (p *fakePage) Focus() tea.Cmd {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.focusCount++
	return nil
}

func (p *fakePage) Blur() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.blurCount++
}

func (p *fakePage) lastMsg() tea.Msg {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.updates) == 0 {
		return nil
	}
	return p.updates[len(p.updates)-1]
}

func (p *fakePage) updatesContaining(matcher func(tea.Msg) bool) int {
	p.mu.Lock()
	defer p.mu.Unlock()
	n := 0
	for _, m := range p.updates {
		if matcher(m) {
			n++
		}
	}
	return n
}

var _ common.Page = (*fakePage)(nil)

// makeRoot is the test factory. The first page is the start page.
func makeRoot(t *testing.T, broker *pubsub.Broker, pages ...*fakePage) *ui.Model {
	t.Helper()
	require.NotEmpty(t, pages, "tests need at least one page")
	reg := ui.NewPageRegistry()
	for _, p := range pages {
		reg.Register(p)
	}
	return ui.NewModel(ui.Config{
		Pages:     reg,
		Broker:    broker,
		StartPage: pages[0].ID(),
		MAVersion: "0.0.0-test",
	})
}

// ----------------------------------------------------------------------------
// PageRegistry
// ----------------------------------------------------------------------------

func TestPageRegistry_GetReturnsRegistered(t *testing.T) {
	r := ui.NewPageRegistry()
	p := newFakePage(msg.PageWelcome, "welcome")
	r.Register(p)

	got, ok := r.Get(msg.PageWelcome)
	require.True(t, ok)
	require.Same(t, p, got, "registry must return the same instance")
}

func TestPageRegistry_GetUnknownReturnsFalse(t *testing.T) {
	r := ui.NewPageRegistry()
	_, ok := r.Get(msg.PageHandoff)
	require.False(t, ok)
}

func TestPageRegistry_DoubleRegisterPanics(t *testing.T) {
	r := ui.NewPageRegistry()
	p := newFakePage(msg.PageWelcome, "")
	r.Register(p)
	require.Panics(t, func() { r.Register(p) },
		"re-registering same PageID is a developer bug, fail loudly")
}

// ----------------------------------------------------------------------------
// Model lifecycle: Init, View, layout
// ----------------------------------------------------------------------------

func TestModel_InitFocusesStartPage(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "welcome-content")
	intent := newFakePage(msg.PageIntent, "intent-content")
	m := makeRoot(t, b, welcome, intent)

	require.NotNil(t, m.Init(), "Init returns Cmd batch (focus + broker pump)")
	require.Equal(t, 1, welcome.focusCount, "start page must receive Focus on Init")
	require.Equal(t, 0, intent.focusCount, "non-start pages must not be focused")
}

func TestModel_ViewIncludesActivePage_HeaderAndFooter(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "WELCOME-BODY")
	m := makeRoot(t, b, welcome)
	m.Init()

	_, _ = m.Update(tea.WindowSizeMsg{Width: 120, Height: 40})
	rendered := m.View().Content

	require.Contains(t, rendered, "WELCOME-BODY",
		"rendered view must contain active page body")
	require.Contains(t, strings.ToLower(rendered), "quit",
		"footer help must include the quit binding")
	require.Contains(t, rendered, "0.0.0-test",
		"status line must show MAVersion")
}

func TestModel_PropagatesLayoutMsg_WithChromeSubtracted(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "")
	m := makeRoot(t, b, welcome)
	m.Init()

	_, _ = m.Update(tea.WindowSizeMsg{Width: 120, Height: 40})

	got := welcome.updatesContaining(func(in tea.Msg) bool {
		_, ok := in.(msg.LayoutMsg)
		return ok
	})
	require.Equal(t, 1, got, "active page must receive exactly one LayoutMsg per resize")

	last, ok := welcome.lastMsg().(msg.LayoutMsg)
	require.True(t, ok)
	require.Equal(t, 120, last.ContentWidth)
	require.Less(t, last.ContentHeight, 40,
		"ContentHeight must be smaller than terminal height (chrome subtracted)")
	require.Greater(t, last.ContentHeight, 0,
		"ContentHeight must be positive on a normal-sized terminal")
}

// ----------------------------------------------------------------------------
// Navigation — forward, back, unknown
// ----------------------------------------------------------------------------

func TestModel_NavigateMsg_SwitchesActivePage(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "")
	intent := newFakePage(msg.PageIntent, "")
	m := makeRoot(t, b, welcome, intent)
	m.Init()

	m2, _ := m.Update(msg.NavigateMsg{To: msg.PageIntent})
	require.Equal(t, 1, welcome.blurCount, "old page must be blurred")
	require.Equal(t, 1, intent.focusCount, "new page must be focused")

	intent.viewContent = "INTENT-NOW-ACTIVE"
	require.Contains(t, m2.View().Content, "INTENT-NOW-ACTIVE")
}

func TestModel_NavigateBack_PopsHistory(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "")
	intent := newFakePage(msg.PageIntent, "")
	wizard := newFakePage(msg.PageWizard, "")
	m := makeRoot(t, b, welcome, intent, wizard)
	m.Init()

	var mt tea.Model = m
	mt, _ = mt.Update(msg.NavigateMsg{To: msg.PageIntent})
	mt, _ = mt.Update(msg.NavigateMsg{To: msg.PageWizard})

	intent.viewContent = "INTENT-AFTER-BACK"
	mt, _ = mt.Update(msg.NavigateBackMsg{})
	require.Contains(t, mt.View().Content, "INTENT-AFTER-BACK",
		"Back must restore the previous page")

	welcome.viewContent = "WELCOME-AFTER-BACK"
	mt, _ = mt.Update(msg.NavigateBackMsg{})
	require.Contains(t, mt.View().Content, "WELCOME-AFTER-BACK",
		"Back must walk all the way to start")
}

func TestModel_NavigateBack_FromStart_IsNoop(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "WELCOME")
	m := makeRoot(t, b, welcome)
	m.Init()

	var mt tea.Model = m
	require.NotPanics(t, func() {
		mt, _ = mt.Update(msg.NavigateBackMsg{})
	})
	require.Contains(t, mt.View().Content, "WELCOME")
}

func TestModel_NavigateMsg_ToUnknownPage_EmitsError(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "WELCOME")
	m := makeRoot(t, b, welcome)
	m.Init()

	mt, _ := m.Update(msg.NavigateMsg{To: msg.PageReview})

	v := mt.View().Content
	require.Contains(t, v, "WELCOME",
		"unknown nav must NOT discard the current page")
	require.Contains(t, strings.ToLower(v), "error",
		"unknown nav must surface an error toast")
}

// ----------------------------------------------------------------------------
// Quit and global keys
// ----------------------------------------------------------------------------

func TestModel_CtrlC_TriggersQuit(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "")
	m := makeRoot(t, b, welcome)
	m.Init()

	// Ctrl+C in v2: Code is the rune, Mod is ModCtrl.
	_, cmd := m.Update(tea.KeyPressMsg(tea.Key{Code: 'c', Mod: tea.ModCtrl}))
	require.NotNil(t, cmd, "Ctrl+C must produce a Quit Cmd")

	out := cmd()
	_, isQuit := out.(tea.QuitMsg)
	require.True(t, isQuit, "Ctrl+C cmd must yield tea.QuitMsg, got %T", out)
}

// ----------------------------------------------------------------------------
// Broker pump (Adjustment A)
// ----------------------------------------------------------------------------

// TestModel_BrokerEnvelope_PumpForwardsToSender — backend goroutines
// publish typed Go values on the broker; the pump forwards them to
// the tea.Program (here a fake Sender) wrapped in BrokerEnvelopeMsg.
//
// v2 GA does NOT ship a `tea.Sub` helper. The supported pattern is:
//
//	go func() { for v := range ch { program.Send(v) } }()
//
// The root exposes the pump as a free function taking a Sender
// interface (program.Send-compatible) so unit tests can drive it
// without a real Program.
func TestModel_BrokerEnvelope_PumpForwardsToSender(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	sent := make(chan tea.Msg, 16)
	sender := ui.SenderFunc(func(in tea.Msg) { sent <- in })

	stop := ui.StartBrokerPump(b, sender)
	t.Cleanup(stop)

	b.Publish(msg.DeployEventMsg{Phase: "helm-install", Step: "ok"})

	select {
	case got := <-sent:
		env, ok := got.(msg.BrokerEnvelopeMsg)
		require.True(t, ok, "pump must wrap broker payload in BrokerEnvelopeMsg, got %T", got)
		_, ok = env.Payload.(msg.DeployEventMsg)
		require.True(t, ok, "envelope must preserve original payload type")
	case <-time.After(2 * time.Second):
		t.Fatal("broker pump didn't forward published message within 2s")
	}
}

func TestModel_BrokerPump_StopIsIdempotent(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)
	sender := ui.SenderFunc(func(tea.Msg) {})
	stop := ui.StartBrokerPump(b, sender)
	require.NotPanics(t, stop)
	require.NotPanics(t, stop)
}

// ----------------------------------------------------------------------------
// Error handling
// ----------------------------------------------------------------------------

func TestModel_ErrorMsg_RendersToast_StaysOnPage(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "WELCOME-BODY")
	m := makeRoot(t, b, welcome)
	m.Init()

	mt, _ := m.Update(msg.ErrorMsg{
		Sev:    msg.SeverityError,
		Source: "preflight",
		Err:    errors.New("preflight failed: kubectl not on PATH"),
	})

	v := mt.View().Content
	require.Contains(t, v, "WELCOME-BODY",
		"error toast must overlay, not replace, the active page")
	require.Contains(t, v, "preflight failed",
		"error message text must be visible in the toast")
}

func TestModel_ErrorMsg_Fatal_TriggersQuit(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "")
	m := makeRoot(t, b, welcome)
	m.Init()

	_, cmd := m.Update(msg.ErrorMsg{
		Sev:    msg.SeverityFatal,
		Source: "workspace",
		Err:    errors.New("workspace mount lost"),
	})
	require.NotNil(t, cmd)
	out := cmd()
	_, isQuit := out.(tea.QuitMsg)
	require.True(t, isQuit, "SeverityFatal must produce tea.QuitMsg, got %T", out)
}

// ----------------------------------------------------------------------------
// Handoff
// ----------------------------------------------------------------------------

func TestModel_HandoffMsg_StoresPayload_Quits(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	welcome := newFakePage(msg.PageWelcome, "")
	m := makeRoot(t, b, welcome)
	m.Init()

	hm := msg.HandoffMsg{
		Bin:       "/usr/local/bin/claude",
		Args:      []string{"--cwd", "/tmp/work"},
		Env:       []string{"FOO=bar"},
		BriefPath: "/tmp/work/.ma/handoff/brief.md",
	}
	mt, cmd := m.Update(hm)
	require.NotNil(t, cmd)
	out := cmd()
	_, isQuit := out.(tea.QuitMsg)
	require.True(t, isQuit,
		"HandoffMsg must produce tea.QuitMsg so the program loop exits cleanly")

	r, ok := mt.(*ui.Model)
	require.True(t, ok)
	got := r.Handoff()
	require.NotNil(t, got, "handoff payload must be retrievable post-Quit")
	require.Equal(t, "/usr/local/bin/claude", got.Bin)
	require.Equal(t, "/tmp/work/.ma/handoff/brief.md", got.BriefPath)
}

func TestModel_NoHandoff_ReturnsNil(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)
	welcome := newFakePage(msg.PageWelcome, "")
	m := makeRoot(t, b, welcome)
	m.Init()
	require.Nil(t, m.Handoff(),
		"Handoff() returns nil when no HandoffMsg was processed (user quit normally)")
}
