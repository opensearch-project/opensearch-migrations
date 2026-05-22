package marelease

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/log"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/workdir"
)

// ---------------------------------------------------------------------
// Public surface
// ---------------------------------------------------------------------

// Sentinel errors. Callers MUST use errors.Is to test these.
var (
	// ErrNotFound is returned by a Fetcher implementation for a 404
	// response. The Resolver treats it as a permission-to-try-the-
	// next-source signal, not a hard error.
	ErrNotFound = errors.New("marelease: artifact not found at source")

	// ErrNoSource fires when ALL sources (release asset, raw repo)
	// return ErrNotFound. UX §0.7: "Hard fail. No fallback to main.
	// No fallback to latest." — this is that hard fail.
	ErrNoSource = errors.New("marelease: no source served the artifact")

	// ErrSHAMismatch fires when the downloaded body's SHA-256 disagrees
	// with the published .sha256 companion, OR when a cached file's
	// SHA disagrees with the network SHA on a re-resolve. UX §0.7:
	// hard fail, no auto-retry, error message hints at --reset-cache.
	ErrSHAMismatch = errors.New("marelease: artifact SHA mismatch (re-run with --reset-cache or report as a non-determinism bug)")

	// ErrUnsupportedPlatform fires when running on a platform whose
	// symlink semantics this package doesn't support (currently:
	// Windows). The TUI's goreleaser only builds linux/darwin so
	// this is informational.
	ErrUnsupportedPlatform = errors.New("marelease: symlink-based CAS is unix-only")
)

// Fetcher is the HTTP boundary. cmd/tui wires *HTTPFetcher in
// production; tests wire a fake.
//
// Implementations MUST return ErrNotFound (not a wrapped one) for 404s
// so the Resolver can route them to the next source in the resolution
// chain. Other errors propagate as-is.
type Fetcher interface {
	Get(ctx context.Context, url string) ([]byte, error)
}

// Config configures the Resolver.
type Config struct {
	// Workdir is the per-session workdir absolute path; CAS dirs are
	// created underneath it.
	Workdir string

	// Version is the MA version, with the leading "v" (e.g. "v3.2.1").
	// This pins which release the Resolver fetches from. UX §0.5
	// makes this the build-time MAVersion.
	Version string

	// Fetcher performs the HTTP GETs. Required.
	Fetcher Fetcher

	// Owner / Repo override the GitHub coordinates for tests. Empty
	// strings use the production defaults.
	Owner string
	Repo  string

	// Logger is the slog logger to use; defaults to the package
	// logger (internal/log). Tests override to a discard logger if
	// they don't care about WARN traces.
	Logger *slog.Logger
}

// Defaults for production.
const (
	defaultOwner = "opensearch-project"
	defaultRepo  = "opensearch-migrations"
)

// Resolver resolves MA artifact names to local on-disk paths via a
// content-addressable cache. Safe for concurrent use across goroutines
// (callers serialize via Bubble Tea's update loop in practice; we don't
// add a mutex because every operation is idempotent: redundant work is
// fine, contradictory work is impossible because the CAS path is
// SHA-keyed).
type Resolver struct {
	cfg Config
	log *slog.Logger
}

// NewResolver constructs a Resolver. Panics if Workdir, Version, or
// Fetcher is empty/nil — those are programmer errors at the call site
// (cmd/tui/main.go) and silent acceptance would make the failure
// surface as a confusing mid-deploy error instead of a clean startup
// crash.
func NewResolver(c Config) *Resolver {
	if c.Workdir == "" {
		panic("marelease: NewResolver requires Workdir")
	}
	if c.Version == "" {
		panic("marelease: NewResolver requires Version")
	}
	if c.Fetcher == nil {
		panic("marelease: NewResolver requires Fetcher")
	}
	if c.Owner == "" {
		c.Owner = defaultOwner
	}
	if c.Repo == "" {
		c.Repo = defaultRepo
	}
	logger := c.Logger
	if logger == nil {
		logger = log.L().With("component", "marelease")
	}
	return &Resolver{cfg: c, log: logger}
}

