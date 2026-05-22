package log_test

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"

	mlog "github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/log"
)

// TestSetup_WritesToConfiguredPath asserts the basic happy path: Setup with
// an explicit dir returns a logger whose Info calls produce log lines in the
// canonical file location, and Cleanup closes the handle.
func TestSetup_WritesToConfiguredPath(t *testing.T) {
	dir := t.TempDir()

	cleanup, err := mlog.Setup(mlog.Options{Dir: dir})
	require.NoError(t, err)
	t.Cleanup(cleanup)

	mlog.L().Info("hello world", "k", "v")
	cleanup() // explicit close so the buffer is flushed before we read

	logPath := filepath.Join(dir, "tui.log")
	contents, err := os.ReadFile(logPath)
	require.NoError(t, err)

	require.Contains(t, string(contents), "hello world")
	require.Contains(t, string(contents), "k=v")
}

// TestSetup_TruncatesOnLaunch — the log file is overwritten, not appended,
// on each launch. A stale log line from a previous run must not appear
// after a fresh Setup.
func TestSetup_TruncatesOnLaunch(t *testing.T) {
	dir := t.TempDir()

	cleanup1, err := mlog.Setup(mlog.Options{Dir: dir})
	require.NoError(t, err)
	mlog.L().Info("first run")
	cleanup1()

	cleanup2, err := mlog.Setup(mlog.Options{Dir: dir})
	require.NoError(t, err)
	mlog.L().Info("second run")
	cleanup2()

	contents, err := os.ReadFile(filepath.Join(dir, "tui.log"))
	require.NoError(t, err)

	require.NotContains(t, string(contents), "first run",
		"truncate-on-launch failed: stale entries from prior run survived")
	require.Contains(t, string(contents), "second run")
}

// TestSetup_FallsBackToTempDir — when UserCacheDir is unavailable (or the
// caller passed an empty Dir AND the env says no cache), Setup falls back
// to a TempDir-based path rather than returning an error. The logger MUST
// always be usable; logging failures cannot block the TUI from starting.
func TestSetup_FallsBackToTempDir(t *testing.T) {
	// Pin Dir empty + UserCacheDirFn returning an error to force fallback.
	cleanup, err := mlog.Setup(mlog.Options{
		Dir:            "",
		UserCacheDirFn: func() (string, error) { return "", errors.New("no cache") },
	})
	require.NoError(t, err)
	t.Cleanup(cleanup)

	path := mlog.LogPath()
	require.NotEmpty(t, path)
	require.Contains(t, path, "ma-cache",
		"fallback path should sit under <TempDir>/ma-cache, got %q", path)
	require.True(t, strings.HasSuffix(path, "tui.log"),
		"log filename must be tui.log, got %q", path)
}

// TestL_BeforeSetup_DoesNotPanic — calling L() before Setup returns a no-op
// logger. This is necessary because package init in dependents may log,
// and we cannot order init across packages.
func TestL_BeforeSetup_DoesNotPanic(t *testing.T) {
	require.NotPanics(t, func() {
		mlog.L().Info("before-setup call must not panic")
	})
}

// TestSetup_CreatesDirIfMissing — the canonical path
// <UserCacheDir>/opensearch-migration-assistant/log/ may not exist on a
// fresh machine. Setup must MkdirAll it; failure to create is a real error.
func TestSetup_CreatesDirIfMissing(t *testing.T) {
	parent := t.TempDir()
	dir := filepath.Join(parent, "deeply", "nested", "log")
	require.NoDirExists(t, dir)

	cleanup, err := mlog.Setup(mlog.Options{Dir: dir})
	require.NoError(t, err)
	t.Cleanup(cleanup)

	require.DirExists(t, dir)
}
