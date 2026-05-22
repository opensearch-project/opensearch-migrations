package versioncheck

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/mod/semver"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/workdir"
)

// cacheFile is the on-disk cache name. Sits inside
// <UserCacheDir>/opensearch-migration-assistant/ alongside the log
// (same dir, different file).
const cacheFile = "version.json"

// ErrNoCache means the cache is absent OR present-but-corrupt. Callers
// treat both identically: re-fetch from GitHub.
var ErrNoCache = errors.New("versioncheck: no usable cache")

// Record is the cached version-check result.
type Record struct {
	// LatestVersion is the most recent published MA version we know
	// about. Stored as the original string so we round-trip exactly
	// what GitHub returned.
	LatestVersion string `json:"latest_version"`

	// FetchedAt is the wall-clock time at which we fetched. Stored
	// as RFC3339 in JSON for human inspection.
	FetchedAt time.Time `json:"fetched_at"`
}

// IsFresh reports whether the record is within ttl of now. Clock is
// injected so callers can drive deterministic tests.
func (r Record) IsFresh(now time.Time, ttl time.Duration) bool {
	return now.Sub(r.FetchedAt) < ttl
}

// Compare returns -1/0/+1 a-vs-b in semver order.
//
// Both arguments are normalized:
//   - leading "v" is tolerated but optional.
//   - empty string or non-semver is an error (we never silently treat
//     a malformed local version as equal to the remote).
//
// Pre-release ordering follows SemVer 2.0.0 (e.g. 3.2.1-rc.1 < 3.2.1).
func Compare(a, b string) (int, error) {
	na, err := normalize(a)
	if err != nil {
		return 0, fmt.Errorf("versioncheck: compare a=%q: %w", a, err)
	}
	nb, err := normalize(b)
	if err != nil {
		return 0, fmt.Errorf("versioncheck: compare b=%q: %w", b, err)
	}
	return semver.Compare(na, nb), nil
}

// IsUpdateAvailable reports whether remote is strictly newer than local.
// Errors propagate from Compare (a malformed input MUST surface, never
// be silently coerced to "no update").
func IsUpdateAvailable(local, remote string) (bool, error) {
	cmp, err := Compare(local, remote)
	if err != nil {
		return false, err
	}
	return cmp < 0, nil
}

// Save writes the record to <dir>/version.json atomically.
//
// dir is typically <UserCacheDir>/opensearch-migration-assistant/. The
// caller is responsible for MkdirAll'ing it before calling Save.
func Save(dir string, r Record) error {
	data, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return fmt.Errorf("versioncheck: marshal: %w", err)
	}
	return workdir.WriteFileAtomic(filepath.Join(dir, cacheFile), data, 0o644)
}

// Load reads <dir>/version.json. Returns ErrNoCache if the file is
// absent or corrupt — callers always handle these the same way
// (re-fetch from GitHub).
func Load(dir string) (Record, error) {
	path := filepath.Join(dir, cacheFile)
	data, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return Record{}, ErrNoCache
	case err != nil:
		return Record{}, fmt.Errorf("versioncheck: read %s: %w", path, err)
	}

	var r Record
	if err := json.Unmarshal(data, &r); err != nil {
		// Corrupt cache is treated as absent — informational signal,
		// not a hard failure.
		return Record{}, ErrNoCache
	}
	if r.LatestVersion == "" {
		return Record{}, ErrNoCache
	}
	return r, nil
}

// normalize prepends "v" if absent so semver.Compare accepts the input,
// and validates the result is a valid semver. We accept "3.2.1" and
// "v3.2.1" interchangeably because operators copy-paste both styles
// from GitHub release pages.
func normalize(v string) (string, error) {
	v = strings.TrimSpace(v)
	if v == "" {
		return "", errors.New("empty version")
	}
	if !strings.HasPrefix(v, "v") {
		v = "v" + v
	}
	if !semver.IsValid(v) {
		return "", fmt.Errorf("not a valid semver: %s", v)
	}
	return v, nil
}
