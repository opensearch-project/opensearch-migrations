package marelease_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/marelease"
)

// ---------------------------------------------------------------------
// Test helpers: a fake Fetcher whose responses are scripted by URL
// substring. Counts hits per URL so we can assert "we did NOT re-fetch
// on the second Resolve."
// ---------------------------------------------------------------------

type fakeFetcher struct {
	// responses keyed by URL substring -> body or error.
	body  map[string][]byte
	fail  map[string]error
	calls atomic.Int32
	hits  map[string]*atomic.Int32
}

func newFakeFetcher() *fakeFetcher {
	return &fakeFetcher{
		body: map[string][]byte{},
		fail: map[string]error{},
		hits: map[string]*atomic.Int32{},
	}
}

func (f *fakeFetcher) addBody(urlSub string, b []byte) {
	f.body[urlSub] = b
	f.hits[urlSub] = &atomic.Int32{}
}

func (f *fakeFetcher) addFail(urlSub string, err error) {
	f.fail[urlSub] = err
	f.hits[urlSub] = &atomic.Int32{}
}

func (f *fakeFetcher) Get(_ context.Context, url string) ([]byte, error) {
	f.calls.Add(1)

	// Pick the LONGEST matching key — most-specific URL wins. This
	// makes the fake deterministic regardless of map iteration order.
	var bestSub string
	var fromBody bool
	for sub := range f.body {
		if strings.Contains(url, sub) && len(sub) > len(bestSub) {
			bestSub, fromBody = sub, true
		}
	}
	for sub := range f.fail {
		if strings.Contains(url, sub) && len(sub) > len(bestSub) {
			bestSub, fromBody = sub, false
		}
	}
	if bestSub == "" {
		return nil, errors.New("fake fetcher: unhandled URL: " + url)
	}
	f.hits[bestSub].Add(1)
	if fromBody {
		return f.body[bestSub], nil
	}
	return nil, f.fail[bestSub]
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// makeWorkdir creates a workdir tree under t.TempDir() and returns its
// absolute path. The workdir already exists; .cache and artifacts
// subdirs are created by Resolve as needed.
func makeWorkdir(t *testing.T) string {
	t.Helper()
	wd := filepath.Join(t.TempDir(), "opensearch-migration-111122223333-us-east-1")
	require.NoError(t, os.MkdirAll(wd, 0o755))
	return wd
}

// scriptRelease wires the fake fetcher with happy-path release-asset
// responses for the named artifact. Returns (body, sha256-hex).
//
// Substrings include the version path segment because the fake matches
// by URL substring; without it the release-asset URL and its .sha256
// would both ambiguously match the bare "/releases/download/".
func scriptRelease(f *fakeFetcher, version, artifactName string, body []byte) string {
	sum := sha256Hex(body)
	bodySub := "/releases/download/" + version + "/" + artifactName
	shaSub := bodySub + ".sha256"
	f.addBody(bodySub, body)
	f.addBody(shaSub, []byte(sum+"  "+artifactName+"\n"))
	return sum
}

// ---------------------------------------------------------------------

// TestResolve_Windows_Unsupported — symlink-based CAS is unix-only.
// Windows operators get a clear error rather than mysterious "Resolve
// succeeded but artifact path doesn't work."
func TestResolve_Windows_Unsupported(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("only meaningful on Windows; on unix we exercise the happy path elsewhere")
	}
	wd := makeWorkdir(t)
	r := marelease.NewResolver(marelease.Config{
		Workdir: wd, Version: "v3.2.1", Fetcher: newFakeFetcher(),
	})
	_, err := r.Resolve(context.Background(), "helm-chart-3.2.1.tgz")
	require.ErrorIs(t, err, marelease.ErrUnsupportedPlatform)
}

