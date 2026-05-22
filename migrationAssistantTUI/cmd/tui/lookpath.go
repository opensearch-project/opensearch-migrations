package main

// lookpath.go — resolves the agent binary path for syscall.Exec.
//
// Per cmd/tui/AGENTS.md "syscall.Exec contract": this MUST be
// invoked before tea.Quit is committed. Failure here means the user
// stays in the TUI with an error message rather than getting
// stranded with no shell.

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

// resolveHandoffBin returns an absolute path to the agent binary the
// caller asked to exec. The argument may be:
//
//   - empty           → error (programmer bug)
//   - absolute path   → must exist; returned verbatim
//   - bare name / rel → resolved via PATH (exec.LookPath)
//
// Callers MUST treat any non-nil error as a hard refusal to hand off:
// keep the TUI alive and display the error.
func resolveHandoffBin(want string) (string, error) {
	if want == "" {
		return "", errors.New("handoff: empty agent binary (programmer bug)")
	}

	if filepath.IsAbs(want) {
		fi, err := os.Stat(want)
		if err != nil {
			return "", fmt.Errorf("handoff: stat %q: %w", want, err)
		}
		if fi.IsDir() {
			return "", fmt.Errorf("handoff: %q is a directory, not an executable", want)
		}
		return want, nil
	}

	resolved, err := exec.LookPath(want)
	if err != nil {
		return "", fmt.Errorf("handoff: lookpath %q: %w", want, err)
	}
	// LookPath may return a relative result for cwd-relative inputs;
	// canonicalize so syscall.Exec gets an absolute path either way.
	abs, err := filepath.Abs(resolved)
	if err != nil {
		return "", fmt.Errorf("handoff: abs %q: %w", resolved, err)
	}
	return abs, nil
}
