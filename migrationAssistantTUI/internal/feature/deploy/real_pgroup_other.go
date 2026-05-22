//go:build !unix

package deploy

import "os/exec"

// configureProcessGroup is a no-op on non-Unix platforms. The TUI does
// not officially support Windows (real_exec_test.go skips on windows),
// but we keep these stubs so the package builds on all GOOS for
// developer-tooling purposes (gopls, IDE indexing, cross-builds).
func configureProcessGroup(_ *exec.Cmd) {}

// killProcessGroup falls back to killing only the leader. On Windows
// the equivalent would be a Job Object; we'd add it if/when we ship
// there. The TUI does not target Windows.
func killProcessGroup(c *exec.Cmd) error {
	if c.Process == nil {
		return nil
	}
	return c.Process.Kill()
}
