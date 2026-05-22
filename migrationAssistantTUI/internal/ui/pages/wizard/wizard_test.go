package wizard_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/handoffbrief"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/wizard"
)

// keyChar produces a v2 KeyPressMsg for a single rune.
func keyChar(r rune) tea.KeyPressMsg { return tea.KeyPressMsg(tea.Key{Code: r, Text: string(r)}) }

// keyCode produces a v2 KeyPressMsg for a non-rune key (Enter, Tab, etc).
func keyCode(c rune) tea.KeyPressMsg { return tea.KeyPressMsg(tea.Key{Code: c}) }

// keyShiftTab is shift+Tab; v2 has no dedicated KeyShiftTab const.
func keyShiftTab() tea.KeyPressMsg {
	return tea.KeyPressMsg(tea.Key{Code: tea.KeyTab, Mod: tea.ModShift})
}

// runCmd evaluates a tea.Cmd to its terminal Msg (or nil).
func runCmd(c tea.Cmd) tea.Msg {
	if c == nil {
		return nil
	}
	return c()
}

// type a string into the focused field.
func typeString(t *testing.T, p common.Page, s string) common.Page {
	t.Helper()
	for _, r := range s {
		p, _ = p.Update(keyChar(r))
	}
	return p
}

// advance to the next field (Tab).
func advance(t *testing.T, p common.Page) common.Page {
	t.Helper()
	p, _ = p.Update(keyCode(tea.KeyTab))
	return p
}

func newPage(t *testing.T) common.Page {
	t.Helper()
	p := wizard.New(wizard.Config{
		MAVersion:  "0.0.0-test",
		AWSAccount: "111122223333",
		Region:     "us-east-1",
		EKSCluster: "ma-cluster",
		Namespace:  "ma",
		Stage:      "dev",
	})
	require.NotNil(t, p)
	return p
}

// ----------------------------------------------------------------------------
// Surface
// ----------------------------------------------------------------------------

func TestNew_ImplementsPage(t *testing.T) {
	var _ common.Page = wizard.New(wizard.Config{})
}

func TestPage_ID(t *testing.T) {
	p := newPage(t)
	require.Equal(t, msg.PageWizard, p.ID())
}

func TestPage_Init_NonNilSentinel(t *testing.T) {
	// Convention: pages return a non-nil cmd from Init even when there's
	// no real startup work, so callers can detect "page initialized".
	p := newPage(t)
	cmd := p.Init()
	require.NotNil(t, cmd, "Init must return a non-nil sentinel cmd")
}

// ----------------------------------------------------------------------------
// Layout / View basics
// ----------------------------------------------------------------------------

func TestPage_AcceptsLayoutMsg(t *testing.T) {
	p := newPage(t)
	out, cmd := p.Update(msg.LayoutMsg{ContentWidth: 80, ContentHeight: 24})
	require.Same(t, p, out)
	require.Nil(t, cmd)
}

func TestView_RendersFirstFieldAndProgress(t *testing.T) {
	p := newPage(t)
	v := p.View()
	require.NotEmpty(t, v)
	// Title and step counter present.
	require.Contains(t, v, "Configure your migration")
	require.Regexp(t, `Step\s+1\s*/\s*\d+`, v)
}

// ----------------------------------------------------------------------------
// Field navigation
// ----------------------------------------------------------------------------

func TestTab_AdvancesField(t *testing.T) {
	// Implementation hint: View() should mark the active field with a
	// distinguishable cursor. We assert that after Tab, the rendered
	// step counter increments.
	p := newPage(t)
	require.Contains(t, p.View(), "Step 1")
	p = advance(t, p)
	require.Contains(t, p.View(), "Step 2")
}

func TestShiftTab_GoesBack(t *testing.T) {
	p := newPage(t)
	p = advance(t, p)
	require.Contains(t, p.View(), "Step 2")
	p, _ = p.Update(keyShiftTab())
	require.Contains(t, p.View(), "Step 1")
}

func TestShiftTab_OnFirstFieldEmitsNavigateBack(t *testing.T) {
	p := newPage(t)
	_, cmd := p.Update(keyShiftTab())
	require.NotNil(t, cmd)
	out := runCmd(cmd)
	nb, ok := out.(msg.NavigateBackMsg)
	require.True(t, ok, "expected NavigateBackMsg, got %T", out)
	_ = nb
}

// ----------------------------------------------------------------------------
// Brief population — full happy path
// ----------------------------------------------------------------------------

