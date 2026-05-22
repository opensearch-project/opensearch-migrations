package launch

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/workdir"
)

// ---------------------------------------------------------------------
// Decide
// ---------------------------------------------------------------------

// Action is the categorical decision the state machine emits.
//
// The UI consumes Action and either proceeds (CreateFresh / ResumeExisting)
// or asks the operator for confirmation (ConfirmAdoptOrFresh). Prepare
// rejects ConfirmAdoptOrFresh — the UI must upgrade it to one of the
// terminal actions before the FS write.
type Action int

const (
	// ActionUnknown is the zero value; never returned by Decide.
	ActionUnknown Action = iota

	// ActionCreateFresh means "create the workdir from scratch, write
	// a fresh state file." Either no workdir existed, or the operator
	// asked to start over.
	ActionCreateFresh

	// ActionResumeExisting means "the existing state matches what we
	// expect; load it and resume on the page it claims."
	ActionResumeExisting

	// ActionConfirmAdoptOrFresh means "the workdir exists but the
	// state file doesn't or is unreadable." The UI shows a dialog
	// asking the operator to pick: adopt the existing dir (write a
	// fresh state, keep any cached artifacts) or restart fresh.
	ActionConfirmAdoptOrFresh
)

// String makes Action printable for log lines.
func (a Action) String() string {
	switch a {
	case ActionCreateFresh:
		return "create-fresh"
	case ActionResumeExisting:
		return "resume-existing"
	case ActionConfirmAdoptOrFresh:
		return "confirm-adopt-or-fresh"
	default:
		return "unknown"
	}
}

// Preference is the operator's startup preference. Default is
// PreferenceAuto: do whatever the state machine thinks best.
type Preference int

const (
	// PreferenceAuto lets Decide choose based on what's on disk.
	PreferenceAuto Preference = iota

	// PreferenceForceFresh tells Decide to ignore an existing matching
	// state and treat it as a fresh start. UX: invoked via the
	// "Start over" menu item or a --reset-cache CLI flag (future).
	PreferenceForceFresh
)

// Decide is the pure transition function. No FS access; no time; no
// random; deterministic for testing.
//
// Inputs:
//   - det: the workdir.DetectResult from a prior workdir.Detect call.
//     The caller (cmd/tui/main.go) is responsible for translating
//     workdir.ErrStateMismatch into a user-visible error before ever
//     reaching Decide; if Detect succeeded, the state (if present) is
//     known to match the requested account/region.
//   - pref: operator preference.
func Decide(det workdir.DetectResult, pref Preference) Action {
	if pref == PreferenceForceFresh {
		return ActionCreateFresh
	}
	if !det.Exists {
		return ActionCreateFresh
	}
	if det.HasState {
		return ActionResumeExisting
	}
	// Dir exists but no usable state — manual creation, partial
	// previous run, or corrupted state. Surface for the operator.
	return ActionConfirmAdoptOrFresh
}

// ---------------------------------------------------------------------
// Prepare
// ---------------------------------------------------------------------

// PrepareInput is the FS-side input. Path / Account / Region come
// from the workdir.Detect call that produced the DetectResult passed
// to Decide; we don't re-detect here.
type PrepareInput struct {
	Action  Action
	Path    string
	Account string
	Region  string
}

// PrepareResult is what the UI keeps around after prep succeeds.
type PrepareResult struct {
	// Path is the workdir on disk; same as input.
	Path string

	// State is the state document as it was written (CreateFresh) or
	// loaded (ResumeExisting).
	State workdir.State
}

// subdirs are the per-workdir directories that always exist after
// Prepare. .cache and artifacts are the marelease CAS layout; log is
// where internal/log writes ma-tui.log on next boot.
var subdirs = []string{".cache", "artifacts", "log"}

// Prepare performs the FS work selected by Decide. Returns the loaded
// or freshly-written state.
func Prepare(_ context.Context, in PrepareInput) (PrepareResult, error) {
	if in.Path == "" {
		return PrepareResult{}, errors.New("launch: PrepareInput.Path is required")
	}
	if in.Account == "" {
		return PrepareResult{}, errors.New("launch: PrepareInput.Account is required")
	}
	if in.Region == "" {
		return PrepareResult{}, errors.New("launch: PrepareInput.Region is required")
	}

	switch in.Action {
	case ActionCreateFresh:
		return prepareFresh(in)
	case ActionResumeExisting:
		return prepareResume(in)
	case ActionConfirmAdoptOrFresh:
		return PrepareResult{}, fmt.Errorf("launch: ActionConfirmAdoptOrFresh must be resolved by the UI before Prepare")
	default:
		return PrepareResult{}, fmt.Errorf("launch: unknown action %d", in.Action)
	}
}

