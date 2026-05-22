package workdir_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/workdir"
)

// --- Path -------------------------------------------------------------

// TestPath_DeterministicNaming — UX §0.4: the workdir name is
// './opensearch-migration-<account>-<region>'. This is the contract on
// which workdir isolation rests; if the format ever drifts, two
// accounts could collide.
func TestPath_DeterministicNaming(t *testing.T) {
	got := workdir.Path("/work", "111122223333", "us-east-1")
	require.Equal(t, "/work/opensearch-migration-111122223333-us-east-1", got)
}

// TestPath_RejectsEmptyAccountOrRegion — empty values would produce a
// workdir name like 'opensearch-migration--us-east-1' which silently
// collides across accounts. Reject upstream.
func TestPath_RejectsEmptyAccountOrRegion(t *testing.T) {
	require.Panics(t, func() { workdir.Path("/work", "", "us-east-1") })
	require.Panics(t, func() { workdir.Path("/work", "111122223333", "") })
}

// --- Detect -----------------------------------------------------------

// TestDetect_NotPresent — a fresh parent dir reports the workdir as
// computable but not yet existing.
func TestDetect_NotPresent(t *testing.T) {
	parent := t.TempDir()

	res, err := workdir.Detect(parent, "111122223333", "us-east-1")

	require.NoError(t, err)
	require.False(t, res.Exists)
	require.Equal(t,
		filepath.Join(parent, "opensearch-migration-111122223333-us-east-1"),
		res.Path)
	require.False(t, res.HasState)
}

// TestDetect_PresentNoState — the workdir exists (e.g. the operator made
// it before quitting) but no .ma-state.json yet. Resume offer must NOT
// fire; we treat it as a fresh dir.
func TestDetect_PresentNoState(t *testing.T) {
	parent := t.TempDir()
	wd := filepath.Join(parent, "opensearch-migration-111122223333-us-east-1")
	require.NoError(t, os.MkdirAll(wd, 0o755))

	res, err := workdir.Detect(parent, "111122223333", "us-east-1")

	require.NoError(t, err)
	require.True(t, res.Exists)
	require.False(t, res.HasState)
}

// TestDetect_PresentWithMatchingState — state file matches the requested
// account/region. Resume is offered.
func TestDetect_PresentWithMatchingState(t *testing.T) {
	parent := t.TempDir()
	wd := filepath.Join(parent, "opensearch-migration-111122223333-us-east-1")
	require.NoError(t, os.MkdirAll(wd, 0o755))

	st := workdir.State{
		Account: "111122223333",
		Region:  "us-east-1",
		Page:    "wizard",
	}
	require.NoError(t, workdir.WriteState(wd, st))

	res, err := workdir.Detect(parent, "111122223333", "us-east-1")

	require.NoError(t, err)
	require.True(t, res.Exists)
	require.True(t, res.HasState)
	require.Equal(t, "wizard", res.State.Page)
}

// TestDetect_StateMismatchHardFails — a state file whose account or
// region disagrees with the requested pair must be hard-rejected. This
// is the test the design doc calls out as missing in the original
// branch. Two operators on one machine, two AWS accounts — the wrong
// state file silently loaded is a foot-cannon.
func TestDetect_StateMismatchHardFails(t *testing.T) {
	parent := t.TempDir()
	wd := filepath.Join(parent, "opensearch-migration-111122223333-us-east-1")
	require.NoError(t, os.MkdirAll(wd, 0o755))

	// State file claims a different account.
	bad := workdir.State{
		Account: "999999999999",
		Region:  "us-east-1",
		Page:    "wizard",
	}
	require.NoError(t, workdir.WriteState(wd, bad))

	_, err := workdir.Detect(parent, "111122223333", "us-east-1")

	require.Error(t, err)
	require.ErrorIs(t, err, workdir.ErrStateMismatch)
}

// TestDetect_CorruptStateIsError — a malformed .ma-state.json must
// surface as an error, not silently fall through to "fresh start".
// Silent fallthrough would erase the operator's prior progress.
func TestDetect_CorruptStateIsError(t *testing.T) {
	parent := t.TempDir()
	wd := filepath.Join(parent, "opensearch-migration-111122223333-us-east-1")
	require.NoError(t, os.MkdirAll(wd, 0o755))
	require.NoError(t,
		os.WriteFile(filepath.Join(wd, ".ma-state.json"),
			[]byte("not json {{{"), 0o644))

	_, err := workdir.Detect(parent, "111122223333", "us-east-1")

	require.Error(t, err)
	require.ErrorIs(t, err, workdir.ErrStateCorrupt)
}

