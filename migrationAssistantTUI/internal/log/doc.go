// Package log provides the migration-assistant TUI's file-only logger.
//
// The TUI cannot write to stdout while the alt-screen is active (DESIGN §2.R3),
// so all diagnostic output goes through this package. The logger is a thin
// wrapper around log/slog with three contracts:
//
//   - Log file path resolution is deterministic:
//     <UserCacheDir>/opensearch-migration-assistant/log/tui.log
//     with a <TempDir>/ma-cache fallback if UserCacheDir is unavailable.
//
//   - The log file is truncated on every launch. Diagnostics are about
//     "what happened during this run", not "what happened across all runs."
//     A stale log from a previous run cannot mask a fresh failure.
//
//   - Every Setup call returns a Cleanup that closes the file handle. The
//     caller is required to defer it (cmd/tui/main.go does).
package log