// TestResolve_ReleaseAssetHit — happy path. Release-asset URL returns
// the body, .sha256 URL returns matching SHA, file lands in
// .cache/<sha>/<name>, and the artifacts/<name> symlink points at it.
func TestResolve_ReleaseAssetHit(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink-based CAS not supported on Windows")
	}
	wd := makeWorkdir(t)

	body := []byte("FAKE HELM CHART CONTENTS")
	f := newFakeFetcher()
	wantSHA := scriptRelease(f, "v3.2.1", "helm-chart-3.2.1.tgz", body)

	r := marelease.NewResolver(marelease.Config{
		Workdir: wd, Version: "v3.2.1", Fetcher: f,
	})

	got, err := r.Resolve(context.Background(), "helm-chart-3.2.1.tgz")
	require.NoError(t, err)

	// Path is the symlink: <wd>/artifacts/helm-chart-3.2.1.tgz
	require.Equal(t, filepath.Join(wd, "artifacts", "helm-chart-3.2.1.tgz"), got)

	// Symlink resolves to the CAS file.
	target, err := os.Readlink(got)
	require.NoError(t, err)
	require.Contains(t, target, wantSHA, "symlink target should embed the SHA")

	// File contents match.
	contents, err := os.ReadFile(got)
	require.NoError(t, err)
	require.Equal(t, body, contents)

	// CAS file exists at the canonical path.
	casPath := filepath.Join(wd, ".cache", wantSHA, "helm-chart-3.2.1.tgz")
	st, err := os.Stat(casPath)
	require.NoError(t, err)
	require.False(t, st.IsDir())
}

// TestResolve_Idempotent — second Resolve with cached file MUST NOT
// re-fetch the body (it MAY re-fetch the .sha256 to verify currency,
// but the body fetch is the expensive one). The design says "Re-fetches
// verify the existing SHA before re-downloading."
func TestResolve_Idempotent(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink-based CAS not supported on Windows")
	}
	wd := makeWorkdir(t)

	body := []byte("FAKE HELM CHART CONTENTS")
	f := newFakeFetcher()
	scriptRelease(f, "v3.2.1", "helm-chart-3.2.1.tgz", body)

	r := marelease.NewResolver(marelease.Config{
		Workdir: wd, Version: "v3.2.1", Fetcher: f,
	})

	// First resolve populates the cache.
	_, err := r.Resolve(context.Background(), "helm-chart-3.2.1.tgz")
	require.NoError(t, err)

	totalAfterFirst := f.calls.Load()
	require.Greater(t, totalAfterFirst, int32(0))

	// Second resolve should be cheap. We allow at most one network
	// call (the .sha256 verification); the body fetch must not happen.
	_, err = r.Resolve(context.Background(), "helm-chart-3.2.1.tgz")
	require.NoError(t, err)
	delta := f.calls.Load() - totalAfterFirst
	require.LessOrEqual(t, delta, int32(1),
		"second resolve should make at most 1 network call (the .sha256 verification); got %d", delta)
}

// TestResolve_FallbackToRawRepo — release-asset returns 404; raw-repo
// hit succeeds. The fallback MUST be logged at WARN (we can't easily
// assert log output without a custom sink, so we instead assert the
// hits counter shows we tried the release asset first then the raw URL).
func TestResolve_FallbackToRawRepo(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink-based CAS not supported on Windows")
	}
	wd := makeWorkdir(t)

	body := []byte("FAKE CFN TEMPLATE")
	f := newFakeFetcher()
	// Release asset 404s.
	f.addFail("/releases/download/", marelease.ErrNotFound)
	// Raw-repo path serves the body and a matching .sha256 from the
	// same tag-pinned URL pattern. Note the substrings include the
	// version + release-artifacts path so longest-match disambiguates
	// the body URL from the .sha256 URL deterministically.
	sum := sha256Hex(body)
	f.addBody("/v3.2.1/release-artifacts/cfn-template.yaml", body)
	f.addBody("/v3.2.1/release-artifacts/cfn-template.yaml.sha256",
		[]byte(sum+"  cfn-template.yaml\n"))

	r := marelease.NewResolver(marelease.Config{
		Workdir: wd, Version: "v3.2.1", Fetcher: f,
	})

	got, err := r.Resolve(context.Background(), "cfn-template.yaml")
	require.NoError(t, err)

	contents, err := os.ReadFile(got)
	require.NoError(t, err)
	require.Equal(t, body, contents)

	// Assert we hit BOTH the release asset URL (failed) AND the raw URL.
	require.Greater(t, f.hits["/releases/download/"].Load(), int32(0),
		"release asset should have been attempted first")
	require.Greater(t, f.hits["/v3.2.1/release-artifacts/cfn-template.yaml.sha256"].Load(), int32(0),
		"raw repo should have been attempted as fallback")
}

