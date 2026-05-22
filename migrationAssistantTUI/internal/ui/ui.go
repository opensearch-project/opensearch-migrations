// Package ui implements the root bubbletea Model. It owns the page
// registry, the page-history stack, the chrome (header/footer/status
// line), the broker pump (Adjustment A), and the post-Quit handoff
// payload.
//
// Architecture:
//
//	cmd/tui/main.go          — owns *tea.Program, calls Run, then
//	                           reads (*Model).Handoff() and execs.
//	internal/ui.Model        — root tea.Model; routes one msg at a
//	                           time to exactly one focused page.
//	internal/ui/pages/*      — leaf pages; satisfy common.Page;
//	                           never touch the *tea.Program directly.
//	internal/pubsub.Broker   — backend goroutines publish typed
//	                           events; the broker pump forwards to
//	                           the Program as BrokerEnvelopeMsg.
//
// Concurrency rules:
//
//   - Backend goroutines NEVER touch a Page directly. They only
//     publish on the broker. The pump is the one place that calls
//     program.Send, and it is synchronous-receive on the broker
//     side so a slow UI cannot block backends (the broker drops on
//     overflow, see internal/pubsub).
//
//   - The root Model is single-threaded by tea contract: every
//     mutating call (Update, View) happens on the tea event loop.
//     We never spawn goroutines from Update; if we need async work
//     we return a tea.Cmd.
//
// Charm-v2 GA notes:
//
//   - bubbletea v2.0.6 does NOT ship a `tea.Sub` helper. The
//     supported broker→UI shape is `go { for v := range ch {
//     program.Send(v) } }`, which is what StartBrokerPump does.
//     If charm ships `tea.Sub` later, swap StartBrokerPump's
//     internals; the public surface stays the same.
//
//   - Model.View() returns tea.View (a struct), not string. Pages
//     return string; the root wraps once with tea.NewView.
package ui