// Resolve fetches the named artifact (e.g. "helm-chart-3.2.1.tgz") and
// returns the local path of a symlink the caller can read or hand to a
// subprocess.
//
// On every call:
//
//  1. Fetch the .sha256 companion from the release-asset URL; if it
//     404s, fall back to the raw-repo URL (and log the fallback).
//     If both 404, return ErrNoSource.
//
//  2. If <workdir>/.cache/<sha>/<name> already exists and its hash
//     matches, ensure the artifacts/<name> symlink points at it and
//     return. NO body download.
//
//  3. Otherwise fetch the body from the same source class that served
//     the .sha256, verify the SHA, write to .cache/<sha>/<name>
//     atomically, repoint the symlink, and return.
//
// Any SHA mismatch is hard-fail (ErrSHAMismatch).
func (r *Resolver) Resolve(ctx context.Context, name string) (string, error) {
	if runtime.GOOS == "windows" {
		return "", ErrUnsupportedPlatform
	}
	if name == "" {
		return "", errors.New("marelease: artifact name is required")
	}

	// 1. Resolve the SHA via the source chain.
	wantSHA, srcKind, err := r.resolveSHA(ctx, name)
	if err != nil {
		return "", err
	}

	casDir := filepath.Join(r.cfg.Workdir, ".cache", wantSHA)
	casFile := filepath.Join(casDir, name)
	linkDir := filepath.Join(r.cfg.Workdir, "artifacts")
	linkPath := filepath.Join(linkDir, name)

	// 2. Cache hit?
	if existing, ok, err := readIfMatches(casFile, wantSHA); err != nil {
		return "", err
	} else if ok {
		_ = existing // bytes not needed; presence + match is enough
		if err := ensureSymlink(linkDir, linkPath, casFile); err != nil {
			return "", err
		}
		r.log.Debug("artifact cache hit", "name", name, "sha", wantSHA)
		return linkPath, nil
	}

	// 3. Cache miss. Fetch the body from the same source class.
	body, err := r.fetchBody(ctx, name, srcKind)
	if err != nil {
		return "", err
	}

	gotSHA := sha256Hex(body)
	if gotSHA != wantSHA {
		return "", fmt.Errorf("%w: name=%s want=%s got=%s",
			ErrSHAMismatch, name, wantSHA, gotSHA)
	}

	if err := os.MkdirAll(casDir, 0o755); err != nil {
		return "", fmt.Errorf("marelease: mkdir CAS %s: %w", casDir, err)
	}
	if err := workdir.WriteFileAtomic(casFile, body, 0o644); err != nil {
		return "", err
	}
	if err := ensureSymlink(linkDir, linkPath, casFile); err != nil {
		return "", err
	}
	r.log.Info("artifact fetched", "name", name, "sha", wantSHA, "source", srcKind, "bytes", len(body))
	return linkPath, nil
}

// ---------------------------------------------------------------------
// Resolution chain
// ---------------------------------------------------------------------

// sourceKind identifies which URL pattern served an artifact. Used to
// keep the body fetch on the same source as the SHA fetch (a release
// retag between the two would be caught by SHA mismatch, but staying
// consistent reduces operator confusion in postmortems).
type sourceKind int

const (
	srcRelease sourceKind = iota
	srcRaw
)

func (s sourceKind) String() string {
	switch s {
	case srcRelease:
		return "release-asset"
	case srcRaw:
		return "raw-repo"
	default:
		return "unknown"
	}
}

// resolveSHA finds the published SHA for the artifact, walking the
// source chain. Returns the SHA, the source it came from, or an error.
func (r *Resolver) resolveSHA(ctx context.Context, name string) (string, sourceKind, error) {
	// 1. Release asset.
	relURL := r.releaseURL(name + ".sha256")
	body, err := r.cfg.Fetcher.Get(ctx, relURL)
	switch {
	case err == nil:
		sum, perr := parseSHA256File(body, name)
		if perr != nil {
			return "", 0, fmt.Errorf("marelease: parse %s.sha256: %w", name, perr)
		}
		return sum, srcRelease, nil
	case errors.Is(err, ErrNotFound):
		// fall through to raw
	default:
		return "", 0, err
	}

	// 2. Raw repo (logged at WARN per UX §0.7).
	rawURL := r.rawURL(name + ".sha256")
	r.log.Warn("release asset missing, falling back to raw repo",
		"artifact", name, "version", r.cfg.Version)
	body, err = r.cfg.Fetcher.Get(ctx, rawURL)
	switch {
	case err == nil:
		sum, perr := parseSHA256File(body, name)
		if perr != nil {
			return "", 0, fmt.Errorf("marelease: parse %s.sha256 (raw): %w", name, perr)
		}
		return sum, srcRaw, nil
	case errors.Is(err, ErrNotFound):
		return "", 0, fmt.Errorf("%w: artifact=%s version=%s",
			ErrNoSource, name, r.cfg.Version)
	default:
		return "", 0, err
	}
}

