package deploy

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os/exec"
)

// ExecStep is one subprocess invocation a Plan resolves to.
//
// The Planner produces parallel slices: a Plan (for the UI to render)
// and a []ExecStep (for the driver to actually run). They are 1:1 by
// position; the driver pairs them via PlanItem.Phase == ExecStep.Phase.
//
// We keep them as separate slices rather than embedding ExecStep in
// PlanItem because PlanItem is a UI shape (rendered on the review
// page) and shouldn't carry implementation details like the exact
// kubectl args — keeps the review page rendering stable across helm
// version bumps.
type ExecStep struct {
	// Phase identifies which PlanItem this step implements.
	Phase string

	// Command is the binary to invoke (must be on PATH at run time;
	// preflight already checked).
	Command string

	// Args is the argv (NOT including Command).
	Args []string

	// Env is appended to os.Environ() at exec time. Use sparingly —
	// most config flows through Args. Empty is the common case.
	Env []string

	// Cwd is the working directory for the subprocess. Empty means
	// the operator's current dir; the planner usually sets it to
	// the workdir's artifacts/ subdirectory.
	Cwd string
}

// SubprocessRunner abstracts subprocess invocation so the driver is
// testable without a real binary on PATH.
//
// Run invokes (cmd, args) for the named phase, calls onLine for each
// line of stdout/stderr (merged), and returns the exit error (nil on
// exit code 0). It MUST honour ctx — production wraps
// exec.CommandContext, fakes return ctx.Err() when ctx.Done() fires
// inside their replay loop.
type SubprocessRunner interface {
	Run(ctx context.Context, phase, cmd string, args []string, onLine func(string)) error
}

// RealConfig is the constructor input for NewRealDriver. Three
// required fields; nil any of them and NewRealDriver panics — same
// reasoning as FakeDriver: silent nil drops are the worst bug class.
type RealConfig struct {
	Publisher Publisher
	Planner   func(ctx context.Context, params Params) (Plan, []ExecStep, error)
	Runner    SubprocessRunner
}

// realDriver is the production-bound Driver. Calls Planner to compute
// the plan, then iterates ExecSteps invoking Runner for each, and
// publishes PhaseEvent values for every state transition.
type realDriver struct {
	pub     Publisher
	planner func(ctx context.Context, params Params) (Plan, []ExecStep, error)
	runner  SubprocessRunner
}

// NewRealDriver constructs the production Driver. Panics on any nil
// dependency.
func NewRealDriver(cfg RealConfig) Driver {
	if cfg.Publisher == nil {
		panic("deploy.NewRealDriver: Publisher must not be nil")
	}
	if cfg.Planner == nil {
		panic("deploy.NewRealDriver: Planner must not be nil")
	}
	if cfg.Runner == nil {
		panic("deploy.NewRealDriver: Runner must not be nil")
	}
	return &realDriver{
		pub:     cfg.Publisher,
		planner: cfg.Planner,
		runner:  cfg.Runner,
	}
}

// PreviewPlan calls the planner. Pure — no events published.
func (r *realDriver) PreviewPlan(ctx context.Context, params Params) (Plan, error) {
	if err := ctx.Err(); err != nil {
		return Plan{}, err
	}
	plan, _, err := r.planner(ctx, params)
	if err != nil {
		return Plan{}, err
	}
	return plan, nil
}

