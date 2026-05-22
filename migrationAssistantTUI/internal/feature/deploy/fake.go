package deploy

import (
	"context"
	"fmt"
	"time"
)

// Publisher is the narrow interface the driver uses to send PhaseEvent
// values to the UI. Production wires *pubsub.Broker (which has a Publish
// method); tests substitute a recording stub. We define it here (rather
// than importing pubsub) so internal/feature/deploy never depends on
// internal/pubsub — the layering rule is: pubsub adapts to whatever
// publisher shape its consumers want, not the other way around.
type Publisher interface {
	Publish(msg any)
}

// FakeScript is the recipe a FakeDriver replays.
//
// Plan         — what PreviewPlan returns. Can be the zero value if the
//
//	test only exercises Run.
//
// PreviewError — if non-nil, PreviewPlan returns this instead of Plan.
// Events       — what Run replays in order, one per "tick", to the
//
//	publisher. A PhaseFailed event short-circuits Run.
//
// EventDelay   — sleep between each Event. Zero means "as fast as
//
//	possible." Tests that want to exercise ctx cancel
//	mid-stream set this to ~50ms.
//
// Adjustment E in the design doc: this is the missing layer between unit
// tests (mock-everything) and end-to-end harnesses (real CFN/helm). A
// page test wires a FakeDriver scripted with a realistic event stream
// and golden-tests the deploy page rendering.
type FakeScript struct {
	Plan         Plan
	PreviewError error
	Events       []PhaseEvent
	EventDelay   time.Duration
}

// fakeDriver is the test-double implementation of Driver. Constructed
// only via NewFakeDriver so the publisher-nil panic is enforced.
type fakeDriver struct {
	script FakeScript
	pub    Publisher
}

// NewFakeDriver returns a Driver that replays the given script. Panics
// if pub is nil — a nil publisher silently dropping events is the most
// painful "test passed locally, broke in CI" failure mode and we'd
// rather catch it at construction.
func NewFakeDriver(script FakeScript, pub Publisher) Driver {
	if pub == nil {
		panic("deploy.NewFakeDriver: publisher must not be nil")
	}
	return &fakeDriver{script: script, pub: pub}
}

// PreviewPlan returns the script's Plan (or PreviewError) and honours
// ctx cancellation up-front. Pure read — no events published.
func (f *fakeDriver) PreviewPlan(ctx context.Context, _ Params) (Plan, error) {
	if err := ctx.Err(); err != nil {
		return Plan{}, err
	}
	if f.script.PreviewError != nil {
		return Plan{}, f.script.PreviewError
	}
	return f.script.Plan, nil
}

// Run replays the script's Events to the publisher in order. Stops
// early on ctx cancel (publishing a synthetic PhaseFailed cancelled
// event for the in-flight phase) or on a scripted PhaseFailed (which
// is short-circuit-by-design — matches RealDriver behaviour).
func (f *fakeDriver) Run(ctx context.Context, _ Params) error {
	var inflightPhase string

	for _, ev := range f.script.Events {
		// Honour cancel BEFORE publishing — semantics: cancel between
		// events stops cleanly without a duplicate publish.
		if err := ctx.Err(); err != nil {
			f.publishCancel(inflightPhase, err)
			return err
		}

		// Optional pacing for tests that need a window to cancel mid-stream.
		if f.script.EventDelay > 0 {
			select {
			case <-ctx.Done():
				f.publishCancel(inflightPhase, ctx.Err())
				return ctx.Err()
			case <-time.After(f.script.EventDelay):
			}
		}

		// Track the in-flight phase so cancel produces a sensible
		// PhaseFailed message.
		if ev.Status == PhaseRunning {
			inflightPhase = ev.Phase
		}

		f.pub.Publish(ev)

		if ev.Status == PhaseFailed {
			return fmt.Errorf("deploy phase %q failed: %s", ev.Phase, ev.Message)
		}
	}
	return nil
}

// publishCancel emits a synthetic terminal event covering the
// in-flight phase when ctx is cancelled mid-replay. If no phase was
// running we emit a generic event with phase="" so subscribers can
// still see "cancelled before any phase started."
func (f *fakeDriver) publishCancel(phase string, err error) {
	f.pub.Publish(PhaseEvent{
		Phase:   phase,
		Status:  PhaseFailed,
		Message: fmt.Sprintf("cancelled: %v", err),
	})
}
