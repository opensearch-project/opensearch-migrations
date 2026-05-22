package launch_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync/atomic"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/launch"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/workdir"
)

// ---------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------

// fakeResolver scripts artifact name → local path (or error). Tracks
// call order so we can assert sequential resolution.
type fakeResolver struct {
	out      map[string]string
	errOn    map[string]error
	calls    atomic.Int32
	order    []string
	orderMtx atomic.Pointer[[]string] // not contended; just for safety
}

func newFakeResolver() *fakeResolver {
	r := &fakeResolver{out: map[string]string{}, errOn: map[string]error{}}
	empty := make([]string, 0, 4)
	r.orderMtx.Store(&empty)
	return r
}
func (r *fakeResolver) wire(name, path string)         { r.out[name] = path }
func (r *fakeResolver) wireErr(name string, err error) { r.errOn[name] = err }

func (r *fakeResolver) Resolve(_ context.Context, name string) (string, error) {
	r.calls.Add(1)
	old := r.orderMtx.Load()
	updated := append(append([]string(nil), *old...), name)
	r.orderMtx.Store(&updated)
	if err, ok := r.errOn[name]; ok {
		return "", err
	}
	if p, ok := r.out[name]; ok {
		return p, nil
	}
	return "", errors.New("fake resolver: unscripted name " + name)
}

func (r *fakeResolver) callOrder() []string {
	cp := *r.orderMtx.Load()
	out := make([]string, len(cp))
	copy(out, cp)
	return out
}

// makeParent returns a clean parent dir under t.TempDir() that Detect
// can search.
func makeParent(t *testing.T) string {
	t.Helper()
	return t.TempDir()
}

// ---------------------------------------------------------------------
// Decide — pure logic
// ---------------------------------------------------------------------

func TestDecide_FreshWhenNoWorkdir(t *testing.T) {
	det := workdir.DetectResult{
		Path: "/tmp/x/opensearch-migration-111122223333-us-east-1", Exists: false,
	}
	got := launch.Decide(det, launch.PreferenceAuto)
	require.Equal(t, launch.ActionCreateFresh, got)
}

func TestDecide_ResumeWhenStateMatches(t *testing.T) {
	det := workdir.DetectResult{
		Path:   "/tmp/x/opensearch-migration-111122223333-us-east-1",
		Exists: true, HasState: true,
		State: workdir.State{Account: "111122223333", Region: "us-east-1", Page: "wizard"},
	}
	got := launch.Decide(det, launch.PreferenceAuto)
	require.Equal(t, launch.ActionResumeExisting, got)
}

func TestDecide_ForceFreshOverridesResume(t *testing.T) {
	det := workdir.DetectResult{
		Path:   "/tmp/x/opensearch-migration-111122223333-us-east-1",
		Exists: true, HasState: true,
		State: workdir.State{Account: "111122223333", Region: "us-east-1"},
	}
	got := launch.Decide(det, launch.PreferenceForceFresh)
	require.Equal(t, launch.ActionCreateFresh, got)
}

func TestDecide_DirExistsButNoState_AskOperator(t *testing.T) {
	// Operator created the dir manually (or a prior crash); we can't
	// assume their intent — surface for confirmation.
	det := workdir.DetectResult{
		Path:   "/tmp/x/opensearch-migration-111122223333-us-east-1",
		Exists: true, HasState: false,
	}
	got := launch.Decide(det, launch.PreferenceAuto)
	require.Equal(t, launch.ActionConfirmAdoptOrFresh, got)
}

// ---------------------------------------------------------------------
// Prepare — FS work
// ---------------------------------------------------------------------

func TestPrepare_CreateFresh_MakesDirs(t *testing.T) {
	parent := makeParent(t)
	wdPath := workdir.Path(parent, "111122223333", "us-east-1")

	got, err := launch.Prepare(context.Background(), launch.PrepareInput{
		Action:  launch.ActionCreateFresh,
		Path:    wdPath,
		Account: "111122223333",
		Region:  "us-east-1",
	})
	require.NoError(t, err)
	require.Equal(t, wdPath, got.Path)

	// Dirs exist.
	for _, sub := range []string{"", ".cache", "artifacts", "log"} {
		st, err := os.Stat(filepath.Join(wdPath, sub))
		require.NoError(t, err, "expected %s to exist", sub)
		require.True(t, st.IsDir())
	}

	// State written and matches.
	s, err := workdir.ReadState(wdPath)
	require.NoError(t, err)
	require.Equal(t, "111122223333", s.Account)
	require.Equal(t, "us-east-1", s.Region)
}

