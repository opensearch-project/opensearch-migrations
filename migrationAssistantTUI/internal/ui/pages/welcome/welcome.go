// Package welcome is the first page the user sees. It introduces
// the migration-assistant, prints the bound MA version, and waits
// for Enter to advance to the intent page.
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
package welcome

import (
	"fmt"
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// Config groups constructor arguments. All fields optional.
type Config struct {
	// MAVersion is the migration-assistant CLI version to print
	// in the splash. Empty string renders "(dev)".
	MAVersion string
}

// Page is the welcome page Model.
type Page struct {
	cfg    Config
	width  int
	height int
}

var _ common.Page = (*Page)(nil)

// New constructs a Page.
func New(cfg Config) *Page { return &Page{cfg: cfg} }

func (p *Page) ID() msg.PageID { return msg.PageWelcome }

func (p *Page) Init() tea.Cmd {
	// Non-nil sentinel so callers can detect "page initialized".
	// Welcome has no real async startup work.
	return func() tea.Msg { return nil }
}

func (p *Page) Update(in tea.Msg) (common.Page, tea.Cmd) {
	switch t := in.(type) {
	case msg.LayoutMsg:
		p.width, p.height = t.ContentWidth, t.ContentHeight
		return p, nil
	case tea.KeyPressMsg:
		if t.Code == tea.KeyEnter {
			return p, func() tea.Msg { return msg.NavigateMsg{To: msg.PageIntent} }
		}
	}
	return p, nil
}

func (p *Page) View() string {
	v := p.cfg.MAVersion
	if v == "" {
		v = "(dev)"
	}

	title := styles.Title.Render("OpenSearch Migration Assistant")
	subtitle := styles.Subtle.Render(fmt.Sprintf("version %s", v))

	body := strings.Join([]string{
		"",
		"  Migration Assistant guides you from cluster bootstrap to a",
		"  ready-to-run migration. Each step writes a structured handoff",
		"  brief; agents pick up where you leave off.",
		"",
		"  " + styles.OK.Render("Press ") + styles.Selected.Render("Enter") + styles.OK.Render(" to begin."),
		"",
	}, "\n")

	return lipgloss.JoinVertical(lipgloss.Left, "", title, subtitle, body)
}

func (p *Page) Focus() tea.Cmd { return nil }
func (p *Page) Blur()          {}
