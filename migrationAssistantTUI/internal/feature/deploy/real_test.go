package deploy_test

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
)

// ----------------------------------------------------------------------------
// Subprocess fake — the runner RealDriver uses to invoke helm/kubectl/aws.
//
// Real production wraps exec.CommandContext; tests substitute fakeRunner so
// we can assert event sequencing without a real binary on PATH. This is the
// same Adjustment-E pattern applied one layer down.
// ----------------------------------------------------------------------------

type fakeRunStep struct {
	stdoutLines []string // each line published as a PhaseRunning Message
	exitErr     error    // nil = success, non-nil = phase fails
}

type fakeRunner struct {
	steps     map[string]fakeRunStep // keyed by phase name
	callOrder []string               // recorded for ordering assertions
}

func (f *fakeRunner) Run(ctx context.Context, phase string, _cmd string, _args []string, onLine func(string)) error {
	f.callOrder = append(f.callOrder, phase)
	step, ok := f.steps[phase]
	if !ok {
		return errors.New("fakeRunner: no script for phase " + phase)
	}
	for _, ln := range step.stdoutLines {
		if err := ctx.Err(); err != nil {
			return err
		}
		onLine(ln)
	}
	return step.exitErr
}

// ----------------------------------------------------------------------------
// RealDriver tests
// ----------------------------------------------------------------------------

func TestRealDriver_PreviewPlan_ReturnsPlannerOutput(t *testing.T) {
	want := deploy.Plan{Items: []deploy.PlanItem{
		{Phase: "cfn", Description: "Deploy CFN bootstrap"},
		{Phase: "helm", Description: "Install helm release"},
	}}
	pub := &recordPub{}
	d := deploy.NewRealDriver(deploy.RealConfig{
		Publisher: pub,
		Planner: func(_ context.Context, _ deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
			return want, nil, nil
		},
		Runner: &fakeRunner{},
	})

	got, err := d.PreviewPlan(context.Background(), deploy.Params{})
	require.NoError(t, err)
	require.Equal(t, want, got)
	require.Empty(t, pub.snapshot(), "PreviewPlan must not publish")
}

func TestRealDriver_PreviewPlan_PropagatesPlannerError(t *testing.T) {
	pub := &recordPub{}
	d := deploy.NewRealDriver(deploy.RealConfig{
		Publisher: pub,
		Planner: func(_ context.Context, _ deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
			return deploy.Plan{}, nil, errors.New("planner: missing artifact")
		},
		Runner: &fakeRunner{},
	})

	_, err := d.PreviewPlan(context.Background(), deploy.Params{})
	require.Error(t, err)
	require.Contains(t, err.Error(), "missing artifact")
}

func TestRealDriver_Run_PublishesPhaseLifecycleInOrder(t *testing.T) {
	pub := &recordPub{}
	steps := []deploy.ExecStep{
		{Phase: "cfn", Command: "aws", Args: []string{"cloudformation", "deploy"}},
		{Phase: "helm", Command: "helm", Args: []string{"upgrade", "--install", "ma"}},
	}
	runner := &fakeRunner{steps: map[string]fakeRunStep{
		"cfn":  {stdoutLines: []string{"Creating stack…", "Stack created"}},
		"helm": {stdoutLines: []string{"Release ma installed"}},
	}}
	d := deploy.NewRealDriver(deploy.RealConfig{
		Publisher: pub,
		Planner: func(_ context.Context, _ deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
			return deploy.Plan{Items: []deploy.PlanItem{
				{Phase: "cfn"}, {Phase: "helm"},
			}}, steps, nil
		},
		Runner: runner,
	})

	require.NoError(t, d.Run(context.Background(), deploy.Params{}))

	got := onlyPhaseEvents(pub.snapshot())
	// Expected lifecycle:
	//   cfn  Running ("Creating stack…")
	//   cfn  Running ("Stack created")
	//   cfn  Completed
	//   helm Running ("Release ma installed")
	//   helm Completed
	require.Len(t, got, 5)
	require.Equal(t, "cfn", got[0].Phase)
	require.Equal(t, deploy.PhaseRunning, got[0].Status)
	require.Equal(t, "Creating stack…", got[0].Message)
	require.Equal(t, deploy.PhaseRunning, got[1].Status)
	require.Equal(t, deploy.PhaseCompleted, got[2].Status)
	require.Equal(t, "helm", got[3].Phase)
	require.Equal(t, deploy.PhaseRunning, got[3].Status)
	require.Equal(t, deploy.PhaseCompleted, got[4].Status)

	require.Equal(t, []string{"cfn", "helm"}, runner.callOrder)
}

