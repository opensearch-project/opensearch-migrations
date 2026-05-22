// Package review renders the pre-deploy summary of the handoff
// brief and waits for the user to confirm (Enter -> deploy) or step
// back (Esc -> previous page).
//
// The page is read-only with respect to the brief: it does not own
// or mutate it; the wizard owns the brief and passes a pointer in.
// A nil pointer renders a placeholder so the page is safe to mount
// before the wizard has materialized any state.
package review

import (
	"fmt"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/handoffbrief"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// Config groups constructor arguments.
type Config struct {
	// Brief is the handoff brief to summarize. Nil-safe: the page
	// renders a "(no brief loaded)" placeholder when nil.
	Brief *handoffbrief.Brief
}

// Page is the review page Model.
type Page struct {
	cfg    Config
	width  int
	height int
}

var _ common.Page = (*Page)(nil)

// New constructs a Page.
func New(cfg Config) *Page { return &Page{cfg: cfg} }

func (p *Page) ID() msg.PageID { return msg.PageReview }

func (p *Page) Init() tea.Cmd {
	// Non-nil sentinel so the root can detect "page initialized".
	return func() tea.Msg { return nil }
}

func (p *Page) Update(in tea.Msg) (common.Page, tea.Cmd) {
	switch t := in.(type) {
	case msg.LayoutMsg:
		p.width, p.height = t.ContentWidth, t.ContentHeight
		return p, nil
	case tea.KeyPressMsg:
		switch t.Code {
		case tea.KeyEnter:
			return p, func() tea.Msg { return msg.NavigateMsg{To: msg.PageDeploy} }
		case tea.KeyEsc:
			return p, func() tea.Msg { return msg.NavigateBackMsg{} }
		}
	}
	return p, nil
}

func (p *Page) View() string {
	title := styles.Title.Render("Review handoff brief")

	if p.cfg.Brief == nil {
		body := styles.Subtle.Render("(no brief loaded)")
		footer := styles.Subtle.Render("Press Enter to deploy · Esc to go back")
		return lipgloss.JoinVertical(lipgloss.Left, "", title, "", body, "", footer)
	}

	b := p.cfg.Brief
	source := fmt.Sprintf("%s %s @ %s",
		valueOrEmpty(b.Source.Engine),
		valueOrEmpty(b.Source.EngineVersion),
		valueOrEmpty(b.Source.Endpoint),
	)
	target := fmt.Sprintf("%s @ %s",
		valueOrEmpty(b.Target.Type),
		valueOrEmpty(b.Target.Endpoint),
	)

	rows := []string{
		"",
		title,
		"",
		row("Stage:        ", b.Stage),
		row("AWS Account:  ", b.AWSAccount),
		row("Region:       ", b.Region),
		row("EKS Cluster:  ", b.EKSCluster),
		row("Namespace:    ", b.Namespace),
		// Source / Target rows are pre-formatted composites; render as-is so
		// the joined string keeps each unset slot's marker.
		styles.Subtle.Render("Source:       ") + source,
		styles.Subtle.Render("Target:       ") + target,
		row("Approx size:  ", b.Source.ApproxSize),
		"",
		styles.Subtle.Render("Press Enter to deploy · Esc to go back"),
	}
	return lipgloss.JoinVertical(lipgloss.Left, rows...)
}

func (p *Page) Focus() tea.Cmd { return nil }
func (p *Page) Blur()          {}

// row formats a single label+value line. Empty values render as the
// styled "(unset)" marker so omissions are visible at a glance.
func row(label, value string) string {
	return styles.Subtle.Render(label) + valueOrEmpty(value)
}

// valueOrEmpty returns value if non-empty, otherwise the styled
// "(unset)" marker. The literal token "unset" must appear in the
// rendered string so the page tests can assert on it.
func valueOrEmpty(value string) string {
	if value == "" {
		return styles.Subtle.Render("(unset)")
	}
	return value
}
