// Package dialog is the modal-overlay surface for the TUI. The root
// Model holds a Stack of Dialog values; while the stack is non-empty
// the top dialog gets keystrokes (the page beneath is frozen, just
// rendered as a dimmed backdrop).
//
// # Why a stack
//
// Dialogs nest. The handoff page can push a confirm-write-brief
// dialog; if the write fails, an error dialog stacks on top of the
// confirm. Both dismiss in LIFO order: ack the error → confirm
// dialog reappears → operator can re-attempt. A flat "current
// dialog" pointer would force whoever owns it to spaghetti through
// the nesting cases by hand.
//
// # Why dialogs emit DialogPopMsg instead of mutating the stack
//
// The stack mutation rule mirrors the page rule: only the root Model
// mutates. Dialogs emit DialogPopMsg paired with their typed result
// (ConfirmResultMsg / ErrorAckMsg) in a tea.Batch; the root Model
// receives both, pops the stack, and routes the result back to the
// active page. This keeps Stack.Update pure and testable, and gives
// the root Model a single serialization point for stack mutations.
//
// # Why result IDs
//
// A page can have multiple push sites for the same dialog type
// (review.write_brief vs review.delete_workdir, for example). The
// page tags each push with a unique DialogID; the dismissal result
// carries that ID back so the page's Update can switch on intent
// without book-keeping a "pending dialog" pointer.
//
// # Test contract
//
// Dialogs are pure tea.Models in disguise. Tests synthesize key
// events directly and assert on the emitted messages via
// allMsgs(cmd) — they do not need teatest. Stack tests cover
// LIFO/empty/forwarding semantics; concrete-dialog tests cover key
// bindings and result payloads.
package dialog

import (
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/styles"
)

// Dialog is the surface every modal overlay implements. It mirrors
// common.Page minus Focus/Blur (dialogs are always focused while on
// top of the stack) and minus LayoutMsg handling (dialogs render
// centered against a fixed dimension Stack passes in via View).
type Dialog interface {
	// ID returns the routing tag the originating page set at push
	// time. Stable across the dialog's lifecycle.
	ID() msg.DialogID

	// Init returns a non-nil sentinel cmd. Mirrors the page contract.
	Init() tea.Cmd

	// Update handles one tea.Msg. The Dialog returns itself (or a
	// successor instance) plus an optional Cmd. On dismissal, the
	// Cmd is a tea.Batch carrying the dialog's typed result + a
	// DialogPopMsg.
	Update(in tea.Msg) (Dialog, tea.Cmd)

	// View renders the dialog body. The Stack is responsible for
	// centering / framing against the screen dimensions.
	View() string
}

// ----------------------------------------------------------------------------
// Stack
// ----------------------------------------------------------------------------

// Stack is the modal stack. Zero value is an empty stack. Every
// mutating method returns a new Stack value — Stack is intentionally
// pass-by-value so the root Model can hold one as a regular field
// without worrying about pointer aliasing through tea.Cmd closures.
type Stack struct {
	items []Dialog
}

// Empty returns true if no dialog is on the stack.
func (s Stack) Empty() bool { return len(s.items) == 0 }

// Top returns the top dialog (the one receiving input), or nil.
func (s Stack) Top() Dialog {
	if len(s.items) == 0 {
		return nil
	}
	return s.items[len(s.items)-1]
}

// Push returns a Stack with d on top.
func (s Stack) Push(d Dialog) Stack {
	// Copy on push so the caller's slice header isn't aliased into
	// our internal storage. Cheap; modal stacks rarely exceed depth 2.
	out := Stack{items: make([]Dialog, len(s.items)+1)}
	copy(out.items, s.items)
	out.items[len(s.items)] = d
	return out
}

// Pop returns a Stack with the top removed. No-op on an empty stack.
func (s Stack) Pop() Stack {
	if len(s.items) == 0 {
		return s
	}
	out := Stack{items: make([]Dialog, len(s.items)-1)}
	copy(out.items, s.items[:len(s.items)-1])
	return out
}

// Update forwards in to the top dialog. The successor dialog (if
// any) replaces the top of the stack. The Stack does NOT pop on its
// own; pop is the root Model's job, on receipt of DialogPopMsg.
func (s Stack) Update(in tea.Msg) (Stack, tea.Cmd) {
	if s.Empty() {
		return s, nil
	}
	top := s.items[len(s.items)-1]
	successor, cmd := top.Update(in)
	if successor != top {
		out := Stack{items: make([]Dialog, len(s.items))}
		copy(out.items, s.items)
		out.items[len(s.items)-1] = successor
		return out, cmd
	}
	return s, cmd
}