// fillBrief drives the wizard through every field with a known set of
// values that produces a Validate()-clean Brief.
func fillBrief(t *testing.T, p common.Page) common.Page {
	t.Helper()
	// Field order is wizard-defined; the test asserts the resulting
	// Brief, not the field sequence per se. Implementation must accept
	// these in declaration order.
	steps := []string{
		"https://es.example.com:9200", // source.endpoint
		"elasticsearch",               // source.engine
		"7.10",                        // source.engine_version
		"basic",                       // source.auth_method
		"keychain:ma-source-creds",    // source.auth_ref
		"1.2 TB",                      // source.approx_size
		"new-opensearch-domain",       // target.type
	}
	for i, s := range steps {
		p = typeString(t, p, s)
		if i < len(steps)-1 {
			p = advance(t, p)
		}
	}
	return p
}

func TestEnter_OnLastField_EmitsNavigateToReview(t *testing.T) {
	p := newPage(t)
	p = fillBrief(t, p)
	_, cmd := p.Update(keyCode(tea.KeyEnter))
	require.NotNil(t, cmd)
	out := runCmd(cmd)
	nm, ok := out.(msg.NavigateMsg)
	require.True(t, ok, "expected NavigateMsg, got %T", out)
	require.Equal(t, msg.PageReview, nm.To)
}

func TestBrief_AfterFullFill_ValidatesClean(t *testing.T) {
	p := newPage(t).(interface {
		Brief() handoffbrief.Brief
		common.Page
	})
	filled := fillBrief(t, p).(interface {
		Brief() handoffbrief.Brief
		common.Page
	})
	b := filled.Brief()
	// Constructor-supplied fields land in the brief verbatim.
	require.Equal(t, "0.0.0-test", b.MAVersion)
	require.Equal(t, "111122223333", b.AWSAccount)
	require.Equal(t, "us-east-1", b.Region)
	require.Equal(t, "ma-cluster", b.EKSCluster)
	require.Equal(t, "ma", b.Namespace)
	require.Equal(t, "dev", b.Stage)
	// Wizard-collected fields.
	require.Equal(t, "https://es.example.com:9200", b.Source.Endpoint)
	require.Equal(t, "elasticsearch", b.Source.Engine)
	require.Equal(t, "7.10", b.Source.EngineVersion)
	require.Equal(t, "basic", b.Source.AuthMethod)
	require.Equal(t, "keychain:ma-source-creds", b.Source.AuthRef)
	require.Equal(t, "1.2 TB", b.Source.ApproxSize)
	require.Equal(t, "new-opensearch-domain", b.Target.Type)
	// Brief is clean enough to write.
	require.NoError(t, b.Validate())
}

// ----------------------------------------------------------------------------
// Validation gate
// ----------------------------------------------------------------------------

func TestEnter_OnLastField_BlockedWhenIncomplete(t *testing.T) {
	// Walk to the last field by issuing exactly len(fields)-1 Tabs,
	// type nothing, hit Enter. Wizard must NOT navigate; it should
	// stay on the page and surface a validation banner.
	p := newPage(t)
	// 7 fields → 6 advances lands on the last field (Step 7/7).
	for i := 0; i < 6; i++ {
		p = advance(t, p)
	}
	require.Contains(t, p.View(), "Step 7")
	_, cmd := p.Update(keyCode(tea.KeyEnter))
	if cmd != nil {
		out := runCmd(cmd)
		_, isNav := out.(msg.NavigateMsg)
		require.False(t, isNav, "incomplete brief must not emit NavigateMsg")
	}
	// Validation hint must be visible somewhere in the view.
	v := p.View()
	require.True(t,
		strings.Contains(v, "missing") || strings.Contains(v, "required") || strings.Contains(v, "invalid"),
		"expected validation hint in view, got: %s", v)
}

// ----------------------------------------------------------------------------
// Esc → NavigateBack
// ----------------------------------------------------------------------------

func TestEsc_EmitsNavigateBack(t *testing.T) {
	p := newPage(t)
	_, cmd := p.Update(keyCode(tea.KeyEsc))
	require.NotNil(t, cmd)
	out := runCmd(cmd)
	_, ok := out.(msg.NavigateBackMsg)
	require.True(t, ok, "expected NavigateBackMsg, got %T", out)
}

// ----------------------------------------------------------------------------
// Focus / Blur idempotent
// ----------------------------------------------------------------------------

func TestFocusBlur_Idempotent(t *testing.T) {
	p := newPage(t)
	_ = p.Focus()
	_ = p.Focus()
	p.Blur()
	p.Blur()
}
