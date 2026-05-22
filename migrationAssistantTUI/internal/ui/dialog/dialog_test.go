package dialog_test

import (
	"errors"
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/dialog"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
)

func keyChar(r rune) tea.KeyPressMsg { return tea.KeyPressMsg(tea.Key{Code: r, Text: string(r)}) }
func keyCode(c rune) tea.KeyPressMsg { return tea.KeyPressMsg(tea.Key{Code: c}) }

// runCmd evaluates a tea.Cmd to its terminal Msg (or nil).
func runCmd(c tea.Cmd) tea.Msg {
	if c == nil {
		return nil
	}
	return c()
}

// allMsgs flattens a Cmd into the slice of Msgs it produces, walking
// into tea.Batch transparently. Returns nil for a nil cmd.
func allMsgs(c tea.Cmd) []tea.Msg {
	if c == nil {
		return nil
	}
	out := c()
	switch v := out.(type) {
	case tea.BatchMsg:
		var msgs []tea.Msg
		for _, sub := range v {
			msgs = append(msgs, allMsgs(sub)...)
		}
		return msgs
	case nil:
		return nil
	default:
		return []tea.Msg{out}
	}
}

// findMsg returns the first msg in cmds matching the type predicate.
func findMsg[T any](msgs []tea.Msg) (T, bool) {
	for _, m := range msgs {
		if v, ok := m.(T); ok {
			return v, true
		}
	}
	var zero T
	return zero, false
}

// ----------------------------------------------------------------------------
// Stack basics
// ----------------------------------------------------------------------------

func TestStack_ZeroValueEmpty(t *testing.T) {
	var s dialog.Stack
	require.True(t, s.Empty())
	require.Nil(t, s.Top())
}

func TestStack_PushTopPop(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1", Title: "Continue?"})
	var s dialog.Stack
	s = s.Push(d)
	require.False(t, s.Empty())
	require.Same(t, d, s.Top())

	s = s.Pop()
	require.True(t, s.Empty())
	require.Nil(t, s.Top())
}

func TestStack_PopOnEmpty_NoOp(t *testing.T) {
	var s dialog.Stack
	require.NotPanics(t, func() { s = s.Pop() })
	require.True(t, s.Empty())
}

func TestStack_LIFO(t *testing.T) {
	d1 := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1", Title: "First"})
	d2 := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c2", Title: "Second"})
	var s dialog.Stack
	s = s.Push(d1)
	s = s.Push(d2)
	require.Same(t, d2, s.Top())
	s = s.Pop()
	require.Same(t, d1, s.Top())
}

// ----------------------------------------------------------------------------
// Stack forwards Update to top dialog
// ----------------------------------------------------------------------------

func TestStack_Update_ForwardsToTop(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1", Title: "Continue?"})
	var s dialog.Stack
	s = s.Push(d)

	// Pressing 'y' on a Confirm must produce a ConfirmResultMsg{Confirmed:true}
	// AND a DialogPopMsg in the same batch.
	s2, cmd := s.Update(keyChar('y'))
	msgs := allMsgs(cmd)
	cr, ok := findMsg[msg.ConfirmResultMsg](msgs)
	require.True(t, ok, "expected ConfirmResultMsg, got %v", msgs)
	require.Equal(t, msg.DialogID("c1"), cr.ID)
	require.True(t, cr.Confirmed)
	_, popOk := findMsg[msg.DialogPopMsg](msgs)
	require.True(t, popOk, "expected DialogPopMsg, got %v", msgs)
	// The Stack itself does NOT pop on Update — that's the root Model's
	// job (it serializes stack mutations on receipt of DialogPopMsg).
	require.False(t, s2.Empty())
}

func TestStack_Update_NoOpWhenEmpty(t *testing.T) {
	var s dialog.Stack
	s2, cmd := s.Update(keyChar('y'))
	require.True(t, s2.Empty())
	require.Nil(t, cmd)
}

// ----------------------------------------------------------------------------
// Stack View
// ----------------------------------------------------------------------------

func TestStack_View_EmptyReturnsEmptyString(t *testing.T) {
	var s dialog.Stack
	require.Equal(t, "", s.View(80, 24))
}

func TestStack_View_RendersTopOnly(t *testing.T) {
	d1 := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1", Title: "First", Body: "first body"})
	d2 := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c2", Title: "Second", Body: "second body"})
	var s dialog.Stack
	s = s.Push(d1).Push(d2)
	v := s.View(80, 24)
	require.Contains(t, v, "Second")
	require.Contains(t, v, "second body")
	require.NotContains(t, v, "first body")
}

