package artifacts

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// ----------------------------------------------------------------------------
// FakeSource — scripted test double.
// ----------------------------------------------------------------------------

type fakeSource struct{ in []Artifact }

// NewFakeSource returns a Source that returns the given slice. The
// slice is defensively copied on every All() call so callers can't
// poison subsequent reads by mutating returned entries.
func NewFakeSource(in []Artifact) Source {
	cp := make([]Artifact, len(in))
	copy(cp, in)
	return &fakeSource{in: cp}
}

func (f *fakeSource) All() []Artifact {
	out := make([]Artifact, len(f.in))
	copy(out, f.in)
	return out
}

func (f *fakeSource) ByName(name string) (Artifact, bool) {
	for _, a := range f.in {
		if a.Name == name {
			return a, true
		}
	}
	return Artifact{}, false
}

// ----------------------------------------------------------------------------
// DirectorySource — production. Reads <workdir>/artifacts/.
//
// One disk scan + SHA computation per artifact at construction time.
// All()/ByName() are O(n)/O(n) over the cached slice afterwards. We
// don't watch the directory; if marelease re-publishes during a run,
// the operator is expected to restart the TUI (it already does on
// version bumps).
//
// We sort by filename so golden tests render deterministically.
// Subdirs (.cache especially) are skipped — only regular files and
// symlinks-to-files are surfaced. Hidden dotfiles (.DS_Store, etc.)
// are skipped per the documented "every entry is consumed" contract.
// ----------------------------------------------------------------------------

type directorySource struct {
	entries []Artifact
}

// NewDirectorySource scans dir once and returns a Source. Returns
// an error if dir doesn't exist or isn't a directory. A successful
// scan with zero matching files returns an empty source (nil error).
func NewDirectorySource(dir string) (Source, error) {
	fi, err := os.Stat(dir)
	if err != nil {
		return nil, fmt.Errorf("artifacts: stat %s: %w", dir, err)
	}
	if !fi.IsDir() {
		return nil, fmt.Errorf("artifacts: %s is not a directory", dir)
	}

	ents, err := os.ReadDir(dir)
	if err != nil {
		return nil, fmt.Errorf("artifacts: readdir %s: %w", dir, err)
	}

	var out []Artifact
	for _, e := range ents {
		name := e.Name()
		if strings.HasPrefix(name, ".") {
			// Hidden files (.DS_Store, .gitkeep, .cache as a hidden
			// subdir) are not artifacts.
			continue
		}
		full := filepath.Join(dir, name)

		// Resolve through symlinks so SHA reflects content. Stat (not
		// Lstat) follows symlinks; if the target is a dir, skip.
		st, err := os.Stat(full)
		if err != nil {
			// Broken symlink — skip rather than fail. The operator will
			// see it absent on the review page.
			continue
		}
		if st.IsDir() {
			continue
		}

		sum, size, err := hashFile(full)
		if err != nil {
			return nil, fmt.Errorf("artifacts: hash %s: %w", full, err)
		}

		out = append(out, Artifact{
			Name:   name,
			Path:   full,
			SHA256: sum,
			Size:   size,
		})
	}

	// Sort by Name for deterministic UI rendering.
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })

	return &directorySource{entries: out}, nil
}

func (d *directorySource) All() []Artifact {
	out := make([]Artifact, len(d.entries))
	copy(out, d.entries)
	return out
}

func (d *directorySource) ByName(name string) (Artifact, bool) {
	for _, a := range d.entries {
		if a.Name == name {
			return a, true
		}
	}
	return Artifact{}, false
}

// hashFile streams the file body into a SHA-256 hasher and returns
// (hex sum, size, error). Streaming avoids loading the whole 50MB
// helm chart into RAM just to fingerprint it.
func hashFile(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()

	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}