func TestPrepare_CreateFresh_OverwritesExisting(t *testing.T) {
	// "Force fresh" must clobber an existing workdir's state. The
	// previous deploy artifacts on disk are intentionally left in
	// place — their CAS dir is content-addressable and a future run
	// either re-uses them (if SHA matches) or fetches anew.
	parent := makeParent(t)
	wdPath := workdir.Path(parent, "111122223333", "us-east-1")
	require.NoError(t, os.MkdirAll(wdPath, 0o755))
	require.NoError(t, workdir.WriteState(wdPath, workdir.State{
		Account: "111122223333", Region: "us-east-1", Page: "deploy",
		Wizard: map[string]string{"existing": "data"},
	}))

	_, err := launch.Prepare(context.Background(), launch.PrepareInput{
		Action: launch.ActionCreateFresh, Path: wdPath,
		Account: "111122223333", Region: "us-east-1",
	})
	require.NoError(t, err)

	s, err := workdir.ReadState(wdPath)
	require.NoError(t, err)
	require.Empty(t, s.Page, "fresh prepare should reset Page")
	require.Empty(t, s.Wizard, "fresh prepare should reset Wizard")
}

func TestPrepare_Resume_PreservesExistingState(t *testing.T) {
	parent := makeParent(t)
	wdPath := workdir.Path(parent, "111122223333", "us-east-1")
	require.NoError(t, os.MkdirAll(wdPath, 0o755))
	require.NoError(t, workdir.WriteState(wdPath, workdir.State{
		Account: "111122223333", Region: "us-east-1", Page: "deploy",
		Wizard: map[string]string{"helm-release": "ma-prod"},
	}))

	_, err := launch.Prepare(context.Background(), launch.PrepareInput{
		Action: launch.ActionResumeExisting, Path: wdPath,
		Account: "111122223333", Region: "us-east-1",
	})
	require.NoError(t, err)

	s, err := workdir.ReadState(wdPath)
	require.NoError(t, err)
	require.Equal(t, "deploy", s.Page)
	require.Equal(t, "ma-prod", s.Wizard["helm-release"])
}

func TestPrepare_RejectsConfirmActions(t *testing.T) {
	// ActionConfirmAdoptOrFresh requires the UI to upgrade it to
	// CreateFresh or ResumeExisting before calling Prepare. Slipping
	// it through to Prepare is a programmer error.
	parent := makeParent(t)
	wdPath := workdir.Path(parent, "111122223333", "us-east-1")

	_, err := launch.Prepare(context.Background(), launch.PrepareInput{
		Action: launch.ActionConfirmAdoptOrFresh, Path: wdPath,
		Account: "111122223333", Region: "us-east-1",
	})
	require.Error(t, err)
}

func TestPrepare_RequiresAllFields(t *testing.T) {
	parent := makeParent(t)
	wdPath := workdir.Path(parent, "111122223333", "us-east-1")
	in := launch.PrepareInput{
		Action: launch.ActionCreateFresh, Path: wdPath,
		Account: "111122223333", Region: "us-east-1",
	}
	for _, mut := range []func(*launch.PrepareInput){
		func(i *launch.PrepareInput) { i.Path = "" },
		func(i *launch.PrepareInput) { i.Account = "" },
		func(i *launch.PrepareInput) { i.Region = "" },
	} {
		bad := in
		mut(&bad)
		_, err := launch.Prepare(context.Background(), bad)
		require.Error(t, err)
	}
}

// ---------------------------------------------------------------------
// FetchArtifacts — orchestrates marelease
// ---------------------------------------------------------------------