// fetchBody fetches the artifact body from the same source class that
// served the SHA.
func (r *Resolver) fetchBody(ctx context.Context, name string, kind sourceKind) ([]byte, error) {
	var url string
	switch kind {
	case srcRelease:
		url = r.releaseURL(name)
	case srcRaw:
		url = r.rawURL(name)
	default:
		return nil, fmt.Errorf("marelease: unknown source kind: %d", kind)
	}
	body, err := r.cfg.Fetcher.Get(ctx, url)
	if err != nil {
		return nil, fmt.Errorf("marelease: fetch %s: %w", url, err)
	}
	return body, nil
}

func (r *Resolver) releaseURL(name string) string {
	return fmt.Sprintf("https://github.com/%s/%s/releases/download/%s/%s",
		r.cfg.Owner, r.cfg.Repo, r.cfg.Version, name)
}

func (r *Resolver) rawURL(name string) string {
	return fmt.Sprintf("https://raw.githubusercontent.com/%s/%s/%s/release-artifacts/%s",
		r.cfg.Owner, r.cfg.Repo, r.cfg.Version, name)
}

// ---------------------------------------------------------------------
// CAS helpers
// ---------------------------------------------------------------------

// readIfMatches reads casFile and returns its contents iff its SHA
// matches wantSHA. Returns (nil, false, nil) if the file is absent or
// its SHA does not match (the caller handles the mismatch as a cache
// miss when the SHA was independently sourced; a true tampering case
// is handled separately by the caller routing through ErrSHAMismatch).
func readIfMatches(casFile, wantSHA string) ([]byte, bool, error) {
	data, err := os.ReadFile(casFile)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return nil, false, nil
	case err != nil:
		return nil, false, fmt.Errorf("marelease: read CAS %s: %w", casFile, err)
	}
	got := sha256Hex(data)
	if got != wantSHA {
		// The cache file at .cache/<wantSHA>/<name> exists but its
		// actual contents hash to something else. This is corruption
		// (hand-edit, torn write outside our atomic path, hash
		// collision is implausible). Hard-fail per UX §0.7.
		return nil, false, fmt.Errorf("%w: cas=%s want=%s got=%s",
			ErrSHAMismatch, casFile, wantSHA, got)
	}
	return data, true, nil
}

// ensureSymlink makes linkPath point at target, atomically. If the
// link exists with the wrong target, it is replaced.
func ensureSymlink(linkDir, linkPath, target string) error {
	if err := os.MkdirAll(linkDir, 0o755); err != nil {
		return fmt.Errorf("marelease: mkdir artifacts dir %s: %w", linkDir, err)
	}
	// Fast path: link already points at the right place.
	if cur, err := os.Readlink(linkPath); err == nil && cur == target {
		return nil
	}
	// Replace by symlinking through a temp name and rename.
	tmp := linkPath + ".tmp-link"
	_ = os.Remove(tmp)
	if err := os.Symlink(target, tmp); err != nil {
		return fmt.Errorf("marelease: symlink %s -> %s: %w", tmp, target, err)
	}
	if err := os.Rename(tmp, linkPath); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("marelease: rename symlink %s -> %s: %w", tmp, linkPath, err)
	}
	return nil
}

// parseSHA256File parses a typical sha256sum-format file: one or more
// lines of "<hex>  <filename>". We accept any line whose filename
// matches the artifact (basename) and return the hex sum from that line.
//
// We do NOT just strip the leading 64 chars — files in this format
// often have a leading "*" or " " separator and a trailing filename,
// and silently chopping risks accepting a malformed file as valid.
func parseSHA256File(data []byte, artifactName string) (string, error) {
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) == 0 {
			continue
		}
		hexSum := fields[0]
		if len(hexSum) != 64 {
			continue
		}
		if _, err := hex.DecodeString(hexSum); err != nil {
			continue
		}
		// If a filename is present, prefer the line whose filename
		// matches (basename match). Otherwise accept the first valid
		// line (single-artifact .sha256 files).
		if len(fields) == 1 {
			return strings.ToLower(hexSum), nil
		}
		if filepath.Base(fields[len(fields)-1]) == artifactName {
			return strings.ToLower(hexSum), nil
		}
	}
	return "", fmt.Errorf("no usable hex line for %s", artifactName)
}

// sha256Hex returns the lowercase hex SHA-256 of data.
func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}
