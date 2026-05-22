// Package skillkit installs an OpenSearch-Migrations agent skill
// bundle (UX.md §0.2, §12) into a workdir and rewrites it into the
// per-agent layout the user's chosen agent expects.
//
// Two design rules are load-bearing:
//
//  1. Open/closed for agents. New agent integrations register an
//     Adapter via Register at package init; Install dispatches
//     through the registry. No central switch on Agent.
//
//  2. Tar-safe. Every header is checked for path traversal,
//     absolute paths, link types (symlink/hardlink/device), and an
//     oversized-entry guard. A malicious bundle returns
//     ErrUnsafeTarEntry without touching the install destination —
//     extraction always lands in a temp directory under workdir,
//     and the atomic swap into the agent's destination only happens
//     if extraction completes cleanly.
package skillkit

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Agent identifies which on-disk layout to write.
type Agent string

const (
	AgentKiro       Agent = "kiro"
	AgentClaudeCode Agent = "claude-code"
)

// MaxEntrySize caps a single tar entry's uncompressed size. Past
// this we assume the bundle is malicious or corrupt — the legitimate
// skill-bundle is on the order of hundreds of KB.
const MaxEntrySize = 16 * 1024 * 1024

// ----------------------------------------------------------------------------
// Errors
// ----------------------------------------------------------------------------

// ErrUnsupportedAgent is returned by Install when the Agent has no
// registered Adapter.
var ErrUnsupportedAgent = errors.New("skillkit: unsupported agent")

// ErrUnsafeTarEntry is returned (wrapped) when a tar entry trips
// any of the safety checks: path traversal, absolute path, link or
// device type, or exceeds MaxEntrySize.
var ErrUnsafeTarEntry = errors.New("skillkit: unsafe tar entry")

// ----------------------------------------------------------------------------
// Adapter registry
// ----------------------------------------------------------------------------

// Adapter installs an extracted bundle (rooted at extractDir) into
// the agent's expected layout under workdir. Implementations are
// idempotent — they may overwrite a previous install.
type Adapter interface {
	// Agent identifies the adapter.
	Agent() Agent
	// Install copies/rewrites files from extractDir to workdir.
	// extractDir is a freshly-extracted, validated tar tree; the
	// adapter MUST NOT escape it. Workdir is the user's project root.
	Install(workdir, extractDir string) error
}

var registry = map[Agent]Adapter{}

// Register adds an adapter. Called from init() in adapter source files.
func Register(a Adapter) {
	registry[a.Agent()] = a
}

// Agents lists every registered Agent. Sorted for stable output.
func Agents() []Agent {
	out := make([]Agent, 0, len(registry))
	for k := range registry {
		out = append(out, k)
	}
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out
}

// ----------------------------------------------------------------------------
// Install — public entry point
// ----------------------------------------------------------------------------

// Install extracts bundleTar to a temp directory under workdir,
// validates every entry, then dispatches to the registered Adapter
// for agent. On any error, the temp directory is removed and the
// agent's destination layout is left untouched (atomic install).
func Install(workdir string, agent Agent, bundleTar string) error {
	adapter, ok := registry[agent]
	if !ok {
		return fmt.Errorf("%w: %q", ErrUnsupportedAgent, agent)
	}
	if _, err := os.Stat(bundleTar); err != nil {
		return fmt.Errorf("skillkit: tarball %q: %w", bundleTar, err)
	}
	if err := os.MkdirAll(workdir, 0o755); err != nil {
		return fmt.Errorf("skillkit: mkdir workdir: %w", err)
	}
	stage, err := os.MkdirTemp(workdir, ".skillkit-stage-*")
	if err != nil {
		return fmt.Errorf("skillkit: mkdtemp: %w", err)
	}
	// On any error past this point, drop the stage dir.
	defer os.RemoveAll(stage)

	if err := extractTarGzSafe(bundleTar, stage); err != nil {
		return err
	}
	return adapter.Install(workdir, stage)
}

// ----------------------------------------------------------------------------
// Tar-safe extraction
// ----------------------------------------------------------------------------

func extractTarGzSafe(src, dst string) error {
	f, err := os.Open(src)
	if err != nil {
		return fmt.Errorf("skillkit: open %q: %w", src, err)
	}
	defer f.Close()

	gz, err := gzip.NewReader(f)
	if err != nil {
		return fmt.Errorf("skillkit: gzip: %w", err)
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return fmt.Errorf("skillkit: tar: %w", err)
		}
		if err := validateHeader(h); err != nil {
			return err
		}
		out := filepath.Join(dst, filepath.Clean(h.Name))
		// Defense-in-depth: confirm the resolved target is still under dst.
		rel, err := filepath.Rel(dst, out)
		if err != nil || strings.HasPrefix(rel, "..") {
			return fmt.Errorf("%w: %q escapes destination", ErrUnsafeTarEntry, h.Name)
		}

		switch h.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(out, 0o755); err != nil {
				return fmt.Errorf("skillkit: mkdir %q: %w", out, err)
			}
		case tar.TypeReg, tar.TypeRegA:
			if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
				return fmt.Errorf("skillkit: mkdir parent of %q: %w", out, err)
			}
			fw, err := os.OpenFile(out, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
			if err != nil {
				return fmt.Errorf("skillkit: create %q: %w", out, err)
			}
			// io.LimitReader is the second-line size guard (header.Size
			// is checked in validateHeader; this stops a lying header).
			if _, err := io.Copy(fw, io.LimitReader(tr, MaxEntrySize+1)); err != nil {
				_ = fw.Close()
				return fmt.Errorf("skillkit: copy %q: %w", out, err)
			}
			if err := fw.Close(); err != nil {
				return fmt.Errorf("skillkit: close %q: %w", out, err)
			}
		}
	}
	return nil
}

func validateHeader(h *tar.Header) error {
	clean := filepath.Clean(h.Name)
	if filepath.IsAbs(clean) {
		return fmt.Errorf("%w: absolute path %q", ErrUnsafeTarEntry, h.Name)
	}
	if strings.HasPrefix(clean, "..") || strings.Contains(clean, string(filepath.Separator)+".."+string(filepath.Separator)) {
		return fmt.Errorf("%w: traversal %q", ErrUnsafeTarEntry, h.Name)
	}
	switch h.Typeflag {
	case tar.TypeDir, tar.TypeReg, tar.TypeRegA:
		// allowed
	case tar.TypeSymlink, tar.TypeLink:
		return fmt.Errorf("%w: link not allowed (%q -> %q)", ErrUnsafeTarEntry, h.Name, h.Linkname)
	default:
		return fmt.Errorf("%w: type %d not allowed (%q)", ErrUnsafeTarEntry, h.Typeflag, h.Name)
	}
	if h.Size > MaxEntrySize {
		return fmt.Errorf("%w: %q size %d > %d", ErrUnsafeTarEntry, h.Name, h.Size, MaxEntrySize)
	}
	return nil
}

// ----------------------------------------------------------------------------
// Helpers used by adapters
// ----------------------------------------------------------------------------

// copyTree mirrors src into dst, creating dst if needed. Files are
// copied with 0o644 permissions; directories with 0o755. dst is NOT
// removed first — adapters that want a clean slate should call
// os.RemoveAll(dst) themselves.
func copyTree(src, dst string) error {
	return filepath.Walk(src, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, p)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		return copyFile(p, target)
	})
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		return err
	}
	return out.Close()
}