// TestResolve_HardFail_NoSource — both release asset and raw repo
// return ErrNotFound. Resolve hard-fails with ErrNoSource. No retries.
func TestResolve_HardFail_NoSource(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink-based CAS not supported on Windows")
	}
	wd := makeWorkdir(t)

	f := newFakeFetcher()
	f.addFail("/releases/download/", marelease.ErrNotFound)
	f.addFail("/raw.githubusercontent.com/", marelease.ErrNotFound)

	r := marelease.NewResolver(marelease.Config{
		Workdir: wd, Version: "v3.2.1", Fetcher: f,
	})

	_, err := r.Resolve(context.Background(), "helm-chart-3.2.1.tgz")
	require.Error(t, err)
	require.ErrorIs(t, err, marelease.ErrNoSource)
}

// TestResolve_HardFail_SHAMismatch — body downloads fine but the
// .sha256 companion disagrees. Hard fail, no auto-retry, no fallback
// to "main", with a message hinting at --reset-cache.
func TestResolve_HardFail_SHAMismatch(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink-based CAS not supported on Windows")
	}
	wd := makeWorkdir(t)

	body := []byte("FAKE HELM CHART CONTENTS")
	f := newFakeFetcher()
	// Body URL serves real body.
	f.addBody("/releases/download/v3.2.1/helm-chart-3.2.1.tgz", body)
	// SHA URL serves a WRONG sum — release was retagged.
	wrongSum := sha256Hex([]byte("DIFFERENT BYTES"))
	f.addBody("/releases/download/v3.2.1/helm-chart-3.2.1.tgz.sha256",
		[]byte(wrongSum+"  helm-chart-3.2.1.tgz\n"))

	r := marelease.NewResolver(marelease.Config{
		Workdir: wd, Version: "v3.2.1", Fetcher: f,
	})

	_, err := r.Resolve(context.Background(), "helm-chart-3.2.1.tgz")
	require.Error(t, err)
	require.ErrorIs(t, err, marelease.ErrSHAMismatch)
	require.Contains(t, err.Error(), "reset-cache",
		"error message must hint at --reset-cache (UX §0.7)")
}

// TestResolve_RejectsEmptyName — programmer-error guard.
func TestResolve_RejectsEmptyName(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink-based CAS not supported on Windows")
	}
	wd := makeWorkdir(t)
	r := marelease.NewResolver(marelease.Config{
		Workdir: wd, Version: "v3.2.1", Fetcher: newFakeFetcher(),
	})
	_, err := r.Resolve(context.Background(), "")
	require.Error(t, err)
}

// TestNewResolver_RejectsBadConfig — empty workdir or version is a
// programmer error and must surface immediately, not at first Resolve.
func TestNewResolver_RejectsBadConfig(t *testing.T) {
	require.Panics(t, func() {
		marelease.NewResolver(marelease.Config{Version: "v3.2.1", Fetcher: newFakeFetcher()})
	})
	require.Panics(t, func() {
		marelease.NewResolver(marelease.Config{Workdir: "/tmp/x", Fetcher: newFakeFetcher()})
	})
	require.Panics(t, func() {
		marelease.NewResolver(marelease.Config{Workdir: "/tmp/x", Version: "v3.2.1"})
	})
}

// TestResolve_StaleCacheReverified — on the second Resolve, if the
// cached file's actual SHA disagrees with the .sha256 from the network,
// hard fail with ErrSHAMismatch. Catches "operator manually corrupted
// the cache" or "concurrent write left a torn file" cases.
func TestResolve_StaleCacheReverified(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink-based CAS not supported on Windows")
	}
	wd := makeWorkdir(t)

	body := []byte("FAKE HELM CHART CONTENTS")
	f := newFakeFetcher()
	wantSHA := scriptRelease(f, "v3.2.1", "helm-chart-3.2.1.tgz", body)

	r := marelease.NewResolver(marelease.Config{
		Workdir: wd, Version: "v3.2.1", Fetcher: f,
	})

	// Populate cache.
	_, err := r.Resolve(context.Background(), "helm-chart-3.2.1.tgz")
	require.NoError(t, err)

	// Corrupt the cache file in place. The CAS path is deterministic.
	casPath := filepath.Join(wd, ".cache", wantSHA, "helm-chart-3.2.1.tgz")
	require.NoError(t, os.WriteFile(casPath, []byte("CORRUPTED"), 0o644))

	// Second resolve must catch the corruption and hard-fail.
	_, err = r.Resolve(context.Background(), "helm-chart-3.2.1.tgz")
	require.Error(t, err)
	require.ErrorIs(t, err, marelease.ErrSHAMismatch)
}
