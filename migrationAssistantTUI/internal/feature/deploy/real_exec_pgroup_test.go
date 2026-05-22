package deploy_test

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
)

// TestExecCommandRunner_Run_KillsBackgroundedGrandchild captures the
// dash-vs-bash CI failure mode: a process tree where the immediate child
// (sh) backgrounds a grandchild (sleep). On `exec.CommandContext` cancel,
// the default Cancel only SIGKILLs the leader; the orphaned grandchild
// keeps the inherited stdout pipe open, blocking cmd.Wait() until the
// natural sleep duration (5s observed in CI). The fix sets Setpgid so we
// can identify the pgroup, replaces Cancel with a pgroup-kill, and sets
// WaitDelay as a backstop. This test asserts the *outcome*: the
// grandchild PID disappears within 2s of cancel — independent of which
// shell `/bin/sh` resolves to.
//
// We discover the grandchild PID by having the script write its own
// PID to a file in tmpdir before sleeping, then checking /proc/<pid>
// after cancel.
func TestExecCommandRunner_Run_KillsBackgroundedGrandchild(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("/bin/sh not available on windows")
	}
	if runtime.GOOS != "linux" {
		// /proc/<pid> existence check is Linux-only. macOS has its own
		// process model; the production fix still applies, but we
		// can't observe it the same way. The original elapsed-time
		// assertion in TestExecCommandRunner_Run_ContextCancelKillsProcess
		// covers macOS.
		t.Skip("grandchild-pid observation requires /proc")
	}

	dir := t.TempDir()
	pidFile := filepath.Join(dir, "grandchild.pid")

	// The script: spawn a backgrounded `sh -c` grandchild that records
	// its own pid via $$ (reliable across bash and dash) and then
	// sleeps long. The leader execs into a foreground sleep so the
	// leader pid is well-defined. The grandchild stays in the leader's
	// process group (no setsid) — that's the exact dash topology that
	// triggered the CI failure: SIGKILL on the leader leaves the
	// grandchild orphaned but still in the same pgroup, holding the
	// inherited stdout pipe.
	script := `sh -c 'echo $$ > ` + pidFile + `; sleep 30' & exec sleep 30`

	r := deploy.ExecCommandRunner{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan error, 1)
	go func() {
		done <- r.Run(ctx, "phase-x", "/bin/sh", []string{"-c", script}, func(string) {})
	}()

	// Wait for the grandchild to record its PID. Up to 3s — the
	// `sh -c` grandchild needs to fork+exec+open file before we can
	// observe it. Generous bound so the test doesn't flake on a slow
	// CI runner.
	var grandchildPID int
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		b, err := os.ReadFile(pidFile)
		if err == nil && len(b) > 0 {
			s := strings.TrimSpace(string(b))
			if pid, perr := strconv.Atoi(s); perr == nil && pid > 0 {
				grandchildPID = pid
				break
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	require.NotZero(t, grandchildPID, "grandchild never recorded its PID")

	// Confirm grandchild is alive right now.
	require.True(t, procExists(grandchildPID), "grandchild not visible in /proc before cancel")

	// Now — and only now, with grandchild confirmed alive — cancel
	// and assert it dies within 2s. This is the regression guard:
	// unmodified code (CommandContext + leader-only Kill) leaves the
	// grandchild alive for the natural sleep duration (30s here).
	start := time.Now()
	cancel()

	gone := false
	for time.Since(start) < 2*time.Second {
		if !procExists(grandchildPID) {
			gone = true
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	require.True(t, gone, "grandchild PID %d still alive 2s after cancel — process tree leak", grandchildPID)

	// Run() must also return promptly (covered by elapsed in the
	// caller goroutine). 3s gives the implementation slack for
	// WaitDelay-driven pipe close.
	select {
	case err := <-done:
		require.Error(t, err, "Run() must return ctx error after cancel")
	case <-time.After(3 * time.Second):
		t.Fatal("Run() did not return within 3s of cancel")
	}
}

// procExists returns true if /proc/<pid> is currently a directory. On
// Linux this is the canonical liveness check that doesn't require
// signaling permissions.
func procExists(pid int) bool {
	_, err := os.Stat("/proc/" + strconv.Itoa(pid))
	return err == nil
}
