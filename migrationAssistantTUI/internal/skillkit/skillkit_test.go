package skillkit_test

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/skillkit"
)

// ---------------------------------------------------------------------------
// In-memory tar.gz fixture helpers
// ---------------------------------------------------------------------------

type tarEntry struct {
	name     string
	body     string
	typeflag byte
	mode     int64
	linkname string
}

// makeTarGz returns a gzip-compressed tar archive built from entries.
// Used by tests so we never read fixture bytes off disk (testdata
// budget: 0 bytes for this package).
func makeTarGz(t *testing.T, entries []tarEntry) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for _, e := range entries {
		mode := e.mode
		if mode == 0 {
			mode = 0o644
		}
		flag := e.typeflag
		if flag == 0 {
			if strings.HasSuffix(e.name, "/") {
				flag = tar.TypeDir
			} else {
				flag = tar.TypeReg
			}
		}
		hdr := &tar.Header{
			Name:     e.name,
			Mode:     mode,
			Size:     int64(len(e.body)),
			Typeflag: flag,
			Linkname: e.linkname,
		}
		require.NoError(t, tw.WriteHeader(hdr))
		if flag == tar.TypeReg {
			_, err := tw.Write([]byte(e.body))
			require.NoError(t, err)
		}
	}
	require.NoError(t, tw.Close())
	require.NoError(t, gz.Close())
	return buf.Bytes()
}

// writeFixture writes the produced archive to disk and returns the
// absolute path. Tests give the path to skillkit.Install.
func writeFixture(t *testing.T, body []byte) string {
	t.Helper()
	dir := t.TempDir()
	p := filepath.Join(dir, "bundle.tar.gz")
	require.NoError(t, os.WriteFile(p, body, 0o644))
	return p
}

// validKiroBundle is a minimal kiro bundle: skills/start.md +
// skills/deploy.md, served from the .kiro/ subtree at the tar root.
func validKiroBundle(t *testing.T) string {
	return writeFixture(t, makeTarGz(t, []tarEntry{
		{name: ".kiro/", typeflag: tar.TypeDir},
		{name: ".kiro/skills/", typeflag: tar.TypeDir},
		{name: ".kiro/skills/start.md", body: "# start\nread me first\n"},
		{name: ".kiro/skills/deploy.md", body: "# deploy\nrun helm\n"},
	}))
}

// validAgnosticBundle ships skills/ at root (no agent-specific
// prefix); the Claude Code adapter rewrites it into .claude/skills/.
func validAgnosticBundle(t *testing.T) string {
	return writeFixture(t, makeTarGz(t, []tarEntry{
		{name: "skills/", typeflag: tar.TypeDir},
		{name: "skills/start.md", body: "# start\nread me first\n"},
		{name: "skills/deploy.md", body: "# deploy\nrun helm\n"},
	}))
}

// ---------------------------------------------------------------------------
// Install — happy paths
// ---------------------------------------------------------------------------

func TestInstall_Kiro_PlacesBundleAtDotKiro(t *testing.T) {
	work := t.TempDir()
	tarball := validKiroBundle(t)

	require.NoError(t, skillkit.Install(work, skillkit.AgentKiro, tarball))

	// .kiro/skills/start.md and .kiro/skills/deploy.md must exist with original content.
	start, err := os.ReadFile(filepath.Join(work, ".kiro", "skills", "start.md"))
	require.NoError(t, err)
	require.Equal(t, "# start\nread me first\n", string(start))
	deploy, err := os.ReadFile(filepath.Join(work, ".kiro", "skills", "deploy.md"))
	require.NoError(t, err)
	require.Equal(t, "# deploy\nrun helm\n", string(deploy))
}

func TestInstall_ClaudeCode_PlacesBundleAtDotClaudeSkills(t *testing.T) {
	work := t.TempDir()
	tarball := validAgnosticBundle(t)

	require.NoError(t, skillkit.Install(work, skillkit.AgentClaudeCode, tarball))

	root := filepath.Join(work, ".claude", "skills", "opensearch-migration")
	start, err := os.ReadFile(filepath.Join(root, "start.md"))
	require.NoError(t, err)
	require.Equal(t, "# start\nread me first\n", string(start))
	deploy, err := os.ReadFile(filepath.Join(root, "deploy.md"))
	require.NoError(t, err)
	require.Equal(t, "# deploy\nrun helm\n", string(deploy))

	// SKILL.md index must be present and reference both files.
	idx, err := os.ReadFile(filepath.Join(root, "SKILL.md"))
	require.NoError(t, err)
	require.Contains(t, string(idx), "start.md")
	require.Contains(t, string(idx), "deploy.md")
}

func TestInstall_Idempotent_OverwritesPreviousBundle(t *testing.T) {
	work := t.TempDir()

	// First install ships start.md only.
	first := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: ".kiro/skills/start.md", body: "v1\n"},
	}))
	require.NoError(t, skillkit.Install(work, skillkit.AgentKiro, first))

	// Second install ships start.md (different content) + deploy.md.
	second := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: ".kiro/skills/start.md", body: "v2\n"},
		{name: ".kiro/skills/deploy.md", body: "v2-deploy\n"},
	}))
	require.NoError(t, skillkit.Install(work, skillkit.AgentKiro, second))

	got, err := os.ReadFile(filepath.Join(work, ".kiro", "skills", "start.md"))
	require.NoError(t, err)
	require.Equal(t, "v2\n", string(got), "second install must overwrite first")

	got2, err := os.ReadFile(filepath.Join(work, ".kiro", "skills", "deploy.md"))
	require.NoError(t, err)
	require.Equal(t, "v2-deploy\n", string(got2))
}

