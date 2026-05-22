// Package wizard is the multi-step form that collects every field
// required to populate a handoffbrief.Brief. It is the only page in
// the tree that owns substantial mutable state; every other page
// either renders the brief read-only (review, handoff) or drives a
// backend (deploy).
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
// Wizard-specific design notes:
//
//   - The wizard owns N textinput.Model values, one per Brief field.
//     Field order is declared by the `fields` slice; renumbering is
//     a wire-shape change (visible to operators in the on-screen
//     "Step k/N" counter).
//   - Brief() materializes a handoffbrief.Brief from the constructor
//     params + the current input values. It is pure: calling it does
//     not mutate the wizard. The review and handoff pages call it.
//   - Validation runs on Enter from the last field. If the brief is
//     incomplete, the wizard surfaces the missing-fields list under
//     the form and refuses to navigate. This protects the handoff
//     write boundary (handoffbrief.Write also validates, but a
//     UI-side gate gives the operator a faster signal).
//   - The wizard never writes HANDOFF.md itself; it emits NavigateMsg
//     to PageReview and lets the review page decide when to flush.
package wizard

import (
	"fmt"
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/textinput"
	"charm.land/lipgloss/v2"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/handoffbrief"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// Config groups constructor arguments. The cluster-context fields are
// resolved before the wizard runs (CLI flags / config) and land in
// the brief verbatim; the wizard only collects per-migration fields.
type Config struct {
	MAVersion  string
	AWSAccount string
	Region     string
	EKSCluster string
	Namespace  string
	Stage      string
}

// fieldKey identifies one wizard field. Stable; reordering is a
// wire-shape change.
type fieldKey int

const (
	fSourceEndpoint fieldKey = iota
	fSourceEngine
	fSourceEngineVersion
	fSourceAuthMethod
	fSourceAuthRef
	fSourceApproxSize
	fTargetType
)

// fieldSpec describes one row in the form.
type fieldSpec struct {
	key         fieldKey
	label       string
	placeholder string
}

// fields is the static, ordered list of wizard fields. Index in this
// slice is the on-screen "Step k/N" counter (1-indexed in the view).
var fields = []fieldSpec{
	{fSourceEndpoint, "Source endpoint", "https://es.example.com:9200"},
	{fSourceEngine, "Source engine", "elasticsearch | opensearch | solr"},
	{fSourceEngineVersion, "Source engine version", "7.10"},
	{fSourceAuthMethod, "Source auth method", "none | basic | sigv4 | header"},
	{fSourceAuthRef, "Source auth ref", "keychain:<id> | env:<NAME> | vault:<path>"},
	{fSourceApproxSize, "Approximate source size", "1.2 TB / 300M docs (free text)"},
	{fTargetType, "Target type", "new-opensearch-domain | existing-aos | self-managed"},
}

// Page is the wizard page Model.
type Page struct {
	cfg     Config
	inputs  []textinput.Model
	active  int    // index into fields/inputs
	banner  string // non-empty after a failed validation Enter
	width   int
	height  int
}

var _ common.Page = (*Page)(nil)

// New constructs a Page with one textinput per declared field.
func New(cfg Config) *Page {
	ins := make([]textinput.Model, len(fields))
	for i, f := range fields {
		ti := textinput.New()
		ti.Placeholder = f.placeholder
		ti.CharLimit = 256
		ins[i] = ti
	}
	// Focus the first field so typing lands somewhere.
	ins[0].Focus()
	return &Page{cfg: cfg, inputs: ins}
}

func (p *Page) ID() msg.PageID { return msg.PageWizard }

func (p *Page) Init() tea.Cmd {
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
	case tea.KeyPressMsg:
		return p.handleKey(t)
	}
	// Forward other messages to the active input (e.g. blink ticks).
	var cmd tea.Cmd
	p.inputs[p.active], cmd = p.inputs[p.active].Update(in)
	return p, cmd
}