// ----------------------------------------------------------------------------
// Confirm dialog
// ----------------------------------------------------------------------------

func TestConfirm_View_ShowsTitleBodyKeys(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{
		ID:    "c1",
		Title: "Write brief?",
		Body:  "About to write HANDOFF.md",
	})
	v := d.View()
	require.Contains(t, v, "Write brief?")
	require.Contains(t, v, "About to write HANDOFF.md")
	require.True(t,
		strings.Contains(v, "y") && strings.Contains(v, "n"),
		"confirm dialog must show y/n hint, got: %s", v)
}

func TestConfirm_Y_EmitsResultTrue(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1"})
	_, cmd := d.Update(keyChar('y'))
	msgs := allMsgs(cmd)
	cr, ok := findMsg[msg.ConfirmResultMsg](msgs)
	require.True(t, ok)
	require.True(t, cr.Confirmed)
}

func TestConfirm_N_EmitsResultFalse(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1"})
	_, cmd := d.Update(keyChar('n'))
	msgs := allMsgs(cmd)
	cr, ok := findMsg[msg.ConfirmResultMsg](msgs)
	require.True(t, ok)
	require.False(t, cr.Confirmed)
}

func TestConfirm_Esc_TreatedAsNo(t *testing.T) {
	// Esc dismisses without confirming. The page that pushed the dialog
	// gets a result with Confirmed=false so it can re-enable input.
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1"})
	_, cmd := d.Update(keyCode(tea.KeyEsc))
	msgs := allMsgs(cmd)
	cr, ok := findMsg[msg.ConfirmResultMsg](msgs)
	require.True(t, ok)
	require.False(t, cr.Confirmed)
	_, popOk := findMsg[msg.DialogPopMsg](msgs)
	require.True(t, popOk)
}

func TestConfirm_OtherKeysIgnored(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1"})
	_, cmd := d.Update(keyChar('x'))
	require.Nil(t, cmd, "non-y/n/esc must not dismiss")
}

func TestConfirm_ID_SurvivesUpdate(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "review.write_brief"})
	_, cmd := d.Update(keyChar('y'))
	cr, ok := findMsg[msg.ConfirmResultMsg](allMsgs(cmd))
	require.True(t, ok)
	require.Equal(t, msg.DialogID("review.write_brief"), cr.ID)
}

// ----------------------------------------------------------------------------
// Error dialog
// ----------------------------------------------------------------------------

func TestError_View_ShowsErrAndAckHint(t *testing.T) {
	d := dialog.NewError(dialog.ErrorConfig{
		ID:  "e1",
		Err: errors.New("stack rollback: NoSuchBucket"),
	})
	v := d.View()
	require.Contains(t, v, "stack rollback: NoSuchBucket")
	require.True(t,
		strings.Contains(v, "Enter") || strings.Contains(v, "any key"),
		"error dialog must show ack hint, got: %s", v)
}

func TestError_AnyKey_EmitsAck(t *testing.T) {
	d := dialog.NewError(dialog.ErrorConfig{ID: "e1", Err: errors.New("boom")})
	_, cmd := d.Update(keyCode(tea.KeyEnter))
	msgs := allMsgs(cmd)
	ack, ok := findMsg[msg.ErrorAckMsg](msgs)
	require.True(t, ok)
	require.Equal(t, msg.DialogID("e1"), ack.ID)
	_, popOk := findMsg[msg.DialogPopMsg](msgs)
	require.True(t, popOk)
}

func TestError_NilErr_StillRendersTitle(t *testing.T) {
	// Defensive: a caller that passes nil shouldn't crash the UI.
	d := dialog.NewError(dialog.ErrorConfig{ID: "e1"})
	require.NotEmpty(t, d.View())
}

// ----------------------------------------------------------------------------
// Init + ID surface
// ----------------------------------------------------------------------------

func TestConfirm_Init_NonNilSentinel(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1"})
	require.NotNil(t, d.Init())
}

func TestError_Init_NonNilSentinel(t *testing.T) {
	d := dialog.NewError(dialog.ErrorConfig{ID: "e1"})
	require.NotNil(t, d.Init())
}

func TestConfirm_ID(t *testing.T) {
	d := dialog.NewConfirm(dialog.ConfirmConfig{ID: "c1"})
	require.Equal(t, msg.DialogID("c1"), d.ID())
}

func TestError_ID(t *testing.T) {
	d := dialog.NewError(dialog.ErrorConfig{ID: "e1"})
	require.Equal(t, msg.DialogID("e1"), d.ID())
}
