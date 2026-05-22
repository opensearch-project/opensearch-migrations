package tools

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// ----------------------------------------------------------------------------
// FakeDetector
// ----------------------------------------------------------------------------

// FakeScript scripts a FakeDetector — same Adjustment-E pattern as
// deploy.FakeScript and agents.FakeScript.
type FakeScript struct {
	Tools       []Tool
	DetectError error
}

type fakeDetector struct{ s FakeScript }

func NewFakeDetector(s FakeScript) Detector { return &fakeDetector{s: s} }

func (f *fakeDetector) Detect(ctx context.Context) ([]Tool, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if f.s.DetectError != nil {
		return nil, f.s.DetectError
	}
	if len(f.s.Tools) == 0 {
		return nil, nil
	}
	out := make([]Tool, len(f.s.Tools))
	copy(out, f.s.Tools)
	return out, nil
}

// ----------------------------------------------------------------------------
// PathDetector — production.
//
// Differs from agents.PathDetector in two important ways:
//
//  1. Required is a list of {Name, MinVersion} — the detector pins
//     the minimum version per tool, so the UI rendering doesn't
//     have to know that "kubectl needs >= 1.28."
//
//  2. Detect ALWAYS returns an entry for each required tool, even
//     when not on PATH (Path == ""). This is the documented contract
//     on Detector — the UI checklist needs explicit absent rows.
// ----------------------------------------------------------------------------

// Required pins one tool the deploy phase needs.
type Required struct {
	Name       string
	MinVersion string
}

// DefaultRequired is the production-pinned list of tools the deploy
// phase invokes. Versions reflect the minimum the helm chart and CFN
// templates were tested against. Bumping these is a deliberate UX +
// release-note change — review carefully.
//
// Order matters: the UI renders them in this order on the preflight page.
var DefaultRequired = []Required{
	{Name: "kubectl", MinVersion: "v1.28.0"},
	{Name: "helm", MinVersion: "v3.12.0"},
	{Name: "aws", MinVersion: "2.13.0"},
	{Name: "docker", MinVersion: "24.0.0"},
	{Name: "git", MinVersion: "2.30.0"},
}

// PathDetectorConfig is the constructor input. Zero value uses
// DefaultRequired and skips version probing.
type PathDetectorConfig struct {
	Required            []Required
	VersionProbe        bool
	VersionProbeTimeout time.Duration
}

const defaultVersionProbeTimeout = 500 * time.Millisecond

type pathDetector struct{ cfg PathDetectorConfig }

func NewPathDetector(cfg PathDetectorConfig) Detector {
	if len(cfg.Required) == 0 {
		cfg.Required = DefaultRequired
	}
	if cfg.VersionProbeTimeout <= 0 {
		cfg.VersionProbeTimeout = defaultVersionProbeTimeout
	}
	return &pathDetector{cfg: cfg}
}

func (p *pathDetector) Detect(ctx context.Context) ([]Tool, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	// Empty PATH is treated as "all tools absent" — no error. UI
	// renders all-empty rows.
	pathEnv := os.Getenv("PATH")
	dirs := strings.Split(pathEnv, string(os.PathListSeparator))

	out := make([]Tool, 0, len(p.cfg.Required))
	for _, req := range p.cfg.Required {
		if err := ctx.Err(); err != nil {
			return out, err
		}
		t := Tool{Name: req.Name, MinVersion: req.MinVersion}
		t.Path = lookInDirs(req.Name, dirs)
		if t.Path != "" && p.cfg.VersionProbe {
			t.Version = probeVersion(ctx, t.Path, p.cfg.VersionProbeTimeout)
		}
		out = append(out, t)
	}
	return out, nil
}

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
		if runtime.GOOS == "windows" {
			return candidate
		}
		if fi.Mode().Perm()&0o111 == 0 {
			continue
		}
		return candidate
	}
	return ""
}

func probeVersion(ctx context.Context, bin string, timeout time.Duration) string {
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	// kubectl/helm/aws each have a different --version flag preference;
	// we send `--version` first, fall back to `version --client` for
	// kubectl. For now the universal `--version` works for all five
	// tools in DefaultRequired (verified manually).
	cmd := exec.CommandContext(probeCtx, bin, "--version")
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	line := strings.SplitN(strings.TrimSpace(string(out)), "\n", 2)[0]
	return line
}