func TestRealDriver_Run_FailureShortCircuits_AndMarksRemainingSkipped(t *testing.T) {
	pub := &recordPub{}
	steps := []deploy.ExecStep{
		{Phase: "cfn", Command: "aws"},
		{Phase: "helm", Command: "helm"},
		{Phase: "kubectl-apply", Command: "kubectl"},
	}
	runner := &fakeRunner{steps: map[string]fakeRunStep{
		"cfn": {
			stdoutLines: []string{"Stack rolling back"},
			exitErr:     errors.New("exit status 1"),
		},
		// helm + kubectl-apply intentionally NOT scripted — if the
		// driver tried to call them, fakeRunner would error.
	}}
	d := deploy.NewRealDriver(deploy.RealConfig{
		Publisher: pub,
		Planner: func(_ context.Context, _ deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
			return deploy.Plan{Items: []deploy.PlanItem{
				{Phase: "cfn"}, {Phase: "helm"}, {Phase: "kubectl-apply"},
			}}, steps, nil
		},
		Runner: runner,
	})

	err := d.Run(context.Background(), deploy.Params{})
	require.Error(t, err)
	require.Contains(t, err.Error(), "cfn")

	got := onlyPhaseEvents(pub.snapshot())
	// Expected:
	//   cfn  Running "Stack rolling back"
	//   cfn  Failed  "exit status 1"
	//   helm Skipped
	//   kubectl-apply Skipped
	require.GreaterOrEqual(t, len(got), 4)
	var sawFailed, sawHelmSkipped, sawKubeSkipped bool
	for _, ev := range got {
		switch {
		case ev.Phase == "cfn" && ev.Status == deploy.PhaseFailed:
			sawFailed = true
			require.True(t, strings.Contains(ev.Message, "exit status 1"), "got message %q", ev.Message)
		case ev.Phase == "helm" && ev.Status == deploy.PhaseSkipped:
			sawHelmSkipped = true
		case ev.Phase == "kubectl-apply" && ev.Status == deploy.PhaseSkipped:
			sawKubeSkipped = true
		}
	}
	require.True(t, sawFailed, "must publish PhaseFailed for cfn")
	require.True(t, sawHelmSkipped, "must publish PhaseSkipped for helm")
	require.True(t, sawKubeSkipped, "must publish PhaseSkipped for kubectl-apply")
	// Runner must NOT have been called for the skipped phases.
	require.Equal(t, []string{"cfn"}, runner.callOrder)
}

