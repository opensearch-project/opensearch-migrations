package deploy_test

import (
	"context"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
)

// These tests exercise the production SubprocessRunner against real
// system binaries. Skipped on Windows (we never ship there for the TUI)
// and on environments where /bin/sh isn't available — keeps the
// test suite honest about what runs in CI without flaking on dev
// laptops missing exotic tools.

func TestExecCommandRunner_Run_StreamsLinesAndExitsZero(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("/bin/sh not available on windows")
	}
	r := deploy.ExecCommandRunner{}

	var mu sync.Mutex
	var lines []string

	err := r.Run(context.Background(), "phase-x", "/bin/sh", []string{"-c", "printf 'one\\ntwo\\nthree\\n'"}, func(s string) {
		mu.Lock()
		defer mu.Unlock()
		lines = append(lines, s)
	})
	require.NoError(t, err)
	require.Equal(t, []string{"one", "two", "three"}, lines)
}

func TestExecCommandRunner_Run_NonZeroExitReturnsError(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("/bin/sh not available on windows")
	}
	r := deploy.ExecCommandRunner{}

	err := r.Run(context.Background(), "phase-x", "/bin/sh", []string{"-c", "echo failing >&2; exit 7"}, func(string) {})
	require.Error(t, err)
}

func TestExecCommandRunner_Run_ContextCancelKillsProcess(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("/bin/sh not available on windows")
	}
	r := deploy.ExecCommandRunner{}

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(50 * time.Millisecond)
		cancel()
	}()

	// `sleep 5` would overshoot the test timeout if cancel didn't kill it.
	start := time.Now()
	err := r.Run(ctx, "phase-x", "/bin/sh", []string{"-c", "sleep 5"}, func(string) {})
	elapsed := time.Since(start)

	require.Error(t, err)
	require.Less(t, elapsed, 2*time.Second, "ctx cancel must terminate the process; elapsed=%v", elapsed)
}

func TestExecCommandRunner_Run_MergesStderrIntoLines(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("/bin/sh not available on windows")
	}
	r := deploy.ExecCommandRunner{}

	var mu sync.Mutex
	var lines []string

	err := r.Run(context.Background(), "phase-x", "/bin/sh", []string{"-c", "echo out; echo err >&2"}, func(s string) {
		mu.Lock()
		defer mu.Unlock()
		lines = append(lines, s)
	})
	require.NoError(t, err)
	joined := strings.Join(lines, "|")
	require.Contains(t, joined, "out")
	require.Contains(t, joined, "err")
}
