package artifacts_test

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/artifacts"
)

// FakeSource

func TestFakeSource_All_ReturnsScripted(t *testing.T) {
	want := []artifacts.Artifact{
		{Name: "cfn-template-3.2.1.yaml", Path: "/x/y", SHA256: "abc", Size: 1234},
		{Name: "helm-chart-3.2.1.tgz", Path: "/x/z", SHA256: "def", Size: 9999},
	}
	s := artifacts.NewFakeSource(want)
	require.Equal(t, want, s.All())
}

func TestFakeSource_ByName_HitAndMiss(t *testing.T) {
	in := []artifacts.Artifact{{Name: "a.yaml", Path: "/x", SHA256: "abc"}}
	s := artifacts.NewFakeSource(in)

	got, ok := s.ByName("a.yaml")
	require.True(t, ok)
	require.Equal(t, in[0], got)

	_, ok = s.ByName("missing")
	require.False(t, ok)
}

func TestFakeSource_All_ReturnsCopy(t *testing.T) {
	in := []artifacts.Artifact{{Name: "a.yaml"}}
	s := artifacts.NewFakeSource(in)
	got := s.All()
	got[0].Name = "MUTATED"
	// Subsequent All() must still return the original — defensive
	// copy means UI mutations can't poison the source.
	require.Equal(t, "a.yaml", s.All()[0].Name)
}

// DirectorySource — production. Reads <workdir>/artifacts/.

func writeArtifact(t *testing.T, dir, name string, body []byte) (path, sha string) {
	t.Helper()
	p := filepath.Join(dir, name)
	require.NoError(t, os.MkdirAll(dir, 0o755))
	require.NoError(t, os.WriteFile(p, body, 0o644))
	sum := sha256.Sum256(body)
	return p, hex.EncodeToString(sum[:])
}

func TestDirectorySource_All_ReturnsAllFilesWithSHAandSize(t *testing.T) {
	dir := t.TempDir()
	bodyA := []byte("body-a")
	pathA, shaA := writeArtifact(t, dir, "a.yaml", bodyA)
	bodyB := []byte("body-b-longer")
	pathB, shaB := writeArtifact(t, dir, "b.tgz", bodyB)

	s, err := artifacts.NewDirectorySource(dir)
	require.NoError(t, err)
	got := s.All()
	require.Len(t, got, 2)

	// Order is filename-sorted (deterministic for golden tests).
	require.Equal(t, "a.yaml", got[0].Name)
	require.Equal(t, pathA, got[0].Path)
	require.Equal(t, shaA, got[0].SHA256)
	require.Equal(t, int64(len(bodyA)), got[0].Size)

	require.Equal(t, "b.tgz", got[1].Name)
	require.Equal(t, pathB, got[1].Path)
	require.Equal(t, shaB, got[1].SHA256)
	require.Equal(t, int64(len(bodyB)), got[1].Size)
}

func TestDirectorySource_ByName_HitAndMiss(t *testing.T) {
	dir := t.TempDir()
	writeArtifact(t, dir, "a.yaml", []byte("body"))

	s, err := artifacts.NewDirectorySource(dir)
	require.NoError(t, err)

	got, ok := s.ByName("a.yaml")
	require.True(t, ok)
	require.Equal(t, "a.yaml", got.Name)

	_, ok = s.ByName("missing")
	require.False(t, ok)
}

func TestDirectorySource_EmptyDirReturnsEmpty(t *testing.T) {
	dir := t.TempDir()
	s, err := artifacts.NewDirectorySource(dir)
	require.NoError(t, err)
	require.Empty(t, s.All())
}

func TestDirectorySource_NonexistentDirReturnsError(t *testing.T) {
	_, err := artifacts.NewDirectorySource(filepath.Join(t.TempDir(), "doesnt-exist"))
	require.Error(t, err)
}

func TestDirectorySource_FileInsteadOfDirReturnsError(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "regular-file")
	require.NoError(t, os.WriteFile(p, []byte("x"), 0o644))
	_, err := artifacts.NewDirectorySource(p)
	require.Error(t, err)
}

func TestDirectorySource_FollowsSymlinks(t *testing.T) {
	// marelease publishes <workdir>/artifacts/<name> as a symlink
	// pointing into <workdir>/.cache/<sha>. The source MUST resolve
	// these — operators see canonical paths, the SHA is computed
	// against actual content.
	cas := t.TempDir()
	body := []byte("real-body")
	target, sha := writeArtifact(t, cas, "blob", body)

	dir := t.TempDir()
	link := filepath.Join(dir, "via-symlink.yaml")
	require.NoError(t, os.Symlink(target, link))

	s, err := artifacts.NewDirectorySource(dir)
	require.NoError(t, err)
	got := s.All()
	require.Len(t, got, 1)
	require.Equal(t, "via-symlink.yaml", got[0].Name)
	require.Equal(t, sha, got[0].SHA256, "SHA must be computed from the symlink target body")
	require.Equal(t, int64(len(body)), got[0].Size)
}

func TestDirectorySource_SkipsSubdirectories(t *testing.T) {
	dir := t.TempDir()
	writeArtifact(t, dir, "real.yaml", []byte("x"))
	require.NoError(t, os.MkdirAll(filepath.Join(dir, ".cache"), 0o755))
	require.NoError(t, os.MkdirAll(filepath.Join(dir, "subdir"), 0o755))

	s, err := artifacts.NewDirectorySource(dir)
	require.NoError(t, err)
	got := s.All()
	require.Len(t, got, 1)
	require.Equal(t, "real.yaml", got[0].Name)
}

func TestDirectorySource_IgnoresHiddenFiles(t *testing.T) {
	// .DS_Store, .gitignore-style files in the artifacts dir aren't
	// artifacts. The contract is "every entry is something the deploy
	// phase consumes."
	dir := t.TempDir()
	writeArtifact(t, dir, "real.yaml", []byte("x"))
	writeArtifact(t, dir, ".hidden", []byte("y"))

	s, err := artifacts.NewDirectorySource(dir)
	require.NoError(t, err)
	got := s.All()
	require.Len(t, got, 1)
	require.Equal(t, "real.yaml", got[0].Name)
}