func (p *Page) handleKey(k tea.KeyPressMsg) (common.Page, tea.Cmd) {
	// Esc: leave the page entirely.
	if k.Code == tea.KeyEsc {
		return p, func() tea.Msg { return msg.NavigateBackMsg{} }
	}
	// Shift+Tab: previous field, or NavigateBack from the first field.
	if k.Code == tea.KeyTab && k.Mod == tea.ModShift {
		if p.active == 0 {
			return p, func() tea.Msg { return msg.NavigateBackMsg{} }
		}
		p.setActive(p.active - 1)
		return p, nil
	}
	// Tab: next field; on the last field, fall through to "no-op" (Enter
	// is the explicit submit gesture, mirroring most form UIs).
	if k.Code == tea.KeyTab {
		if p.active < len(fields)-1 {
			p.setActive(p.active + 1)
		}
		return p, nil
	}
	// Enter on the last field is the submit gesture; on an earlier
	// field it advances like Tab (a common form convenience).
	if k.Code == tea.KeyEnter {
		if p.active < len(fields)-1 {
			p.setActive(p.active + 1)
			return p, nil
		}
		return p.submit()
	}
	// Default: forward to the active textinput.
	var cmd tea.Cmd
	p.inputs[p.active], cmd = p.inputs[p.active].Update(k)
	// Typing clears any stale validation banner so it doesn't hang
	// over a now-corrected form.
	p.banner = ""
	return p, cmd
}

func (p *Page) setActive(i int) {
	p.inputs[p.active].Blur()
	p.active = i
	p.inputs[p.active].Focus()
}

// submit validates the brief; on success it emits NavigateMsg to
// PageReview, on failure it sets the banner and stays on the page.
func (p *Page) submit() (common.Page, tea.Cmd) {
	b := p.Brief()
	if err := b.Validate(); err != nil {
		// Compose a one-line missing-fields banner. The full list goes
		// inline so the operator can see at a glance which inputs are
		// still empty.
		p.banner = fmt.Sprintf("missing or invalid: %s", err.Error())
		return p, nil
	}
	p.banner = ""
	return p, func() tea.Msg { return msg.NavigateMsg{To: msg.PageReview} }
}

// ----------------------------------------------------------------------------
// Brief
// ----------------------------------------------------------------------------

// Brief materializes a handoffbrief.Brief from constructor params + the
// current input values. Pure: calling it does not mutate the wizard.
func (p *Page) Brief() handoffbrief.Brief {
	get := func(k fieldKey) string {
		return strings.TrimSpace(p.inputs[int(k)].Value())
	}
	return handoffbrief.Brief{
		MAVersion:  p.cfg.MAVersion,
		AWSAccount: p.cfg.AWSAccount,
		Region:     p.cfg.Region,
		EKSCluster: p.cfg.EKSCluster,
		Namespace:  p.cfg.Namespace,
		Stage:      p.cfg.Stage,
		Source: handoffbrief.Source{
			Endpoint:      get(fSourceEndpoint),
			Engine:        get(fSourceEngine),
			EngineVersion: get(fSourceEngineVersion),
			AuthMethod:    get(fSourceAuthMethod),
			AuthRef:       get(fSourceAuthRef),
			ApproxSize:    get(fSourceApproxSize),
		},
		Target: handoffbrief.Target{
			Type: get(fTargetType),
		},
	}
}

// ----------------------------------------------------------------------------
// View
// ----------------------------------------------------------------------------

func (p *Page) View() string {
	title := styles.Title.Render("Configure your migration")
	progress := styles.Subtle.Render(fmt.Sprintf("Step %d / %d", p.active+1, len(fields)))

	rows := []string{"", title, progress, ""}
	for i, f := range fields {
		marker := "  "
		labelStyle := styles.Subtle
		if i == p.active {
			marker = "> "
			labelStyle = styles.Selected
		}
		label := labelStyle.Render(marker + f.label)
		rows = append(rows, label, "    "+p.inputs[i].View(), "")
	}
	if p.banner != "" {
		rows = append(rows, styles.Error.Render("  "+p.banner), "")
	}
	rows = append(rows,
		styles.Subtle.Render("  Tab/Shift+Tab to move · Enter to submit · Esc to cancel"))

	return lipgloss.JoinVertical(lipgloss.Left, rows...)
}

// ----------------------------------------------------------------------------
// Focus / Blur
// ----------------------------------------------------------------------------

func (p *Page) Focus() tea.Cmd {
	// Re-focus whichever field was last active. Idempotent: textinput's
	// Focus is itself idempotent.
	return p.inputs[p.active].Focus()
}

func (p *Page) Blur() {
	for i := range p.inputs {
		p.inputs[i].Blur()
	}
}
