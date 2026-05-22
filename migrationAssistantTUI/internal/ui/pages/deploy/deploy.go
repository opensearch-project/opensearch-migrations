// Package deploy is the page that renders an in-flight deploy and
// gates the transition to the handoff page on completion. It is the
// only page in the tree that subscribes to the pubsub broker (via
// the root Model's pump → BrokerEnvelopeMsg envelope), and the type
// assertion on Payload is what binds this UI surface to the
// internal/feature/deploy.PhaseEvent wire shape.
//
// Page convention (followed by every page in this tree):
//
//   - The package's exported `New(Config) *Page` constructor takes a
//     value-type Config holding the page's required dependencies.
//     Optional fields are zero-valued and have sensible defaults.
//   - `*Page` implements common.Page.
//   - Update returns the page itself + a tea.Cmd; pages must NEVER
//     mutate the page registry, broker, or program directly. Routing
//     is the root Model's job; pages only emit Cmds (mostly NavigateMsg).
//   - View returns a plain string. The root wraps the entire UI in a
//     tea.View; pages never construct tea.View themselves.
//   - Focus/Blur are idempotent and side-effect-light. Pages do their
//     heavy startup in Init (returns a Cmd) so the tea event loop
//     can sequence it.
//
// Deploy-specific design notes:
//
//   - The page does NOT call Driver.Run itself. The host wires a
//     Starter cmd at construction; that cmd is what kicks off the
//     real deploy goroutine, which in turn publishes PhaseEvent
//     values onto the broker. This keeps the UI test independent of
//     the deploy package's concurrency model: page-level tests just
//     synthesize BrokerEnvelopeMsg instances directly.
//   - Phase status updates are routed by Phase string. Events for
//     phases not in the plan are ignored (defensive — a stale
//     PhaseEvent from a prior run shouldn't ever reach us, but if it
//     does, dropping it on the floor is safer than corrupting the
//     visible plan).
//   - Terminal state is "every plan phase reached PhaseCompleted" OR
//     "any plan phase reached PhaseFailed". Until then, Enter is a
//     no-op so an impatient operator can't skip past an in-flight
//     deploy.
//   - Esc emits NavigateBackMsg at any time. Cancelling the in-flight
//     deploy goroutine is the host's job (it owns the context); the
//     page only signals intent.
package deploy

