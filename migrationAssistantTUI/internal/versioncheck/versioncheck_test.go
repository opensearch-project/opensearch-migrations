package versioncheck_test

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/versioncheck"
)

// --- Compare ----------------------------------------------------------

// TestCompare_Standard — basic semver ordering. Compare returns
// negative/zero/positive in the math/big style.
func TestCompare_Standard(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"3.2.1", "3.2.1", 0},
		{"3.2.1", "3.2.2", -1},
		{"3.2.2", "3.2.1", +1},
		{"3.2.0", "3.10.0", -1}, // numeric, not lexical
		{"3.2.1", "4.0.0", -1},
		{"4.0.0", "3.99.99", +1},
		// Leading-v is tolerated either way.
		{"v3.2.1", "3.2.1", 0},
		{"3.2.1", "v3.2.1", 0},
	}
	for _, c := range cases {
		got, err := versioncheck.Compare(c.a, c.b)
		require.NoError(t, err, "%s vs %s", c.a, c.b)
		require.Equal(t, c.want, sign(got), "%s vs %s", c.a, c.b)
	}
}

// TestCompare_Prerelease — pre-release versions sort before their
// release counterpart per SemVer 2.0.0.
func TestCompare_Prerelease(t *testing.T) {
	got, err := versioncheck.Compare("3.2.1-rc.1", "3.2.1")
	require.NoError(t, err)
	require.Equal(t, -1, sign(got))

	got, err = versioncheck.Compare("3.2.1-rc.1", "3.2.1-rc.2")
	require.NoError(t, err)
	require.Equal(t, -1, sign(got))
}

// TestCompare_Malformed — a non-semver string is an error, not a
// silent zero. We never want a malformed local MAVersion to claim
// it's "equal" to the remote.
func TestCompare_Malformed(t *testing.T) {
	_, err := versioncheck.Compare("not-a-version", "3.2.1")
	require.Error(t, err)

	_, err = versioncheck.Compare("3.2.1", "")
	require.Error(t, err)
}

// TestIsUpdateAvailable — convenience wrapper. Update means
// remote > local, strictly.
func TestIsUpdateAvailable(t *testing.T) {
	cases := []struct {
		local, remote string
		want          bool
	}{
		{"3.2.1", "3.2.1", false},
		{"3.2.1", "3.2.2", true},
		{"3.2.2", "3.2.1", false},
		{"3.2.1", "4.0.0", true},
	}
	for _, c := range cases {
		got, err := versioncheck.IsUpdateAvailable(c.local, c.remote)
		require.NoError(t, err)
		require.Equal(t, c.want, got, "local=%s remote=%s", c.local, c.remote)
	}
}

// --- Cache ------------------------------------------------------------

// TestCache_RoundTrip — Save then Load returns the same record, and
// the on-disk file is valid JSON the operator can `cat`.
func TestCache_RoundTrip(t *testing.T) {
	dir := t.TempDir()

	want := versioncheck.Record{
		LatestVersion: "3.5.0",
		FetchedAt:     time.Date(2026, 1, 15, 12, 0, 0, 0, time.UTC),
	}
	require.NoError(t, versioncheck.Save(dir, want))

	got, err := versioncheck.Load(dir)
	require.NoError(t, err)
	require.Equal(t, want.LatestVersion, got.LatestVersion)
	require.True(t, want.FetchedAt.Equal(got.FetchedAt),
		"fetched-at round-trip: want=%s got=%s", want.FetchedAt, got.FetchedAt)

	// Sanity-check the on-disk shape.
	raw, err := os.ReadFile(filepath.Join(dir, "version.json"))
	require.NoError(t, err)
	require.Contains(t, string(raw), "\"latest_version\"")
	require.Contains(t, string(raw), "\"fetched_at\"")
}

// TestCache_LoadMissing — absent cache file returns ErrNoCache.
// Callers (welcome page) treat this as "should fetch from GitHub".
func TestCache_LoadMissing(t *testing.T) {
	dir := t.TempDir()

	_, err := versioncheck.Load(dir)
	require.ErrorIs(t, err, versioncheck.ErrNoCache)
}

// TestCache_LoadCorrupt — a corrupt cache file is also reported as
// ErrNoCache (not a hard error). The version check is informational;
// a bad cache should not break the TUI launch.
func TestCache_LoadCorrupt(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t,
		os.WriteFile(filepath.Join(dir, "version.json"),
			[]byte("not json"), 0o644))

	_, err := versioncheck.Load(dir)
	require.ErrorIs(t, err, versioncheck.ErrNoCache)
}

// TestIsFresh — record is fresh if FetchedAt is within TTL of now.
// The clock is injected for testability.
func TestIsFresh(t *testing.T) {
	now := time.Date(2026, 1, 15, 12, 0, 0, 0, time.UTC)
	rec := versioncheck.Record{
		LatestVersion: "3.5.0",
		FetchedAt:     now.Add(-23 * time.Hour),
	}

	require.True(t, rec.IsFresh(now, 24*time.Hour),
		"23h-old record should be fresh under 24h TTL")

	rec.FetchedAt = now.Add(-25 * time.Hour)
	require.False(t, rec.IsFresh(now, 24*time.Hour),
		"25h-old record should be stale under 24h TTL")
}

// --- helpers ----------------------------------------------------------

// sign normalizes Compare's result to {-1, 0, +1} for table-driven assertions.
func sign(n int) int {
	switch {
	case n < 0:
		return -1
	case n > 0:
		return +1
	default:
		return 0
	}
}