func prepareFresh(in PrepareInput) (PrepareResult, error) {
	if err := os.MkdirAll(in.Path, 0o755); err != nil {
		return PrepareResult{}, fmt.Errorf("launch: mkdir workdir %s: %w", in.Path, err)
	}
	for _, sub := range subdirs {
		full := filepath.Join(in.Path, sub)
		if err := os.MkdirAll(full, 0o755); err != nil {
			return PrepareResult{}, fmt.Errorf("launch: mkdir %s: %w", full, err)
		}
	}
	state := workdir.State{Account: in.Account, Region: in.Region}
	if err := workdir.WriteState(in.Path, state); err != nil {
		return PrepareResult{}, err
	}
	return PrepareResult{Path: in.Path, State: state}, nil
}

func prepareResume(in PrepareInput) (PrepareResult, error) {
	// Resume tolerates missing subdirs (e.g. operator deleted .cache
	// to force a re-fetch) — recreate them.
	for _, sub := range subdirs {
		full := filepath.Join(in.Path, sub)
		if err := os.MkdirAll(full, 0o755); err != nil {
			return PrepareResult{}, fmt.Errorf("launch: mkdir %s: %w", full, err)
		}
	}
	state, err := workdir.ReadState(in.Path)
	if err != nil {
		return PrepareResult{}, err
	}
	if state.Account != in.Account || state.Region != in.Region {
		// Detect should have caught this; defense in depth.
		return PrepareResult{}, fmt.Errorf("%w: state(%s/%s) vs input(%s/%s)",
			workdir.ErrStateMismatch,
			state.Account, state.Region, in.Account, in.Region)
	}
	return PrepareResult{Path: in.Path, State: state}, nil
}

// ---------------------------------------------------------------------
// FetchArtifacts
// ---------------------------------------------------------------------

// ArtifactResolver is the interface launch needs from marelease — kept
// narrow so tests can stub it without depending on the full Resolver
// type (Adjustment C in spirit: per-feature minimal interfaces).
type ArtifactResolver interface {
	Resolve(ctx context.Context, name string) (string, error)
}

// FetchInput is the input to FetchArtifacts.
type FetchInput struct {
	Resolver ArtifactResolver
	Names    []string
}

// FetchArtifacts iterates over Names in order and asks the resolver
// for each. Returns name → path on success. Errors short-circuit:
// remaining names are NOT attempted, and partial state on disk is
// left in place (marelease's CAS makes that safe).
//
// Sequential by design: the WARN log lines from marelease's raw-repo
// fallback come out in a predictable order, which matters for
// postmortems. If we ever measure "fetch latency" as a real bottleneck
// we can parallelise here, but for 3 artifacts of <50MB each this is
// cheap.
func FetchArtifacts(ctx context.Context, in FetchInput) (map[string]string, error) {
	if in.Resolver == nil {
		panic("launch: FetchArtifacts requires Resolver")
	}
	if len(in.Names) == 0 {
		return nil, errors.New("launch: FetchArtifacts requires at least one name")
	}

	out := make(map[string]string, len(in.Names))
	for _, name := range in.Names {
		// Honour ctx cancellation between artifacts so a user
		// hitting Ctrl-C during prefetch returns promptly. The
		// resolver itself should also honour ctx; this is the
		// belt-and-braces guard.
		if err := ctx.Err(); err != nil {
			return out, err
		}
		path, err := in.Resolver.Resolve(ctx, name)
		if err != nil {
			return out, fmt.Errorf("launch: resolve %s: %w", name, err)
		}
		out[name] = path
	}
	return out, nil
}

// ---------------------------------------------------------------------
// ArtifactNames — the locked manifest
// ---------------------------------------------------------------------

// ArtifactNames returns the canonical list of artifact filenames the
// TUI fetches for a given MA semver (without leading "v"). The names
// embed the version so a stale operator-edited cache from a prior
// version is automatically not re-used.
//
// Adding an artifact requires updating BOTH this list AND the
// goreleaser publish step that uploads it (and its .sha256). The
// stable test pins the list so a one-sided change fails CI.
func ArtifactNames(semver string) []string {
	return []string{
		"cfn-template-" + semver + ".yaml",
		"helm-chart-" + semver + ".tgz",
		"skill-bundle-" + semver + ".zip",
	}
}