import (
	"fmt"
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	deployfeat "github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// Config groups constructor arguments.
type Config struct {
	// Plan is the deploy.Driver.PreviewPlan output captured BEFORE
	// the page is constructed. The page renders one row per item.
	// Empty Plan is allowed (renders an empty list); the host should
	// never construct the page in that state in production.
	Plan deployfeat.Plan

	// Starter, if non-nil, is invoked from Init() so the deploy
	// goroutine starts at the moment the page becomes active.
	// Starter must be non-blocking (just kick off a goroutine and
	// return); long-blocking work in a tea.Cmd starves the event
	// loop. Page tests pass nil and synthesize PhaseEvents directly.
	Starter tea.Cmd
}

// phaseRow is one row in the rendered plan.
type phaseRow struct {
	phase       string
	description string
	status      deployfeat.PhaseStatus
	message     string // last non-empty message for this phase
}

// Page is the deploy page Model.
type Page struct {
	cfg      Config
	rows     []phaseRow
	indexBy  map[string]int // phase → row index for O(1) lookup
	terminal terminalState
	width    int
	height   int
}

type terminalState int

const (
	tsInflight terminalState = iota
	tsSuccess
	tsFailure
)

var _ common.Page = (*Page)(nil)

// New constructs a Page. Plan items become phaseRows in order;
// duplicate phase names in the plan are not deduplicated (driver
// contract: plans are unique on phase).
func New(cfg Config) *Page {
	rows := make([]phaseRow, len(cfg.Plan.Items))
	idx := make(map[string]int, len(cfg.Plan.Items))
	for i, it := range cfg.Plan.Items {
		rows[i] = phaseRow{phase: it.Phase, description: it.Description}
		idx[it.Phase] = i
	}
	return &Page{cfg: cfg, rows: rows, indexBy: idx}
}

func (p *Page) ID() msg.PageID { return msg.PageDeploy }

func (p *Page) Init() tea.Cmd {
	if p.cfg.Starter != nil {
		return p.cfg.Starter
	}
	// Non-nil sentinel so callers can detect "page initialized".
	return func() tea.Msg { return nil }
}

// ----------------------------------------------------------------------------
// Update
// ----------------------------------------------------------------------------

func (p *Page) Update(in tea.Msg) (common.Page, tea.Cmd) {
	switch t := in.(type) {
	case msg.LayoutMsg:
		p.width, p.height = t.ContentWidth, t.ContentHeight
		return p, nil
	case msg.BrokerEnvelopeMsg:
		return p.handleBroker(t)
	case tea.KeyPressMsg:
		return p.handleKey(t)
	}
	return p, nil
}

func (p *Page) handleBroker(env msg.BrokerEnvelopeMsg) (common.Page, tea.Cmd) {
	ev, ok := env.Payload.(deployfeat.PhaseEvent)
	if !ok {
		// Other topics on the broker (logs, metrics) are ignored. The
		// type assertion is the routing rule: if it's not a PhaseEvent,
		// it's not for us.
		return p, nil
	}
	row, ok := p.indexBy[ev.Phase]
	if !ok {
		// Stale / unrecognized phase. Drop silently.
		return p, nil
	}
	p.rows[row].status = ev.Status
	if ev.Message != "" {
		p.rows[row].message = ev.Message
	}
	p.recomputeTerminal()
	return p, nil
}

func (p *Page) handleKey(k tea.KeyPressMsg) (common.Page, tea.Cmd) {
	if k.Code == tea.KeyEsc {
		return p, func() tea.Msg { return msg.NavigateBackMsg{} }
	}
	if k.Code == tea.KeyEnter && p.terminal == tsSuccess {
		return p, func() tea.Msg { return msg.NavigateMsg{To: msg.PageHandoff} }
	}
	// Enter while inflight or after failure is a no-op. Failure routes
	// via Esc only — there's no "advance from a failed deploy".
	return p, nil
}

func (p *Page) recomputeTerminal() {
	allCompleted := len(p.rows) > 0
	for _, r := range p.rows {
		if r.status == deployfeat.PhaseFailed {
			p.terminal = tsFailure
			return
		}
		if r.status != deployfeat.PhaseCompleted {
			allCompleted = false
		}
	}
	if allCompleted {
		p.terminal = tsSuccess
	} else {
		p.terminal = tsInflight
	}
}

// ----------------------------------------------------------------------------
// View
// ----------------------------------------------------------------------------

func (p *Page) View() string {
	title := styles.Title.Render("Deploying migration assistant")
	out := []string{"", title, ""}

	for _, r := range p.rows {
		out = append(out, "  "+formatRow(r))
		if r.message != "" && r.status == deployfeat.PhaseFailed {
			out = append(out, styles.Error.Render("    "+r.message))
		}
	}

	out = append(out, "")
	switch p.terminal {
	case tsSuccess:
		out = append(out, styles.OK.Render("  All phases completed."))
		out = append(out, styles.Subtle.Render("  Press Enter to continue."))
	case tsFailure:
		out = append(out, styles.Error.Render("  Deploy failed."))
		out = append(out, styles.Subtle.Render("  Press Esc to go back and review."))
	default:
		out = append(out, styles.Subtle.Render("  Esc to cancel."))
	}

	return lipgloss.JoinVertical(lipgloss.Left, out...)
}

// formatRow renders one phase row: "<status-icon> <phase> — <description>".
// Status word is included verbatim so tests (and operators) can grep for
// "pending" / "running" / "completed" / "failed" / "skipped".
func formatRow(r phaseRow) string {
	statusWord := r.status.String()
	icon := iconFor(r.status)

	label := fmt.Sprintf("%s  %-22s %s", icon, r.phase+" — "+r.description, statusWord)
	switch r.status {
	case deployfeat.PhaseCompleted:
		return styles.OK.Render(label)
	case deployfeat.PhaseFailed:
		return styles.Error.Render(label)
	case deployfeat.PhaseRunning:
		return styles.Selected.Render(label)
	default:
		return styles.Subtle.Render(label)
	}
}

func iconFor(s deployfeat.PhaseStatus) string {
	switch s {
	case deployfeat.PhaseCompleted:
		return "✓"
	case deployfeat.PhaseFailed:
		return "✗"
	case deployfeat.PhaseRunning:
		return "▶"
	case deployfeat.PhaseSkipped:
		return "·"
	default:
		return " "
	}
}

// ----------------------------------------------------------------------------
// Focus / Blur
// ----------------------------------------------------------------------------

func (p *Page) Focus() tea.Cmd { return nil }
func (p *Page) Blur()          {}

// ----------------------------------------------------------------------------
// internal helper used in tests of view formatting
// ----------------------------------------------------------------------------

// stringContains is a no-op presence guard kept here so a future test
// migration to package-private helpers has a stable hook.
func stringContains(haystack, needle string) bool {
	return strings.Contains(haystack, needle)
}

var _ = stringContains
