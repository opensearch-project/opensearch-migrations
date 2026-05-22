// Package versioncheck compares the build-time MA version against the
// latest published GitHub release, with a local cache to avoid hitting
// GitHub on every TUI launch.
//
// UX §0.5 (locked rule r6): the TUI binary's main.MAVersion is the
// single source of truth for which MA chart/CFN it ships. The version
// check is purely informational — it surfaces "update available" via
// the broker so the welcome page can render a dialog, but it never
// changes which artifacts the TUI fetches. Operators upgrade by
// downloading a new TUI binary, never by the TUI itself rewriting its
// version.
//
// Cache: <UserCacheDir>/opensearch-migration-assistant/version.json,
// TTL 24h. A stale-but-readable cache keeps the welcome page snappy
// even when offline. A corrupt cache is treated as absent (we re-fetch).
//
// The fetch is the caller's concern — versioncheck only handles the
// pure parts: semver Compare, IsUpdateAvailable (which is a one-liner
// over Compare but explicit so callers don't reinvent it inconsistently),
// and the on-disk cache. The HTTP call to GitHub lives in cmd/tui or a
// thin client elsewhere; tests stay hermetic.
package versioncheck
