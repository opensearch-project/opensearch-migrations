package deploy_test

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
)

type stubDriver struct {
	plan deploy.Plan
	err  error
}

func (s stubDriver) PreviewPlan(_ context.Context, _ deploy.Params) (deploy.Plan, error) {
	return s.plan, s.err
}
func (s stubDriver) Run(_ context.Context, _ deploy.Params) error { return s.err }

func TestDriverContract_PreviewAndRun(t *testing.T) {
	var d deploy.Driver = stubDriver{
		plan: deploy.Plan{Items: []deploy.PlanItem{
			{Phase: "cfn", Description: "Deploy CFN bootstrap stack"},
			{Phase: "helm", Description: "Install helm release"},
		}},
	}
	plan, err := d.PreviewPlan(context.Background(), deploy.Params{})
	require.NoError(t, err)
	require.Len(t, plan.Items, 2)
	require.Equal(t, "cfn", plan.Items[0].Phase)

	require.NoError(t, d.Run(context.Background(), deploy.Params{}))
}

func TestDriverContract_PropagatesError(t *testing.T) {
	var d deploy.Driver = stubDriver{err: errors.New("helm: timeout")}
	err := d.Run(context.Background(), deploy.Params{})
	require.Error(t, err)
}

func TestPhaseStatus_String(t *testing.T) {
	tests := []struct {
		s    deploy.PhaseStatus
		want string
	}{
		{deploy.PhasePending, "pending"},
		{deploy.PhaseRunning, "running"},
		{deploy.PhaseCompleted, "completed"},
		{deploy.PhaseFailed, "failed"},
		{deploy.PhaseSkipped, "skipped"},
		{deploy.PhaseStatus(99), "unknown"},
	}
	for _, tt := range tests {
		require.Equal(t, tt.want, tt.s.String())
	}
}

func TestPhaseStatus_ZeroValueIsPending(t *testing.T) {
	// Locked: any new struct{ Phase string; Status PhaseStatus }
	// gets PhasePending by default. Don't reorder these constants
	// without updating every PhaseEvent producer.
	var s deploy.PhaseStatus
	require.Equal(t, deploy.PhasePending, s)
}

func TestPhaseEvent_StableShape(t *testing.T) {
	// Compile-only pin: any reordering or rename of fields breaks
	// this test. PhaseEvent is a wire shape (broker payload, future
	// replay-from-disk).
	e := deploy.PhaseEvent{
		Phase:   "helm",
		Status:  deploy.PhaseFailed,
		Message: "helm: timeout",
	}
	require.Equal(t, "helm", e.Phase)
	require.Equal(t, deploy.PhaseFailed, e.Status)
	require.Equal(t, "helm: timeout", e.Message)
}