func TestFetchArtifacts_Sequential(t *testing.T) {
	parent := makeParent(t)
	wdPath := workdir.Path(parent, "111122223333", "us-east-1")
	require.NoError(t, os.MkdirAll(wdPath, 0o755))

	res := newFakeResolver()
	res.wire("cfn-template.yaml", "/abs/path/cfn-template.yaml")
	res.wire("helm-chart-3.2.1.tgz", "/abs/path/helm-chart-3.2.1.tgz")
	res.wire("skill-bundle.zip", "/abs/path/skill-bundle.zip")

	got, err := launch.FetchArtifacts(context.Background(), launch.FetchInput{
		Resolver: res,
		Names:    []string{"cfn-template.yaml", "helm-chart-3.2.1.tgz", "skill-bundle.zip"},
	})
	require.NoError(t, err)

	// Returns exactly the requested names, mapped to resolver paths.
	require.Equal(t, "/abs/path/cfn-template.yaml", got["cfn-template.yaml"])
	require.Equal(t, "/abs/path/helm-chart-3.2.1.tgz", got["helm-chart-3.2.1.tgz"])
	require.Equal(t, "/abs/path/skill-bundle.zip", got["skill-bundle.zip"])

	// Sequential, in input order — keeps WARN log lines in a
	// predictable order so postmortems read top-to-bottom.
	order := res.callOrder()
	require.Equal(t,
		[]string{"cfn-template.yaml", "helm-chart-3.2.1.tgz", "skill-bundle.zip"},
		order)
}

func TestFetchArtifacts_ShortCircuitsOnError(t *testing.T) {
	res := newFakeResolver()
	res.wire("cfn-template.yaml", "/abs/cfn")
	res.wireErr("helm-chart-3.2.1.tgz", errors.New("boom"))
	// skill-bundle is wired but should never be called.
	res.wire("skill-bundle.zip", "/abs/skill")

	_, err := launch.FetchArtifacts(context.Background(), launch.FetchInput{
		Resolver: res,
		Names:    []string{"cfn-template.yaml", "helm-chart-3.2.1.tgz", "skill-bundle.zip"},
	})
	require.Error(t, err)
	require.Contains(t, err.Error(), "helm-chart-3.2.1.tgz")
	require.Contains(t, err.Error(), "boom")

	order := res.callOrder()
	require.Equal(t,
		[]string{"cfn-template.yaml", "helm-chart-3.2.1.tgz"},
		order, "should not have attempted skill-bundle after helm error")
}

func TestFetchArtifacts_RespectsContextCancel(t *testing.T) {
	res := newFakeResolver()
	for _, n := range []string{"a", "b", "c"} {
		res.wire(n, "/abs/"+n)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // already cancelled

	_, err := launch.FetchArtifacts(ctx, launch.FetchInput{
		Resolver: res,
		Names:    []string{"a", "b", "c"},
	})
	require.Error(t, err)
	require.ErrorIs(t, err, context.Canceled)
	// Either zero calls (checked before first Resolve) or one call
	// (resolver itself respects ctx); both are acceptable.
	require.LessOrEqual(t, res.calls.Load(), int32(1))
}

func TestFetchArtifacts_RejectsEmptyNames(t *testing.T) {
	_, err := launch.FetchArtifacts(context.Background(), launch.FetchInput{
		Resolver: newFakeResolver(),
		Names:    nil,
	})
	require.Error(t, err)
}

func TestFetchArtifacts_RejectsNilResolver(t *testing.T) {
	require.Panics(t, func() {
		_, _ = launch.FetchArtifacts(context.Background(), launch.FetchInput{
			Resolver: nil,
			Names:    []string{"a"},
		})
	})
}

// ---------------------------------------------------------------------
// ArtifactNames — locked manifest
// ---------------------------------------------------------------------

// TestArtifactNames_Stable — the list is the contract for "what the
// TUI cares about for a deploy." If you add a new artifact, this test
// fails and you must add it both here AND in the goreleaser publish
// step. Catches the silent "we forgot to publish the .sha256" bug.
func TestArtifactNames_Stable(t *testing.T) {
	got := launch.ArtifactNames("3.2.1")
	want := []string{
		"cfn-template-3.2.1.yaml",
		"helm-chart-3.2.1.tgz",
		"skill-bundle-3.2.1.zip",
	}
	sort.Strings(got)
	sort.Strings(want)
	require.Equal(t, want, got)
}