// Run computes the plan, then iterates each ExecStep:
//
//  1. Cancel-check ctx; if done, publish PhaseFailed(cancelled) for
//     in-flight phase and return ctx.Err().
//  2. Invoke Runner.Run; for each stdout line, publish a
//     PhaseRunning event with the line as Message.
//  3. On Runner success, publish PhaseCompleted for the phase.
//  4. On Runner error, publish PhaseFailed with the error string,
//     mark all remaining phases PhaseSkipped, return wrapped error.
//
// The "skipped" semantics match the docstring on PhaseSkipped: the
// planner decided the rest shouldn't run because an earlier phase
// failed. We do this in the driver (not the planner) because only
// the driver knows which phase failed at runtime.
func (r *realDriver) Run(ctx context.Context, params Params) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	_, steps, err := r.planner(ctx, params)
	if err != nil {
		return err
	}

	for i, step := range steps {
		// Per-step cancel check before we touch the runner.
		if err := ctx.Err(); err != nil {
			r.publishCancel(step.Phase, err)
			r.markRemainingSkipped(steps, i+1)
			return err
		}

		// Stream callback: publish a PhaseRunning event for each line.
		// We allocate the closure once per step so it captures the right
		// phase name.
		phase := step.Phase
		onLine := func(line string) {
			r.pub.Publish(PhaseEvent{
				Phase:   phase,
				Status:  PhaseRunning,
				Message: line,
			})
		}

		runErr := r.runner.Run(ctx, step.Phase, step.Command, step.Args, onLine)

		if errors.Is(runErr, context.Canceled) || errors.Is(runErr, context.DeadlineExceeded) {
			r.publishCancel(step.Phase, runErr)
			r.markRemainingSkipped(steps, i+1)
			return runErr
		}
		if runErr != nil {
			r.pub.Publish(PhaseEvent{
				Phase:   step.Phase,
				Status:  PhaseFailed,
				Message: runErr.Error(),
			})
			r.markRemainingSkipped(steps, i+1)
			return fmt.Errorf("deploy phase %q failed: %w", step.Phase, runErr)
		}

		r.pub.Publish(PhaseEvent{
			Phase:  step.Phase,
			Status: PhaseCompleted,
		})
	}
	return nil
}

func (r *realDriver) publishCancel(phase string, err error) {
	r.pub.Publish(PhaseEvent{
		Phase:   phase,
		Status:  PhaseFailed,
		Message: fmt.Sprintf("cancelled: %v", err),
	})
}

func (r *realDriver) markRemainingSkipped(steps []ExecStep, from int) {
	for j := from; j < len(steps); j++ {
		r.pub.Publish(PhaseEvent{
			Phase:  steps[j].Phase,
			Status: PhaseSkipped,
		})
	}
}

// ----------------------------------------------------------------------------
// ExecCommandRunner — the production SubprocessRunner. Wraps
// exec.CommandContext, merges stdout+stderr, calls onLine for each
// line. Lives in this same file so the deploy package is a single
// unit; no separate command-runner package needed at this scale.
// ----------------------------------------------------------------------------

// ExecCommandRunner is the production implementation of
// SubprocessRunner. Zero value is usable.
type ExecCommandRunner struct{}

// Run executes (cmd, args) under ctx, streaming merged stdout/stderr
// lines through onLine. Returns nil on exit 0; otherwise returns the
// underlying error from cmd.Wait. ctx cancellation propagates to the
// process via exec.CommandContext.
func (ExecCommandRunner) Run(ctx context.Context, _ string, cmd string, args []string, onLine func(string)) error {
	c := exec.CommandContext(ctx, cmd, args...)
	stdout, err := c.StdoutPipe()
	if err != nil {
		return err
	}
	c.Stderr = c.Stdout // merge — single line stream simplifies the UI
	if err := c.Start(); err != nil {
		return err
	}

	// Read until EOF or process exit; line-buffered.
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024) // helm output can be long
	for scanner.Scan() {
		// Defensive: don't block on onLine if ctx is dying.
		if err := ctx.Err(); err != nil {
			_ = c.Wait()
			return err
		}
		onLine(scanner.Text())
	}
	// Note: scanner.Err() is intentionally not surfaced as a Run error
	// when the process succeeded — partial-read failures are usually
	// downstream of the process exiting anyway, and Wait() has the
	// authoritative status.
	if err := c.Wait(); err != nil {
		return err
	}
	if scanErr := scanner.Err(); scanErr != nil && !errors.Is(scanErr, io.EOF) {
		return scanErr
	}
	return nil
}
