package log

import (
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync/atomic"
)

// Options configures Setup. The zero value is "use UserCacheDir() with the
// canonical subpath, fall back to TempDir/ma-cache on error."
type Options struct {
	// Dir, when non-empty, overrides path resolution entirely. Tests use it
	// to redirect the log file at a t.TempDir(). cmd/tui/main does NOT set
	// it: it relies on Setup's deterministic resolution rules so the path
	// is the same one the operator sees in the status line.
	Dir string

	// UserCacheDirFn defaults to os.UserCacheDir. Tests inject a stub to
	// force the fallback path without touching real disk.
	UserCacheDirFn func() (string, error)

	// TempDirFn defaults to os.TempDir. Tests inject a stub to assert the
	// fallback path without depending on the host's real /tmp.
	TempDirFn func() string
}

// canonicalSubpath is the path under UserCacheDir that the TUI logs into.
// It is referenced by status-line text — change carefully.
const (
	canonicalSubpath = "opensearch-migration-assistant" + string(os.PathSeparator) + "log"
	fallbackSubpath  = "ma-cache"
	logFileName      = "tui.log"
)

// state holds the active logger and its underlying file handle. We use
// atomic.Pointer so concurrent reads (most commonly L() called from
// goroutines while Setup runs) don't race the slog construction.
type state struct {
	logger *slog.Logger
	file   *os.File
	path   string
}

var current atomic.Pointer[state]

// noop is returned by L() when Setup hasn't run yet. We don't want to
// panic on early diagnostic calls (package init in dependents may log,
// init order is not stable across packages).
var noop = slog.New(slog.NewTextHandler(io.Discard, nil))

// Setup opens (or truncates) the log file and installs a slog logger that
// L() will return. The returned cleanup func closes the file; callers are
// expected to defer it (cmd/tui/main does).
//
// Setup is intentionally NOT goroutine-safe with itself. It is called once
// from cmd/tui/main before any other package starts. Concurrent Setup calls
// from tests are safe in practice because each test uses its own dir and
// cleanup runs before the next test's Setup, but no contract is promised.
func Setup(opts Options) (cleanup func(), err error) {
	dir, err := resolveDir(opts)
	if err != nil {
		return nil, err
	}

	if mkErr := os.MkdirAll(dir, 0o755); mkErr != nil {
		return nil, fmt.Errorf("log: mkdir %s: %w", dir, mkErr)
	}

	path := filepath.Join(dir, logFileName)
	// O_TRUNC: every launch starts with a clean log. Diagnostics describe
	// THIS run; cross-run history is the user's terminal scroll buffer,
	// not our concern.
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
	if err != nil {
		return nil, fmt.Errorf("log: open %s: %w", path, err)
	}

	handler := slog.NewTextHandler(f, &slog.HandlerOptions{Level: slog.LevelDebug})
	st := &state{
		logger: slog.New(handler),
		file:   f,
		path:   path,
	}
	current.Store(st)

	return func() {
		// Cleanup is idempotent: tests call it explicitly to flush, then
		// t.Cleanup calls it again. Closing twice is fine because we clear
		// the atomic pointer and the second close on a *os.File returns an
		// error we don't propagate.
		old := current.Swap(nil)
		if old != nil && old.file != nil {
			_ = old.file.Sync()
			_ = old.file.Close()
		}
	}, nil
}

// L returns the active slog logger. Safe to call before Setup; returns a
// no-op writer in that case so package init code doesn't panic.
func L() *slog.Logger {
	st := current.Load()
	if st == nil {
		return noop
	}
	return st.logger
}

// LogPath returns the path of the active log file, or "" if Setup hasn't
// run yet. The TUI status line uses this so the operator can find the log
// without guessing.
func LogPath() string {
	st := current.Load()
	if st == nil {
		return ""
	}
	return st.path
}

// resolveDir applies the canonical-then-fallback path resolution.
//
//   1. Options.Dir if non-empty (test override).
//   2. UserCacheDir() / opensearch-migration-assistant / log
//   3. TempDir() / ma-cache
//
// Step 3 is deliberately NOT a real fallback for the canonical path — it
// is the *fallback location* when the host has no UserCacheDir available
// (rare, but possible on minimal containers or when HOME is unset).
func resolveDir(opts Options) (string, error) {
	if opts.Dir != "" {
		return opts.Dir, nil
	}

	cacheFn := opts.UserCacheDirFn
	if cacheFn == nil {
		cacheFn = os.UserCacheDir
	}

	if base, err := cacheFn(); err == nil && base != "" {
		return filepath.Join(base, canonicalSubpath), nil
	}

	tmpFn := opts.TempDirFn
	if tmpFn == nil {
		tmpFn = os.TempDir
	}

	return filepath.Join(tmpFn(), fallbackSubpath), nil
}
