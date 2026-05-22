package agents

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// ----------------------------------------------------------------------------
// FakeDetector — scripted test double.
//
// Same Adjustment-E pattern as deploy.FakeDriver: the test specifies
// the exact agents to return, the detector replays them. Used by every
// page-level test so the welcome/handoff pages render predictably
// without depending on what's installed on the dev/CI box.
// ----------------------------------------------------------------------------

// FakeScript is the recipe for a FakeDetector.
type FakeScript struct {
	// Agents is the slice Detect returns.
	Agents []Agent
	// DetectError, if non-nil, is returned in place of Agents.
	DetectError error
}

type fakeDetector struct{ s FakeScript }

// NewFakeDetector returns a Detector that replays the given script.
func NewFakeDetector(s FakeScript) Detector { return &fakeDetector{s: s} }

func (f *fakeDetector) Detect(ctx context.Context) ([]Agent, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if f.s.DetectError != nil {
		return nil, f.s.DetectError
	}
	if len(f.s.Agents) == 0 {
		return nil, nil
	}
	out := make([]Agent, len(f.s.Agents))
	copy(out, f.s.Agents)
	return out, nil
}

// ----------------------------------------------------------------------------
// PathDetector — production. Walks $PATH for a known set of agent names.
// ----------------------------------------------------------------------------

// DefaultAgentNames is the canonical list of agents the TUI knows how
// to hand off to. Updated as the ecosystem grows. Stable enough that
// page rendering tests can rely on the order.
//
// Order matters: this is the order Detect returns matches in. Tests
// and golden files key off it.
var DefaultAgentNames = []string{
	"claude",
	"codex",
	"kiro",
	"opencode",
	"gemini",
	"q",
}

// PathDetectorConfig is the constructor input. Zero value uses
// DefaultAgentNames and skips version probing — a no-args
// NewPathDetector(PathDetectorConfig{}) is a sensible production
// default.
type PathDetectorConfig struct {
	// Names is the agent names to look for. Empty slice means use
	// DefaultAgentNames.
	Names []string

	// VersionProbe, if true, runs `<binary> --version` per detected
	// agent and parses out a version string. Slow (one subprocess per
	// agent), so the welcome page enables it but the handoff page
	// (which has the data already) leaves it false.
	VersionProbe bool

	// VersionProbeTimeout caps each per-binary --version invocation.
	// Zero means use the package default (500ms). A hung binary must
	// not block detection; the timeout applies even when the caller's
	// context has no deadline.
	VersionProbeTimeout time.Duration
}

const defaultVersionProbeTimeout = 500 * time.Millisecond

type pathDetector struct{ cfg PathDetectorConfig }

// NewPathDetector returns the production Detector.
func NewPathDetector(cfg PathDetectorConfig) Detector {
	if len(cfg.Names) == 0 {
		cfg.Names = DefaultAgentNames
	}
	if cfg.VersionProbeTimeout <= 0 {
		cfg.VersionProbeTimeout = defaultVersionProbeTimeout
	}
	return &pathDetector{cfg: cfg}
}

func (p *pathDetector) Detect(ctx context.Context) ([]Agent, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	pathEnv := os.Getenv("PATH")
	if pathEnv == "" {
		// PATH unset is a "successfully looked, found nothing" state,
		// not an error: the contract on Detector says the UI should
		// not surface a warning for empty PATH.
		return nil, nil
	}
	dirs := strings.Split(pathEnv, string(os.PathListSeparator))

	var out []Agent
	for _, name := range p.cfg.Names {
		// Per-name cancel check — if a slow VersionProbe ran on the
		// previous iteration and ctx died meanwhile, stop early.
		if err := ctx.Err(); err != nil {
			return out, err
		}
		full := lookInDirs(name, dirs)
		if full == "" {
			continue
		}
		ag := Agent{Name: name, Path: full}
		if p.cfg.VersionProbe {
			ag.Version = probeVersion(ctx, full, p.cfg.VersionProbeTimeout)
		}
		out = append(out, ag)
	}
	return out, nil
}

// lookInDirs resolves `name` against the given PATH entries WITHOUT
// touching the global os.LookPath cache. We scan dirs in order and
// return the first executable hit. Returns "" if not found.
//
// We don't use os/exec.LookPath because:
//   - it consults the real env's PATH, not our explicit slice (we
//     could re-set os.Setenv("PATH", …) but that races other tests);
//   - on Linux it doesn't check the executable bit reliably across
//     bind mounts.
func lookInDirs(name string, dirs []string) string {
	for _, d := range dirs {
		if d == "" {
			continue
		}
		candidate := filepath.Join(d, name)
		fi, err := os.Stat(candidate)
		if err != nil || fi.IsDir() {
			continue
		}
		// Executable bit check — same heuristic as exec.LookPath uses.
		if runtime.GOOS == "windows" {
			// We don't actually ship Windows; if we did we'd check
			// PATHEXT. Skip safely.
			return candidate
		}
		if fi.Mode().Perm()&0o111 == 0 {
			continue
		}
		return candidate
	}
	return ""
}

// probeVersion runs `bin --version` with a timeout and returns the
// first line of output. Returns "" on any failure (timeout, non-zero
// exit, garbled output) — never errors out of the detector.
//
// The first line is a deliberately conservative parse: every agent we
// know prints something like "claude version 1.4.2" or "kiro 0.7.0"
// on the first line. Downstream (handoffbrief) extracts a semver
// substring if it needs to compare.
func probeVersion(ctx context.Context, bin string, timeout time.Duration) string {
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	cmd := exec.CommandContext(probeCtx, bin, "--version")
	out, err := cmd.Output()
	if err != nil {
		// Common path: --version not supported, returned non-zero,
		// or hit the timeout. Treat as "version unknown."
		var execErr *exec.ExitError
		_ = errors.As(err, &execErr)
		return ""
	}
	line := strings.SplitN(strings.TrimSpace(string(out)), "\n", 2)[0]
	return line
}
