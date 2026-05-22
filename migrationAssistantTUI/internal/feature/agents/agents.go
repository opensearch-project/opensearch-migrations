// Package agents declares the agent-detector contract. An "agent"
// here is an installed AI coding assistant (claude, kiro, codex,
// opencode, …) that the operator might use to drive the migration
// after the TUI hands off via HANDOFF.md + a skill bundle.
//
// Implementations of Detector live in this package alongside this
// file (next plan row); production uses exec.LookPath + version
// probes, fakes script a fixed installed-set for tests.
package agents

import "context"

// Agent is a single detected agent.
type Agent struct {
	// Name is the canonical agent identifier ("claude", "kiro",
	// "codex", "opencode", "amazon-q-developer", …). Stable across
	// versions; UI strings derive from it via a lookup table.
	Name string

	// Path is the absolute path to the executable (the result of
	// exec.LookPath). Empty if the agent was detected by some other
	// means (config file, env var) without a binary on PATH.
	Path string

	// Version is the detected version string ("1.4.2", "0.7.0-beta",
	// …) or empty if --version probe failed or returned non-semver.
	// We do NOT parse this further at the detection layer; downstream
	// (handoffbrief) decides what to do with version info.
	Version string
}

// Detector enumerates installed agents.
//
// Detect is the single method. It MUST be safe to call concurrently
// from goroutines (the deploy page may call it at the same time as
// the welcome page) and SHOULD complete in <500ms — slow probes are
// the detector's responsibility to budget.
//
// A nil error with empty result means "we successfully looked, found
// nothing." A non-nil error means "we couldn't look at all" (e.g.
// PATH unreadable); the UI surfaces that as a warning and proceeds
// with empty.
type Detector interface {
	Detect(ctx context.Context) ([]Agent, error)
}