// --- WriteFileAtomic --------------------------------------------------

// TestWriteFileAtomic_HappyPath — bytes land at the destination with
// the requested permission.
func TestWriteFileAtomic_HappyPath(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "out.bin")

	require.NoError(t, workdir.WriteFileAtomic(dst, []byte("hello"), 0o600))

	got, err := os.ReadFile(dst)
	require.NoError(t, err)
	require.Equal(t, "hello", string(got))

	if runtime.GOOS != "windows" {
		st, err := os.Stat(dst)
		require.NoError(t, err)
		require.Equal(t, os.FileMode(0o600), st.Mode().Perm())
	}
}

// TestWriteFileAtomic_OverwritesExisting — re-writing an existing file
// replaces its contents in one rename, never leaving a torn write.
func TestWriteFileAtomic_OverwritesExisting(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "state.json")
	require.NoError(t, os.WriteFile(dst, []byte("OLD"), 0o644))

	require.NoError(t, workdir.WriteFileAtomic(dst, []byte("NEW"), 0o644))

	got, err := os.ReadFile(dst)
	require.NoError(t, err)
	require.Equal(t, "NEW", string(got))
}

// TestWriteFileAtomic_NoTempLeftBehind — the .tmp sibling MUST be
// cleaned up after a successful write. Otherwise repeat writes leave
// orphan .tmp files cluttering the workdir, which the operator notices
// and worries about.
func TestWriteFileAtomic_NoTempLeftBehind(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "out.bin")

	require.NoError(t, workdir.WriteFileAtomic(dst, []byte("x"), 0o644))

	entries, err := os.ReadDir(dir)
	require.NoError(t, err)
	for _, e := range entries {
		require.NotContains(t, e.Name(), ".tmp",
			"atomic write left behind: %s", e.Name())
	}
}

// TestWriteFileAtomic_ParentMissingIsError — atomic write into a
// non-existent directory is an error (we do not silently create
// intermediate dirs; that's a separate concern).
func TestWriteFileAtomic_ParentMissingIsError(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "missing-subdir", "out.bin")

	err := workdir.WriteFileAtomic(dst, []byte("x"), 0o644)

	require.Error(t, err)
}

// --- ReadState / WriteState round-trip --------------------------------

// TestStateRoundTrip — WriteState followed by ReadState yields equal
// state. JSON tags are stable.
func TestStateRoundTrip(t *testing.T) {
	dir := t.TempDir()

	want := workdir.State{
		Account: "111122223333",
		Region:  "us-west-2",
		Page:    "deploy",
		Wizard: map[string]string{
			"sourceEngine": "elasticsearch-7.10",
			"targetEngine": "opensearch-2.17",
		},
	}
	require.NoError(t, workdir.WriteState(dir, want))

	got, err := workdir.ReadState(dir)
	require.NoError(t, err)
	require.Equal(t, want, got)
}

// TestReadState_Missing — reading a state from a workdir that has none
// returns ErrNoState (a sentinel), not a generic os.ErrNotExist. The
// caller distinguishes "fresh workdir" from "I/O failure".
func TestReadState_Missing(t *testing.T) {
	dir := t.TempDir()

	_, err := workdir.ReadState(dir)

	require.Error(t, err)
	require.ErrorIs(t, err, workdir.ErrNoState)
}

// TestWriteState_AtomicSemantics — assert that a partial write would
// not leak. Easiest: verify the on-disk file is valid JSON immediately
// after WriteState returns, by re-reading via ReadState.
func TestWriteState_AtomicSemantics(t *testing.T) {
	dir := t.TempDir()
	st := workdir.State{Account: "1", Region: "us-east-1", Page: "intent"}

	require.NoError(t, workdir.WriteState(dir, st))

	raw, err := os.ReadFile(filepath.Join(dir, ".ma-state.json"))
	require.NoError(t, err)
	var parsed workdir.State
	require.NoError(t, json.Unmarshal(raw, &parsed))
	require.Equal(t, st, parsed)
}