// ---------------------------------------------------------------------------
// Install — error paths
// ---------------------------------------------------------------------------

func TestInstall_RejectsUnsupportedAgent(t *testing.T) {
	work := t.TempDir()
	tarball := validKiroBundle(t)

	err := skillkit.Install(work, skillkit.Agent("aider"), tarball)
	require.Error(t, err)
	require.True(t, errors.Is(err, skillkit.ErrUnsupportedAgent))
}

func TestInstall_RejectsMissingTarball(t *testing.T) {
	work := t.TempDir()
	err := skillkit.Install(work, skillkit.AgentKiro, filepath.Join(t.TempDir(), "nope.tar.gz"))
	require.Error(t, err)
}

func TestInstall_RejectsCorruptGzip(t *testing.T) {
	work := t.TempDir()
	bad := writeFixture(t, []byte("not a gzip stream"))
	err := skillkit.Install(work, skillkit.AgentKiro, bad)
	require.Error(t, err)
}

// ---------------------------------------------------------------------------
// Tar-safety: zip-slip, absolute paths, link types, oversized entries
// ---------------------------------------------------------------------------

func TestInstall_RejectsPathTraversal(t *testing.T) {
	work := t.TempDir()
	bad := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: "../../etc/passwd", body: "owned\n"},
	}))
	err := skillkit.Install(work, skillkit.AgentKiro, bad)
	require.Error(t, err)
	require.True(t, errors.Is(err, skillkit.ErrUnsafeTarEntry))
}

func TestInstall_RejectsAbsolutePath(t *testing.T) {
	work := t.TempDir()
	bad := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: "/etc/passwd", body: "owned\n"},
	}))
	err := skillkit.Install(work, skillkit.AgentKiro, bad)
	require.Error(t, err)
	require.True(t, errors.Is(err, skillkit.ErrUnsafeTarEntry))
}

func TestInstall_RejectsSymlinks(t *testing.T) {
	work := t.TempDir()
	bad := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: ".kiro/evil", typeflag: tar.TypeSymlink, linkname: "/etc/passwd"},
	}))
	err := skillkit.Install(work, skillkit.AgentKiro, bad)
	require.Error(t, err)
	require.True(t, errors.Is(err, skillkit.ErrUnsafeTarEntry))
}

func TestInstall_RejectsHardlinks(t *testing.T) {
	work := t.TempDir()
	bad := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: ".kiro/evil", typeflag: tar.TypeLink, linkname: "/etc/passwd"},
	}))
	err := skillkit.Install(work, skillkit.AgentKiro, bad)
	require.Error(t, err)
	require.True(t, errors.Is(err, skillkit.ErrUnsafeTarEntry))
}

func TestInstall_RejectsDeviceFiles(t *testing.T) {
	work := t.TempDir()
	bad := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: ".kiro/dev", typeflag: tar.TypeChar},
	}))
	err := skillkit.Install(work, skillkit.AgentKiro, bad)
	require.Error(t, err)
	require.True(t, errors.Is(err, skillkit.ErrUnsafeTarEntry))
}

func TestInstall_RejectsOversizedEntry(t *testing.T) {
	// An individual file > 16 MB is rejected as a sanity check
	// against tar-bomb decompression amplification.
	work := t.TempDir()
	big := strings.Repeat("a", 16*1024*1024+1)
	bad := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: ".kiro/skills/huge.md", body: big},
	}))
	err := skillkit.Install(work, skillkit.AgentKiro, bad)
	require.Error(t, err)
	require.True(t, errors.Is(err, skillkit.ErrUnsafeTarEntry))
}

// ---------------------------------------------------------------------------
// Atomicity: failed install does not corrupt previous bundle
// ---------------------------------------------------------------------------

func TestInstall_FailedExtract_LeavesPreviousBundleIntact(t *testing.T) {
	work := t.TempDir()
	// First good install.
	require.NoError(t, skillkit.Install(work, skillkit.AgentKiro, validKiroBundle(t)))
	pre, err := os.ReadFile(filepath.Join(work, ".kiro", "skills", "start.md"))
	require.NoError(t, err)

	// Second install is malicious — must fail, must NOT touch .kiro/.
	bad := writeFixture(t, makeTarGz(t, []tarEntry{
		{name: ".kiro/skills/ok.md", body: "ok\n"},
		{name: "../escape.md", body: "owned\n"},
	}))
	err = skillkit.Install(work, skillkit.AgentKiro, bad)
	require.Error(t, err)

	// .kiro/skills/start.md still exists with original content.
	post, err := os.ReadFile(filepath.Join(work, ".kiro", "skills", "start.md"))
	require.NoError(t, err)
	require.Equal(t, pre, post, "previous bundle must survive failed install")

	// The malicious entry's "ok.md" sibling must NOT have been written
	// (atomic — all-or-nothing).
	_, err = os.Stat(filepath.Join(work, ".kiro", "skills", "ok.md"))
	require.True(t, os.IsNotExist(err), "no partial write from a failed install")
}

// ---------------------------------------------------------------------------
// Adapter registry — open/closed for new agents
// ---------------------------------------------------------------------------

func TestAgents_Listed(t *testing.T) {
	// Agents() lists every known adapter — used by --help text and
	// the welcome page's agent picker.
	got := skillkit.Agents()
	require.ElementsMatch(t, []skillkit.Agent{
		skillkit.AgentKiro,
		skillkit.AgentClaudeCode,
	}, got)
}
