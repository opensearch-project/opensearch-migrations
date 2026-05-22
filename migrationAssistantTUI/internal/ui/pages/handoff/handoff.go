// Package handoff is the final wizard page. It displays a summary of
// the agent that will be exec'd post-Quit and emits HandoffMsg when
// the user confirms with Enter. The page itself NEVER calls
// os/exec or syscall — that's the job of cmd/tui/main.go after
// tea.Program.Run returns.
package handoff

import (
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// Config groups constructor arguments. All fields optional.
type Config struct {
	// AgentBin is the path to the selected agent CLI binary. Empty
	// renders an error state and disables Enter.
	AgentBin string
	// AgentArgs is the argv (excluding argv[0]) passed to AgentBin.
	AgentArgs []string
	// AgentEnv is additional environment variables in KEY=VAL form.
	AgentEnv []string
	// BriefPath is the on-disk location of the handoff brief markdown.
	BriefPath string
	// AgentName is the pretty display name (e.g. "Claude Code").
	AgentName string
}

// Page is the handoff page Model.
type Page struct {
	cfg    Config
	width  int
	height int
}

var _ common.Page = (*Page)(nil)

// New constructs a Page.
func New(cfg Config) *Page { return &Page{cfg: cfg} }

func (p *Page) ID() msg.PageID { return msg.PageHandoff }

func (p *Page) Init() tea.Cmd {
	// Non-nil sentinel so callers can detect "page initialized".
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
			if p.cfg.AgentBin == "" {
				return p, nil
			}
			cfg := p.cfg
			return p, func() tea.Msg {
				return msg.HandoffMsg{
					Bin:       cfg.AgentBin,
					Args:      cfg.AgentArgs,
					Env:       cfg.AgentEnv,
					BriefPath: cfg.BriefPath,
				}
			}
		case tea.KeyEsc:
			return p, func() tea.Msg { return msg.NavigateBackMsg{} }
		}
	}
	return p, nil
}

func (p *Page) View() string {
	title := styles.Title.Render("Ready to hand off")

	briefLine := styles.Subtle.Render("Brief written to: ") + p.cfg.BriefPath

	var agentLine string
	if p.cfg.AgentBin == "" {
		agentLine = styles.Subtle.Render("Agent: ") +
			styles.Error.Render("(no agent selected)")
	} else {
		agentLine = styles.Subtle.Render("Agent: ") + p.cfg.AgentName
	}

	cmdParts := append([]string{p.cfg.AgentBin}, p.cfg.AgentArgs...)
	cmdLine := styles.Subtle.Render("$ " + strings.Join(cmdParts, " "))

	var footer string
	if p.cfg.AgentBin == "" {
		footer = styles.Subtle.Render("· Esc to go back")
	} else {
		footer = styles.Subtle.Render("Press Enter to launch agent · Esc to go back")
	}

	return lipgloss.JoinVertical(lipgloss.Left,
		"",
		title,
		"",
		briefLine,
		agentLine,
		cmdLine,
		"",
		footer,
	)
}

func (p *Page) Focus() tea.Cmd { return nil }
func (p *Page) Blur()          {}