import (
	"fmt"
	"strings"
	"sync"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/key"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/pubsub"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	uikeys "github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/keys"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// ----------------------------------------------------------------------------
// PageRegistry
// ----------------------------------------------------------------------------

// PageRegistry is a tiny ID->Page map. Construction-time-only:
// pages are registered before NewModel and never mutated after.
// Concurrency: never accessed from goroutines other than the tea
// event loop.
type PageRegistry struct {
	pages map[msg.PageID]common.Page
}

func NewPageRegistry() *PageRegistry {
	return &PageRegistry{pages: map[msg.PageID]common.Page{}}
}

// Register adds p to the registry. Panics on a duplicate ID — this
// is a developer bug (two pages with the same enum) and we want a
// loud test failure, never a silent overwrite.
func (r *PageRegistry) Register(p common.Page) {
	if _, dup := r.pages[p.ID()]; dup {
		panic(fmt.Sprintf("ui: duplicate Page registration for %s", p.ID()))
	}
	r.pages[p.ID()] = p
}

// Get returns the registered page or false if no page has that ID.
func (r *PageRegistry) Get(id msg.PageID) (common.Page, bool) {
	p, ok := r.pages[id]
	return p, ok
}

// ----------------------------------------------------------------------------
// Sender + broker pump (Adjustment A)
// ----------------------------------------------------------------------------

// Sender is the subset of *tea.Program the broker pump uses. We
// take an interface so unit tests don't need a real Program.
type Sender interface {
	Send(tea.Msg)
}

// SenderFunc adapts a closure into a Sender (pattern parallels
// http.HandlerFunc).
type SenderFunc func(tea.Msg)

func (f SenderFunc) Send(m tea.Msg) { f(m) }

// StartBrokerPump spawns one goroutine that range-reads the broker
// subscription channel and forwards every published value to the
// Sender wrapped in BrokerEnvelopeMsg. The returned stop function
// cancels the subscription and unblocks the goroutine; calling it
// twice is a no-op.
//
// This replaces the legacy "block in a tea.Cmd" pattern (which
// would stall the tea event loop) and the not-yet-shipped tea.Sub.
func StartBrokerPump(b *pubsub.Broker, snd Sender) (stop func()) {
	ch, unsub := b.Subscribe()
	var once sync.Once
	stopFn := func() { once.Do(unsub) }

	go func() {
		for v := range ch {
			snd.Send(msg.BrokerEnvelopeMsg{Payload: v})
		}
	}()
	return stopFn
}

// ----------------------------------------------------------------------------
// Model
// ----------------------------------------------------------------------------

// Config groups the constructor arguments. Required: Pages,
// Broker, StartPage. MAVersion is optional; empty string renders
// "(dev)" in the status line.
type Config struct {
	Pages     *PageRegistry
	Broker    *pubsub.Broker
	StartPage msg.PageID
	MAVersion string
}

// Model is the root tea.Model.
type Model struct {
	cfg Config

	active  msg.PageID  // currently-focused page id
	history []msg.PageID // back-stack; does NOT include `active`

	// Chrome we reserve. Header (1) + status line (1) + footer help (1).
	chrome common.Chrome

	// Last received terminal size so we can re-derive layout on
	// page switch (the new page wants its first LayoutMsg).
	termW, termH int

	// Toast: a transient line shown over the page when an ErrorMsg
	// arrives. Cleared by the next non-error update.
	toast string

	// Handoff payload set when a HandoffMsg arrives. cmd/tui/main.go
	// reads it via Handoff() after Run() returns.
	handoff *msg.HandoffMsg
}

const (
	headerHeight = 1
	statusHeight = 1
	footerHeight = 1
)

// NewModel constructs a Model. Panics if cfg is invalid (nil
// registry, no start page, etc.) — programmer error, not user
// error.
func NewModel(cfg Config) *Model {
	if cfg.Pages == nil {
		panic("ui.NewModel: Pages registry is required")
	}
	if cfg.Broker == nil {
		panic("ui.NewModel: Broker is required")
	}
	if _, ok := cfg.Pages.Get(cfg.StartPage); !ok {
		panic(fmt.Sprintf("ui.NewModel: StartPage %s not in registry", cfg.StartPage))
	}
	return &Model{
		cfg:    cfg,
		active: cfg.StartPage,
		chrome: common.Chrome{
			HeaderHeight: headerHeight,
			FooterHeight: statusHeight + footerHeight,
		},
	}
}

// Init runs the start page's Init + Focus and returns the resulting
// command batch. The broker pump is NOT started here — cmd/tui's
// main starts it after building the Program (it needs a real
// program.Send).
func (m *Model) Init() tea.Cmd {
	page, _ := m.cfg.Pages.Get(m.active)
	cmds := []tea.Cmd{}
	if c := page.Init(); c != nil {
		cmds = append(cmds, c)
	}
	if c := page.Focus(); c != nil {
		cmds = append(cmds, c)
	}
	if len(cmds) == 0 {
		// Init must return non-nil so callers can detect "model
		// initialized" — return a no-op tea.Cmd.
		return func() tea.Msg { return nil }
	}
	return tea.Batch(cmds...)
}

// Update is the central message router. Every backend->UI event
// arrives here, not on a page directly.
func (m *Model) Update(in tea.Msg) (tea.Model, tea.Cmd) {
	switch t := in.(type) {

	case tea.WindowSizeMsg:
		m.termW, m.termH = t.Width, t.Height
		return m, m.forwardLayout()

	case tea.KeyPressMsg:
		// Global keys take priority over page keys.
		if key.Matches(t, uikeys.Global.Quit) {
			return m, tea.Quit
		}
		// Otherwise, forward to active page.
		return m, m.forwardToActive(in)

	case msg.NavigateMsg:
		return m, m.navigate(t.To)

	case msg.NavigateBackMsg:
		return m, m.navigateBack()

	case msg.ErrorMsg:
		m.toast = renderToastText(t)
		if t.Sev == msg.SeverityFatal {
			return m, tea.Quit
		}
		return m, nil

	case msg.HandoffMsg:
		// Take a value-copy so subsequent mutations to the original
		// (unlikely but defensible) don't reach the post-Quit reader.
		hm := t
		m.handoff = &hm
		return m, tea.Quit

	case msg.BrokerEnvelopeMsg:
		// Default route: the active page. Pages that care about
		// envelopes type-assert on Payload.
		return m, m.forwardToActive(in)

	default:
		return m, m.forwardToActive(in)
	}
}

// View renders header / status / page-content / footer. Page area
// is the active page's View() string verbatim; pages get a
// LayoutMsg at resize time and are responsible for fitting.
func (m *Model) View() tea.View {
	var b strings.Builder

	header := styles.Header.Render(" migration-assistant ")
	b.WriteString(header)
	b.WriteString("\n")

	page, ok := m.cfg.Pages.Get(m.active)
	if ok {
		body := page.View()
		if m.toast != "" {
			body = m.toast + "\n" + body
		}
		b.WriteString(body)
	} else {
		b.WriteString(styles.Error.Render(fmt.Sprintf("ui: no page registered for %s", m.active)))
	}

	b.WriteString("\n")
	b.WriteString(styles.Status.Render(m.statusLine()))
	b.WriteString("\n")
	b.WriteString(styles.Help.Render(renderShortHelp()))

	return tea.NewView(b.String())
}

// Handoff returns the HandoffMsg that triggered Quit, or nil if the
// program exited for another reason. cmd/tui/main.go reads this
// after Run() returns.
func (m *Model) Handoff() *msg.HandoffMsg { return m.handoff }

// ----------------------------------------------------------------------------
// internals
// ----------------------------------------------------------------------------

// forwardLayout pushes a fresh LayoutMsg into the active page (only).
// Called on WindowSizeMsg and on every page switch.
func (m *Model) forwardLayout() tea.Cmd {
	if m.termW == 0 || m.termH == 0 {
		return nil
	}
	lay := common.LayoutFromWindow(m.termW, m.termH, m.chrome)
	return m.forwardToActive(lay)
}

// forwardToActive runs the active page's Update with `in` and
// returns its Cmd. Mutates the registry entry in-place since
// Update returns common.Page.
func (m *Model) forwardToActive(in tea.Msg) tea.Cmd {
	page, ok := m.cfg.Pages.Get(m.active)
	if !ok {
		return nil
	}
	next, cmd := page.Update(in)
	m.cfg.Pages.pages[m.active] = next
	return cmd
}

// navigate switches focus to `to`. Old page is blurred, new page
// is focused, history is pushed.
func (m *Model) navigate(to msg.PageID) tea.Cmd {
	next, ok := m.cfg.Pages.Get(to)
	if !ok {
		// Unknown page — surface as an error toast, stay put.
		m.toast = renderToastText(msg.ErrorMsg{
			Sev:    msg.SeverityError,
			Source: "ui",
			Err:    fmt.Errorf("unknown page %s", to),
		})
		return nil
	}
	if to == m.active {
		return nil // no-op
	}

	prev, _ := m.cfg.Pages.Get(m.active)
	prev.Blur()

	m.history = append(m.history, m.active)
	m.active = to
	m.toast = "" // clear stale toast on transition

	cmds := []tea.Cmd{}
	if c := next.Focus(); c != nil {
		cmds = append(cmds, c)
	}
	if c := m.forwardLayout(); c != nil {
		cmds = append(cmds, c)
	}
	return tea.Batch(cmds...)
}

// navigateBack pops the back-stack. No-op when stack is empty.
func (m *Model) navigateBack() tea.Cmd {
	if len(m.history) == 0 {
		return nil
	}
	prevID := m.history[len(m.history)-1]
	m.history = m.history[:len(m.history)-1]

	curr, _ := m.cfg.Pages.Get(m.active)
	curr.Blur()

	next, ok := m.cfg.Pages.Get(prevID)
	if !ok {
		// Should be impossible — we pushed it from the registry —
		// but if it happens, stay put rather than panic.
		return nil
	}

	m.active = prevID
	m.toast = ""

	cmds := []tea.Cmd{}
	if c := next.Focus(); c != nil {
		cmds = append(cmds, c)
	}
	if c := m.forwardLayout(); c != nil {
		cmds = append(cmds, c)
	}
	return tea.Batch(cmds...)
}

func (m *Model) statusLine() string {
	v := m.cfg.MAVersion
	if v == "" {
		v = "(dev)"
	}
	return fmt.Sprintf("ma %s · %s", v, m.active)
}

func renderShortHelp() string {
	parts := []string{}
	for _, kb := range uikeys.Global.ShortHelp() {
		h := kb.Help()
		if h.Key == "" {
			continue
		}
		parts = append(parts, fmt.Sprintf("%s %s", h.Key, h.Desc))
	}
	return strings.Join(parts, "  ·  ")
}

func renderToastText(em msg.ErrorMsg) string {
	style := styles.Error
	switch em.Sev {
	case msg.SeverityWarn:
		style = styles.Warn
	case msg.SeverityInfo:
		style = styles.Subtle
	}
	body := em.Err.Error()
	if em.Source != "" {
		body = em.Source + ": " + body
	}
	return style.Render(fmt.Sprintf("[%s] %s", em.Sev, body))
}