// View renders the top dialog centered against the supplied screen
// dimensions, returning empty when the stack is empty. The root
// Model overlays this on top of the page view.
func (s Stack) View(screenW, screenH int) string {
	if s.Empty() {
		return ""
	}
	body := s.items[len(s.items)-1].View()
	box := styles.Selected.
		Width(min(screenW-4, 60)).
		Padding(1, 2).
		Render(body)
	return lipgloss.Place(screenW, screenH,
		lipgloss.Center, lipgloss.Center,
		box,
	)
}

// ----------------------------------------------------------------------------
// Confirm dialog
// ----------------------------------------------------------------------------

// ConfirmConfig groups Confirm's constructor arguments.
type ConfirmConfig struct {
	ID    msg.DialogID
	Title string
	Body  string
}

// confirmDialog is the y/n confirmation modal.
type confirmDialog struct {
	cfg ConfirmConfig
}

var _ Dialog = (*confirmDialog)(nil)

// NewConfirm constructs a y/n confirmation dialog. ID is required;
// the originating page reads it from the result msg to disambiguate.
func NewConfirm(cfg ConfirmConfig) Dialog { return &confirmDialog{cfg: cfg} }

func (d *confirmDialog) ID() msg.DialogID { return d.cfg.ID }

func (d *confirmDialog) Init() tea.Cmd {
	return func() tea.Msg { return nil }
}

func (d *confirmDialog) Update(in tea.Msg) (Dialog, tea.Cmd) {
	k, ok := in.(tea.KeyPressMsg)
	if !ok {
		return d, nil
	}
	switch {
	case k.Code == 'y' || k.Code == 'Y':
		return d, d.dismiss(true)
	case k.Code == 'n' || k.Code == 'N' || k.Code == tea.KeyEsc:
		return d, d.dismiss(false)
	}
	return d, nil
}

func (d *confirmDialog) dismiss(confirmed bool) tea.Cmd {
	id := d.cfg.ID
	return tea.Batch(
		func() tea.Msg { return msg.ConfirmResultMsg{ID: id, Confirmed: confirmed} },
		func() tea.Msg { return msg.DialogPopMsg{} },
	)
}

func (d *confirmDialog) View() string {
	title := styles.Title.Render(d.cfg.Title)
	body := d.cfg.Body
	hint := styles.Subtle.Render("[y] yes   [n] no   [esc] cancel")
	parts := []string{title, ""}
	if body != "" {
		parts = append(parts, body, "")
	}
	parts = append(parts, hint)
	return lipgloss.JoinVertical(lipgloss.Left, parts...)
}

// ----------------------------------------------------------------------------
// Error dialog
// ----------------------------------------------------------------------------

// ErrorConfig groups Error's constructor arguments.
type ErrorConfig struct {
	ID  msg.DialogID
	Err error
}

// errorDialog is the acknowledge-and-dismiss error modal.
type errorDialog struct {
	cfg ErrorConfig
}

var _ Dialog = (*errorDialog)(nil)

// NewError constructs an error-acknowledgement dialog. Any keypress
// dismisses it; pages that need recovery semantics chain on the
// emitted ErrorAckMsg.
func NewError(cfg ErrorConfig) Dialog { return &errorDialog{cfg: cfg} }

func (d *errorDialog) ID() msg.DialogID { return d.cfg.ID }

func (d *errorDialog) Init() tea.Cmd {
	return func() tea.Msg { return nil }
}

func (d *errorDialog) Update(in tea.Msg) (Dialog, tea.Cmd) {
	if _, ok := in.(tea.KeyPressMsg); !ok {
		return d, nil
	}
	id := d.cfg.ID
	return d, tea.Batch(
		func() tea.Msg { return msg.ErrorAckMsg{ID: id} },
		func() tea.Msg { return msg.DialogPopMsg{} },
	)
}

func (d *errorDialog) View() string {
	title := styles.Error.Render("Error")
	var body string
	if d.cfg.Err != nil {
		body = d.cfg.Err.Error()
	} else {
		body = "(no error message)"
	}
	hint := styles.Subtle.Render("Press Enter or any key to acknowledge.")
	return lipgloss.JoinVertical(lipgloss.Left,
		title,
		"",
		body,
		"",
		hint,
	)
}

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

// (none — builtin min is sufficient.)