func TestRealDriver_Run_HonoursContextCancel(t *testing.T) {
	// Cancel mid-stdout-stream. fakeRunner respects ctx, so after the
	// first onLine call the ctx is cancelled; the runner returns
	// ctx.Canceled; the driver wraps with a synthetic PhaseFailed
	// (cancelled).
	pub := &recordPub{}
	steps := []deploy.ExecStep{
		{Phase: "cfn", Command: "aws"},
		{Phase: "helm", Command: "helm"}, // remaining => Skipped
	}
	ctx, cancel := context.WithCancel(context.Background())
	runner := &fakeRunner{steps: map[string]fakeRunStep{
		"cfn": {stdoutLines: []string{"line 1", "line 2", "line 3"}},
	}}
	// Cancel after the first line is published. fakeRunner re-checks
	// ctx between each line and returns ctx.Err() once it's done.
	wrapped := &cancelOnFirstLine{inner: runner, cancel: cancel}

	d := deploy.NewRealDriver(deploy.RealConfig{
		Publisher: pub,
		Planner: func(_ context.Context, _ deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
			return deploy.Plan{Items: []deploy.PlanItem{{Phase: "cfn"}, {Phase: "helm"}}}, steps, nil
		},
		Runner: wrapped,
	})

	err := d.Run(ctx, deploy.Params{})
	require.ErrorIs(t, err, context.Canceled)

	got := onlyPhaseEvents(pub.snapshot())
	require.NotEmpty(t, got)
	// Locate the cancellation event; must be PhaseFailed with
	// "cancelled" in the message.
	var sawCancel bool
	for _, ev := range got {
		if ev.Status == deploy.PhaseFailed && strings.Contains(ev.Message, "cancelled") {
			sawCancel = true
			break
		}
	}
	require.True(t, sawCancel, "expected a PhaseFailed (cancelled) event; got %+v", got)

	// helm must be marked Skipped (defensive: an earlier failure must
	// not let later phases run).
	var sawHelmSkipped bool
	for _, ev := range got {
		if ev.Phase == "helm" && ev.Status == deploy.PhaseSkipped {
			sawHelmSkipped = true
		}
	}
	require.True(t, sawHelmSkipped)
}

// cancelOnFirstLine wraps a SubprocessRunner so the first onLine call
// triggers the test-controlled cancel func. Lets us simulate "user
// hit Ctrl-C while helm was streaming output."
type cancelOnFirstLine struct {
	inner  deploy.SubprocessRunner
	cancel context.CancelFunc
	fired  bool
}

func (c *cancelOnFirstLine) Run(ctx context.Context, phase, cmd string, args []string, onLine func(string)) error {
	wrapped := func(line string) {
		onLine(line)
		if !c.fired {
			c.fired = true
			c.cancel()
		}
	}
	return c.inner.Run(ctx, phase, cmd, args, wrapped)
}

func TestRealDriver_Run_PlannerErrorIsTerminal(t *testing.T) {
	pub := &recordPub{}
	d := deploy.NewRealDriver(deploy.RealConfig{
		Publisher: pub,
		Planner: func(_ context.Context, _ deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
			return deploy.Plan{}, nil, errors.New("planner: workdir corrupt")
		},
		Runner: &fakeRunner{},
	})

	err := d.Run(context.Background(), deploy.Params{})
	require.Error(t, err)
	require.Contains(t, err.Error(), "workdir corrupt")
	// Planner-failure means we never got far enough to publish a
	// per-phase event; the UI surfaces the error directly.
	require.Empty(t, onlyPhaseEvents(pub.snapshot()))
}

func TestRealDriver_NilPublisherPanics(t *testing.T) {
	require.Panics(t, func() {
		deploy.NewRealDriver(deploy.RealConfig{
			Publisher: nil,
			Planner: func(_ context.Context, _ deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
				return deploy.Plan{}, nil, nil
			},
			Runner: &fakeRunner{},
		})
	})
}

func TestRealDriver_NilPlannerPanics(t *testing.T) {
	require.Panics(t, func() {
		deploy.NewRealDriver(deploy.RealConfig{
			Publisher: &recordPub{},
			Planner:   nil,
			Runner:    &fakeRunner{},
		})
	})
}

func TestRealDriver_NilRunnerPanics(t *testing.T) {
	require.Panics(t, func() {
		deploy.NewRealDriver(deploy.RealConfig{
			Publisher: &recordPub{},
			Planner: func(_ context.Context, _ deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
				return deploy.Plan{}, nil, nil
			},
			Runner: nil,
		})
	})
}
