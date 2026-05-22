// Package msg defines the bubbletea Msg types that flow through
// the root Model's Update loop. Every cross-package event the UI
// reacts to lands here as a typed struct (no anonymous string-keyed
// payloads), so the root Model's switch on msg.Type is exhaustive
// at code-review time.
//
// Six distinct message families:
//
//	LayoutMsg           — page-content dimensions (post-chrome)
//	NavigateMsg         — request a page transition
//	ErrorMsg            — surface an error with a severity + source
//	HandoffMsg          — request tea.Quit followed by syscall.Exec
//	DeployEventMsg      — one streamed update from the deploy backend
//	BrokerEnvelopeMsg   — one envelope from internal/pubsub.Broker
//
// Rule R6 (UX.md): pages receive LayoutMsg, never tea.WindowSizeMsg
// directly. The root Model translates window size into LayoutMsg
// after subtracting the header/footer chrome it owns.
package msg

import (
	"fmt"
)

// ----------------------------------------------------------------------------
// PageID enum
// ----------------------------------------------------------------------------

// PageID names the six pages of the wizard. Used by NavigateMsg and
// the root Model's page registry.
type PageID int

const (
	PageUnknown PageID = iota
	PageWelcome
	PageIntent
	PageWizard
	PageReview
	PageDeploy
	PageHandoff
)

// String returns the lowercase page name (used for routing and
// status-line display).
func (p PageID) String() string {
	switch p {
	case PageWelcome:
		return "welcome"
	case PageIntent:
		return "intent"
	case PageWizard:
		return "wizard"
	case PageReview:
		return "review"
	case PageDeploy:
		return "deploy"
	case PageHandoff:
		return "handoff"
	default:
		return fmt.Sprintf("unknown(%d)", int(p))
	}
}

// ----------------------------------------------------------------------------
// LayoutMsg
// ----------------------------------------------------------------------------

// LayoutMsg gives a page its content area in cells. Width and
// Height already account for the chrome (header, status line,
// footer) the root Model reserves; pages may render directly into
// these dimensions without subtracting anything.
type LayoutMsg struct {
	ContentWidth  int
	ContentHeight int
}

// ----------------------------------------------------------------------------
// NavigateMsg
// ----------------------------------------------------------------------------

// NavigateMsg requests the root Model to transition to To. The
// root validates the transition (legal next-pages depend on the
// state machine in internal/ui).
type NavigateMsg struct {
	To PageID
}

// ----------------------------------------------------------------------------
// Severity + ErrorMsg
// ----------------------------------------------------------------------------

// Severity orders error gravity: Info < Warn < Error.
type Severity int

const (
	SeverityInfo Severity = iota
	SeverityWarn
	SeverityError
)

// String returns the lowercase name (used in error log lines).
func (s Severity) String() string {
	switch s {
	case SeverityInfo:
		return "info"
	case SeverityWarn:
		return "warn"
	case SeverityError:
		return "error"
	default:
		return fmt.Sprintf("unknown(%d)", int(s))
	}
}

// ErrorMsg surfaces a failure to the UI. Source identifies the
// origin (e.g. "preflight", "imageops", "deploy"). Err is wrapped
// so callers can errors.Is against the underlying sentinel.
type ErrorMsg struct {
	Sev    Severity
	Source string
	Err    error
}

// String formats the message for log lines and the error toast.
func (m ErrorMsg) String() string {
	if m.Err == nil {
		return fmt.Sprintf("[%s] %s: <nil>", m.Sev, m.Source)
	}
	return fmt.Sprintf("[%s] %s: %s", m.Sev, m.Source, m.Err.Error())
}

// ----------------------------------------------------------------------------
// HandoffMsg
// ----------------------------------------------------------------------------

// HandoffMsg is the final message before the TUI exits. The root
// Model returns tea.Quit, then cmd/tui's main runs syscall.Exec
// with these arguments so the user lands inside the agent shell
// with no TUI in the process tree.
type HandoffMsg struct {
	Bin  string
	Args []string
	Env  []string // os.Environ()-style "K=V" pairs
}

// ----------------------------------------------------------------------------
// DeployEventMsg
// ----------------------------------------------------------------------------

// DeployEventMsg is one streamed update from feature/deploy.Driver.
// Phase is the high-level stage ("preflight", "imageops",
// "nodepool", "helm", "smoke", "done"). Step is the step within
// the phase. Done/Total give 0..N progress. Terminal=true marks
// the final event of the deploy (success or failure).
type DeployEventMsg struct {
	Phase    string
	Step     string
	Done     int
	Total    int
	Terminal bool
}

// ----------------------------------------------------------------------------
// BrokerEnvelopeMsg
// ----------------------------------------------------------------------------

// BrokerEnvelopeMsg wraps one envelope read out of internal/pubsub.
// It is the SOLE channel by which backend goroutines push events
// into the UI — never a direct goroutine push, never a Cmd that
// reads outside its own closure (rule r3 in UX.md).
type BrokerEnvelopeMsg struct {
	Topic   string
	Payload []byte
}
