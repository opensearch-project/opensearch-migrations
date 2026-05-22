// Package deploy declares the migration-deploy driver contract.
// The driver is the ONLY place in the TUI that performs mutating
// operations against the operator's environment (CFN deploys, helm
// installs, kubectl applies). Everything else is read-only or
// confined to the workdir.
//
// This package defines:
//   - PhaseEvent       — one update from a running phase.
//   - PhaseStatus      — enum of event states.
//   - Plan / PlanItem  — the deploy plan the UI shows on the review
//     page before the operator commits.
//   - Driver           — the interface the UI talks to.
//
// Implementations live in this same package: RealDriver shells out
// to the operator's tools; FakeDriver replays a scripted event slice
// (Adjustment E in the design doc — the missing layer between unit
// tests and end-to-end harnesses).
package deploy

import "context"

// PhaseStatus is the lifecycle state of a single phase.
type PhaseStatus int

const (
	// PhasePending is the initial state — listed in the plan but
	// not yet started.
	PhasePending PhaseStatus = iota

	// PhaseRunning is "we've kicked off the underlying tool and
	// are streaming output." The UI renders this with a spinner.
	PhaseRunning

	// PhaseCompleted is terminal-success.
	PhaseCompleted

	// PhaseFailed is terminal-failure. The UI renders this red and
	// blocks remaining phases.
	PhaseFailed

	// PhaseSkipped is terminal-not-attempted. Used when an earlier
	// phase failed and the planner decided this one shouldn't run.
	PhaseSkipped
)

// String makes PhaseStatus printable for log lines and golden tests.
func (s PhaseStatus) String() string {
	switch s {
	case PhasePending:
		return "pending"
	case PhaseRunning:
		return "running"
	case PhaseCompleted:
		return "completed"
	case PhaseFailed:
		return "failed"
	case PhaseSkipped:
		return "skipped"
	default:
		return "unknown"
	}
}

// PhaseEvent is one update in the deploy stream.
//
// The driver publishes PhaseEvent values onto the pubsub.Broker; the
// UI subscribes via tea.Sub and renders. Stable wire shape: adding
// fields is fine (zero values render harmlessly), renaming is a
// breaking change for any future replay-from-disk feature.
type PhaseEvent struct {
	// Phase is the phase identifier ("cfn", "helm", "kubectl-apply",
	// "wait-for-rollout"). Stable; UI strings derive from it.
	Phase string

	// Status is the new state.
	Status PhaseStatus

	// Message is human-prose detail, optional. For PhaseRunning this
	// is typically a streamed stdout/stderr line; for PhaseFailed
	// this is the error summary the UI puts on the red banner.
	Message string
}

// PlanItem is one row in the to-be-executed deploy plan.
type PlanItem struct {
	// Phase matches the PhaseEvent.Phase string for the row.
	Phase string

	// Description is the human label ("Deploy CFN bootstrap
	// stack"). Stable across runs of the same MA version.
	Description string
}

// Plan is the ordered list of phases the deploy driver intends to
// execute. The review page renders this and demands the operator
// confirm before the driver Run() is allowed to proceed.
type Plan struct {
	Items []PlanItem
}

// Driver is the deploy interface.
//
// Lifecycle:
//
//  1. PreviewPlan(ctx, params) — pure, no side effects. Returns the
//     plan the UI shows on the review page. Reads cluster state etc.
//     for read-only calls.
//
//  2. Run(ctx, params) — actually executes the plan. Publishes
//     PhaseEvent values to the broker (driver was given a Publish
//     func at construction). Returns when terminal — either all
//     phases completed or one failed; partial-failure cleanup is
//     the operator's job (we surface the kubectl/helm command they
//     can run to rollback).
//
// Both methods MUST honour ctx cancellation: PreviewPlan returns
// ctx.Err(); Run cancels in-flight subprocesses and publishes a final
// PhaseFailed with status="cancelled" before returning.
type Driver interface {
	PreviewPlan(ctx context.Context, params Params) (Plan, error)
	Run(ctx context.Context, params Params) error
}

// Params is the input to PreviewPlan / Run. Stable struct so adding
// a field is a non-breaking change for both Real and Fake drivers.
type Params struct {
	// WorkdirPath is the absolute workdir path; the driver reads
	// artifacts/ for CFN templates / helm charts.
	WorkdirPath string

	// Account / Region constrain CFN + EKS calls.
	Account string
	Region  string

	// HelmRelease is the operator-chosen helm release name. Echoed
	// into kubectl --namespace and helm install args.
	HelmRelease string

	// EKSClusterName is the target cluster. Read-only by Driver
	// (set up out-of-band by an earlier CFN phase).
	EKSClusterName string
}
