package review_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/handoffbrief"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/review"
)

func populatedBrief() *handoffbrief.Brief {
	return &handoffbrief.Brief{
		Stage:      "dev",
		AWSAccount: "123456789012",
		Region:     "us-west-2",
		EKSCluster: "ma-cluster",
		Namespace:  "ma",
		Source: handoffbrief.Source{
			Endpoint:      "https://src.example.com:9200",
			Engine:        "elasticsearch",
			EngineVersion: "7.10",
			ApproxSize:    "1.2 TB",
		},
		Target: handoffbrief.Target{
			Type:     "new-opensearch-domain",
			Endpoint: "https://tgt.example.com:443",
		},
	}
}

func TestPage_ID(t *testing.T) {
	p := review.New(review.Config{})
	require.Equal(t, msg.PageReview, p.ID())
}

func TestPage_View_NilBrief_RendersPlaceholder(t *testing.T) {
	p := review.New(review.Config{Brief: nil})
	v := p.View()
	require.Contains(t, strings.ToLower(v), "no brief loaded",
		"nil brief should render the placeholder")
}

func TestPage_View_PopulatedBrief_RendersAllFields(t *testing.T) {
	b := populatedBrief()
	p := review.New(review.Config{Brief: b})
	v := p.View()
	for _, want := range []string{
		b.Stage,
		b.AWSAccount,
		b.Region,
		b.EKSCluster,
		b.Namespace,
		b.Source.Endpoint,
		b.Target.Type,
		b.Source.ApproxSize,
	} {
		require.Contains(t, v, want, "view must contain %q", want)
	}
}

func TestPage_View_EmptyFields_RenderUnsetMarker(t *testing.T) {
	b := populatedBrief()
	b.Stage = ""
	p := review.New(review.Config{Brief: b})
	v := p.View()
	require.Contains(t, strings.ToLower(v), "unset",
		"empty fields should render an (unset) marker")
}

func TestPage_Update_EnterNavigatesToDeploy(t *testing.T) {
	p := review.New(review.Config{Brief: populatedBrief()})
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter}))
	require.NotNil(t, cmd, "Enter must produce a navigation command")

	out := cmd()
	nav, ok := out.(msg.NavigateMsg)
	require.True(t, ok, "Enter must yield NavigateMsg, got %T", out)
	require.Equal(t, msg.PageDeploy, nav.To, "review -> deploy on Enter")
}

func TestPage_Update_EscEmitsNavigateBack(t *testing.T) {
	p := review.New(review.Config{Brief: populatedBrief()})
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyEsc}))
	require.NotNil(t, cmd, "Esc must produce a navigate-back command")

	out := cmd()
	_, ok := out.(msg.NavigateBackMsg)
	require.True(t, ok, "Esc must yield NavigateBackMsg, got %T", out)
}

func TestPage_Update_LayoutMsg_NoCmd_NoPanic(t *testing.T) {
	p := review.New(review.Config{Brief: populatedBrief()})
	require.NotPanics(t, func() {
		_, cmd := p.Update(msg.LayoutMsg{ContentWidth: 80, ContentHeight: 24})
		require.Nil(t, cmd, "LayoutMsg must not produce a Cmd")
	})
}

func TestPage_Update_IgnoresUnknownKeys(t *testing.T) {
	p := review.New(review.Config{Brief: populatedBrief()})
	_, cmd := p.Update(tea.KeyPressMsg(tea.Key{Code: 'x'}))
	require.Nil(t, cmd, "unknown keys at review page must be ignored")
}

func TestPage_Init_NonNil(t *testing.T) {
	p := review.New(review.Config{})
	require.NotNil(t, p.Init(), "Init returns a non-nil sentinel cmd")
}

func TestPage_FocusBlur_AreSafe(t *testing.T) {
	p := review.New(review.Config{Brief: populatedBrief()})
	require.NotPanics(t, func() {
		_ = p.Focus()
		p.Blur()
		_ = p.Focus()
	}, "Focus/Blur must be idempotent")
}
