// Package intent is the second page of the wizard. It asks the user
// to pick one of three branches:
//
//   - Deploy a new migration stack       → PageWizard
//   - Reuse an existing migration stack  → PageReview
//   - Explore tools without deploying    → PageHandoff
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
package intent

import (
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// Config groups constructor arguments. All fields optional.
type Config struct{}

// option pairs a label with the page it routes to on Enter.
type option struct {
	label string
	to    msg.PageID
}

// options is the static, ordered list rendered by View() and indexed
// by Page.selected.
var options = []option{
	{"Deploy a new migration stack", msg.PageWizard},
	{"Reuse an existing migration stack", msg.PageReview},
	{"Explore tools without deploying", msg.PageHandoff},
}

// Page is the intent page Model.
type Page struct {
	cfg      Config
	selected int
	width    int
	height   int
}

var _ common.Page = (*Page)(nil)

// New constructs a Page.
func New(cfg Config) *Page { return &Page{cfg: cfg} }

func (p *Page) ID() msg.PageID { return msg.PageIntent }

func (p *Page) Init() tea.Cmd {
	// Non-nil sentinel so callers can detect "page initialized".
	// Intent has no real async startup work.
	return func() tea.Msg { return nil }
}

func (p *Page) Update(in tea.Msg) (common.Page, tea.Cmd) {
	switch t := in.(type) {
	case msg.LayoutMsg:
		p.width, p.height = t.ContentWidth, t.ContentHeight
		return p, nil
	case tea.KeyPressMsg:
		switch t.Code {
		case tea.KeyUp:
			if p.selected > 0 {
				p.selected--
			}
			return p, nil
		case tea.KeyDown:
			if p.selected < len(options)-1 {
				p.selected++
			}
			return p, nil
		case tea.KeyEnter:
			to := options[p.selected].to
			return p, func() tea.Msg { return msg.NavigateMsg{To: to} }
		}
	}
	return p, nil
}

func (p *Page) View() string {
	title := styles.Title.Render("What would you like to do?")

	rows := make([]string, 0, len(options)+2)
	rows = append(rows, "", title, "")
	for i, opt := range options {
		marker := "  "
		var line string
		if i == p.selected {
			marker = "> "
			line = styles.Selected.Render(marker + opt.label)
		} else {
			line = styles.Subtle.Render(marker + opt.label)
		}
		rows = append(rows, line)
	}
	rows = append(rows, "",
		styles.Subtle.Render("  ↑/↓ to move · Enter to select"))

	return lipgloss.JoinVertical(lipgloss.Left, rows...)
}

func (p *Page) Focus() tea.Cmd { return nil }
func (p *Page) Blur()          {}
