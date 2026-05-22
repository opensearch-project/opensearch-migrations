package deploy_test

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
)

// ----------------------------------------------------------------------------
// Test helper: a Publisher that just records what was published.
//
// FakeDriver and RealDriver are constructed with a Publisher interface; the
// production wiring is pubsub.Broker. Tests use this recorder so they can
// assert exact event order without bringing pubsub into scope.
// ----------------------------------------------------------------------------

type recordPub struct {
	mu    sync.Mutex
	items []any
}

func (r *recordPub) Publish(msg any) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.items = append(r.items, msg)
}

func (r *recordPub) snapshot() []any {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]any, len(r.items))
	copy(out, r.items)
	return out
}

// onlyPhaseEvents filters the recording to PhaseEvent values, dropping
// anything else the driver might publish in the future. Keeps the assertions
// stable if we add a CommandStartedMsg or similar later.
func onlyPhaseEvents(items []any) []deploy.PhaseEvent {
	var out []deploy.PhaseEvent
	for _, it := range items {
		if pe, ok := it.(deploy.PhaseEvent); ok {
			out = append(out, pe)
		}
	}
	return out
}

// ----------------------------------------------------------------------------
// FakeDriver tests (Adjustment E)
// ----------------------------------------------------------------------------

func TestFakeDriver_PreviewPlan_ReturnsScriptedPlan(t *testing.T) {
	want := deploy.Plan{Items: []deploy.PlanItem{
		{Phase: "cfn", Description: "Deploy CFN bootstrap"},
		{Phase: "helm", Description: "Install helm release"},
	}}
	pub := &recordPub{}
	d := deploy.NewFakeDriver(deploy.FakeScript{Plan: want}, pub)

	got, err := d.PreviewPlan(context.Background(), deploy.Params{})
	require.NoError(t, err)
	require.Equal(t, want, got)
	// PreviewPlan does NOT publish — it's pure read-only.
	require.Empty(t, pub.snapshot())
}

func TestFakeDriver_PreviewPlan_HonoursContextCancel(t *testing.T) {
	d := deploy.NewFakeDriver(deploy.FakeScript{}, &recordPub{})

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := d.PreviewPlan(ctx, deploy.Params{})
	require.ErrorIs(t, err, context.Canceled)
}

func TestFakeDriver_Run_ReplaysScriptedEventsInOrder(t *testing.T) {
	pub := &recordPub{}
	script := deploy.FakeScript{
		Events: []deploy.PhaseEvent{
			{Phase: "cfn", Status: deploy.PhaseRunning, Message: "deploying"},
			{Phase: "cfn", Status: deploy.PhaseCompleted},
			{Phase: "helm", Status: deploy.PhaseRunning, Message: "installing release"},
			{Phase: "helm", Status: deploy.PhaseCompleted},
		},
	}
	d := deploy.NewFakeDriver(script, pub)

	require.NoError(t, d.Run(context.Background(), deploy.Params{}))
	require.Equal(t, script.Events, onlyPhaseEvents(pub.snapshot()))
}

func TestFakeDriver_Run_FailedEventStopsRunWithError(t *testing.T) {
	// When the script contains a PhaseFailed event, Run returns an error
	// after publishing it. Subsequent events in the script are NOT
	// published — matches RealDriver's short-circuit behaviour.
	pub := &recordPub{}
	script := deploy.FakeScript{
		Events: []deploy.PhaseEvent{
			{Phase: "cfn", Status: deploy.PhaseRunning},
			{Phase: "cfn", Status: deploy.PhaseFailed, Message: "stack rollback"},
			// This event is in the script but should not be replayed —
			// the driver short-circuits on PhaseFailed.
			{Phase: "helm", Status: deploy.PhaseRunning},
		},
	}
	d := deploy.NewFakeDriver(script, pub)

	err := d.Run(context.Background(), deploy.Params{})
	require.Error(t, err)
	require.Contains(t, err.Error(), "cfn")
	require.Contains(t, err.Error(), "stack rollback")

	got := onlyPhaseEvents(pub.snapshot())
	require.Len(t, got, 2, "must short-circuit at PhaseFailed; got %+v", got)
	require.Equal(t, deploy.PhaseRunning, got[0].Status)
	require.Equal(t, deploy.PhaseFailed, got[1].Status)
}

func TestFakeDriver_Run_HonoursContextCancel(t *testing.T) {
	// Cancel mid-replay; the driver MUST publish a final PhaseFailed
	// for the in-flight phase with status="cancelled" (as documented
	// on Driver.Run).
	pub := &recordPub{}
	script := deploy.FakeScript{
		Events: []deploy.PhaseEvent{
			{Phase: "cfn", Status: deploy.PhaseRunning, Message: "tick 1"},
			{Phase: "cfn", Status: deploy.PhaseRunning, Message: "tick 2"},
			{Phase: "cfn", Status: deploy.PhaseCompleted},
		},
		// EventDelay slows replay so cancel can fire mid-stream.
		EventDelay: 50 * time.Millisecond,
	}
	d := deploy.NewFakeDriver(script, pub)

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(20 * time.Millisecond)
		cancel()
	}()

	err := d.Run(ctx, deploy.Params{})
	require.ErrorIs(t, err, context.Canceled)

	got := onlyPhaseEvents(pub.snapshot())
	// Last event must be PhaseFailed with "cancelled" — defended in
	// the docstring of Driver.Run.
	require.NotEmpty(t, got)
	last := got[len(got)-1]
	require.Equal(t, deploy.PhaseFailed, last.Status)
	require.Contains(t, last.Message, "cancelled")
}

func TestFakeDriver_Run_EmptyScriptIsValid(t *testing.T) {
	// Empty event list is a legal "deploy of a no-op plan" — Run
	// returns nil and publishes nothing. Useful for testing the
	// no-action path of pages that call Run unconditionally.
	pub := &recordPub{}
	d := deploy.NewFakeDriver(deploy.FakeScript{}, pub)

	require.NoError(t, d.Run(context.Background(), deploy.Params{}))
	require.Empty(t, pub.snapshot())
}

func TestFakeDriver_Run_PreviewError_PropagatesFromPreview(t *testing.T) {
	// Script can pin PreviewPlan to return an error — exercises the
	// review-page error path without needing a real CFN stub.
	pub := &recordPub{}
	d := deploy.NewFakeDriver(deploy.FakeScript{
		PreviewError: errors.New("preview: stack lookup failed"),
	}, pub)

	_, err := d.PreviewPlan(context.Background(), deploy.Params{})
	require.Error(t, err)
	require.Contains(t, err.Error(), "stack lookup failed")
}

func TestFakeDriver_PublisherNilIsRejected(t *testing.T) {
	// NewFakeDriver with a nil publisher panics at construction (rather
	// than producing a driver that silently drops events). Catches
	// "forgot to wire the broker" in tests.
	require.Panics(t, func() {
		deploy.NewFakeDriver(deploy.FakeScript{}, nil)
	})
}
