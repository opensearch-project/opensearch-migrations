package workdir

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// stateFile is the canonical name of the per-workdir state document.
// The leading dot keeps it out of casual `ls` output but it's still
// inspectable by operators who go looking.
const stateFile = ".ma-state.json"

// dirPrefix is the workdir name prefix. UX §0.4 — load-bearing for
// account/region isolation.
const dirPrefix = "opensearch-migration-"

// State is the per-workdir resume document.
//
// JSON tags are part of the on-disk contract; renaming a field is a
// breaking change and must include a migration. New fields must be
// added with `omitempty` so older state files keep loading.
type State struct {
	Account string            `json:"account"`
	Region  string            `json:"region"`
	Page    string            `json:"page,omitempty"`
	Wizard  map[string]string `json:"wizard,omitempty"`
}

// DetectResult is what Detect returns to the caller.
type DetectResult struct {
	// Path is the conventional workdir path for the requested
	// account/region, regardless of whether it exists yet.
	Path string

	// Exists is true if Path is a directory on disk.
	Exists bool

	// HasState is true if a valid .ma-state.json was found AND it
	// matches the requested account/region. (A mismatched state
	// file produces an error from Detect rather than HasState=false.)
	HasState bool

	// State is the loaded state document; zero value if HasState is
	// false.
	State State
}

// Sentinel errors. Callers MUST use errors.Is to test these — the
// concrete types may be wrapped with %w for context.
var (
	// ErrStateMismatch fires when an existing .ma-state.json claims
	// a different account/region than the one Detect was asked
	// about. This is a hard failure; we never silently overwrite a
	// foreign workdir.
	ErrStateMismatch = errors.New("workdir: state account/region mismatch")

	// ErrStateCorrupt fires when .ma-state.json exists but is not
	// valid JSON or is missing required fields. Distinct from
	// ErrNoState so callers can route corruption differently
	// (operator action required) from absence (offer fresh start).
	ErrStateCorrupt = errors.New("workdir: state file corrupt")

	// ErrNoState fires when ReadState is called on a workdir with no
	// .ma-state.json. Distinct from os.ErrNotExist so the caller can
	// distinguish "this workdir is fresh" from "the disk is broken".
	ErrNoState = errors.New("workdir: no state file")
)

// Path computes the conventional workdir path. UX §0.4.
//
// Panics if account or region is empty — those are programmer errors
// upstream (config layer must populate both before calling here), and
// silent acceptance would yield collision-prone names like
// "opensearch-migration--us-east-1".
func Path(parent, account, region string) string {
	if account == "" || region == "" {
		panic("workdir: Path called with empty account or region")
	}
	return filepath.Join(parent, dirPrefix+account+"-"+region)
}

// Detect inspects the conventional workdir path for an existing dir
// and (optionally) state file. See DetectResult for fields.
//
// Errors:
//   - ErrStateMismatch — state file claims a different account/region.
//   - ErrStateCorrupt  — state file is unreadable as JSON.
//   - I/O errors from Stat are wrapped with %w.
func Detect(parent, account, region string) (DetectResult, error) {
	res := DetectResult{Path: Path(parent, account, region)}

	st, err := os.Stat(res.Path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return res, nil
	case err != nil:
		return res, fmt.Errorf("workdir: stat %s: %w", res.Path, err)
	case !st.IsDir():
		return res, fmt.Errorf("workdir: %s exists and is not a directory", res.Path)
	}
	res.Exists = true

	state, err := ReadState(res.Path)
	switch {
	case errors.Is(err, ErrNoState):
		return res, nil
	case errors.Is(err, ErrStateCorrupt):
		return res, err
	case err != nil:
		return res, err
	}

	if state.Account != account || state.Region != region {
		return res, fmt.Errorf("%w: file=%s/%s requested=%s/%s",
			ErrStateMismatch, state.Account, state.Region, account, region)
	}

	res.HasState = true
	res.State = state
	return res, nil
}

// ReadState loads .ma-state.json from the workdir.
//
// Returns ErrNoState if the file is absent, ErrStateCorrupt if it is
// present but not valid JSON, otherwise the parsed state.
func ReadState(workdir string) (State, error) {
	path := filepath.Join(workdir, stateFile)
	data, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return State{}, ErrNoState
	case err != nil:
		return State{}, fmt.Errorf("workdir: read %s: %w", path, err)
	}

	var s State
	if err := json.Unmarshal(data, &s); err != nil {
		return State{}, fmt.Errorf("%w: %v", ErrStateCorrupt, err)
	}
	return s, nil
}

// WriteState writes .ma-state.json atomically. The .tmp sibling is
// guaranteed cleaned up on success.
//
// JSON is indented for human inspection; the operator can `cat` the
// file to debug a stuck resume.
func WriteState(workdir string, s State) error {
	path := filepath.Join(workdir, stateFile)
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return fmt.Errorf("workdir: marshal state: %w", err)
	}
	return WriteFileAtomic(path, data, 0o644)
}

// WriteFileAtomic writes data to path through a temp sibling, fsyncs,
// then renames into place. If the operator Ctrl+C's between write and
// rename, the destination is untouched.
//
// What we deliberately do NOT do:
//
//   - fsync the parent directory after rename. POSIX requires this for
//     true durability across power loss, but the workdir state file is
//     advisory (a lost write costs the operator a wizard re-entry, not
//     deployed infrastructure). Skipping the dir fsync simplifies the
//     code and avoids a Windows-portability special case.
//
//   - MkdirAll on the parent. If the parent is missing, the workdir
//     setup path is broken upstream; we return the error instead of
//     hiding it.
//
// Failure modes:
//   - parent directory missing: error.
//   - any I/O failure: temp sibling is best-effort cleaned up before
//     returning the wrapped error.
func WriteFileAtomic(path string, data []byte, perm os.FileMode) (retErr error) {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, filepath.Base(path)+".*.tmp")
	if err != nil {
		return fmt.Errorf("workdir: create temp in %s: %w", dir, err)
	}
	tmpName := tmp.Name()

	// Best-effort cleanup of the temp file if anything below fails.
	// On the success path we Remove via Rename, so this is a no-op.
	defer func() {
		if retErr != nil {
			_ = os.Remove(tmpName)
		}
	}()

	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("workdir: write temp %s: %w", tmpName, err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("workdir: fsync temp %s: %w", tmpName, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("workdir: close temp %s: %w", tmpName, err)
	}
	if err := os.Chmod(tmpName, perm); err != nil {
		return fmt.Errorf("workdir: chmod temp %s: %w", tmpName, err)
	}
	if err := os.Rename(tmpName, path); err != nil {
		return fmt.Errorf("workdir: rename %s -> %s: %w", tmpName, path, err)
	}
	return nil
}
