package agents_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/agents"
)

// ----------------------------------------------------------------------------
// FakeDetector tests
// ----------------------------------------------------------------------------

func TestFakeDetector_ReturnsScriptedAgents(t *testing.T) {
	want := []agents.Agent{
		{Name: "claude", Path: "/usr/local/bin/claude", Version: "1.0.0"},
		{Name: "kiro", Path: "/opt/kiro/bin/kiro", Version: "0.7.0"},
	}
	d := agents.NewFakeDetector(agents.FakeScript{Agents: want})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Equal(t, want, got)
}

func TestFakeDetector_PropagatesScriptedError(t *testing.T) {
	want := errors.New("PATH unreadable")
	d := agents.NewFakeDetector(agents.FakeScript{DetectError: want})
	_, err := d.Detect(context.Background())
	require.ErrorIs(t, err, want)
}

func TestFakeDetector_HonoursContextCancel(t *testing.T) {
	d := agents.NewFakeDetector(agents.FakeScript{Agents: []agents.Agent{{Name: "claude"}}})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := d.Detect(ctx)
	require.ErrorIs(t, err, context.Canceled)
}

func TestFakeDetector_EmptyScriptIsValid(t *testing.T) {
	// Zero value — "no agents installed" — is a real production state
	// (operator on a fresh dev box). Detect returns nil, nil.
	d := agents.NewFakeDetector(agents.FakeScript{})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Empty(t, got)
}

// ----------------------------------------------------------------------------
// PathDetector tests — walks PATH for a known set of agent names.
// ----------------------------------------------------------------------------

// makeFakeBin creates an executable file in dir whose --version output is
// `version`. Returns the dir to add to PATH. On Windows the test is
// skipped (we don't ship a Windows TUI; cf. ExecCommandRunner).
func makeFakeBin(t *testing.T, name, versionLine string) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("PATH probe tests use shell scripts; not supported on Windows")
	}
	dir := t.TempDir()
	body := "#!/bin/sh\necho '" + versionLine + "'\n"
	p := filepath.Join(dir, name)
	require.NoError(t, os.WriteFile(p, []byte(body), 0o755))
	return dir
}

func TestPathDetector_DetectsAgentsOnPath(t *testing.T) {
	claudeDir := makeFakeBin(t, "claude", "claude version 1.4.2")
	kiroDir := makeFakeBin(t, "kiro", "kiro 0.7.0")

	t.Setenv("PATH", claudeDir+string(os.PathListSeparator)+kiroDir)

	d := agents.NewPathDetector(agents.PathDetectorConfig{
		Names: []string{"claude", "kiro", "codex"},
	})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)

	// Order is by Names slice (stable for golden-test rendering),
	// codex omitted because not on PATH.
	require.Len(t, got, 2)
	require.Equal(t, "claude", got[0].Name)
	require.Equal(t, filepath.Join(claudeDir, "claude"), got[0].Path)
	require.Equal(t, "kiro", got[1].Name)
}

func TestPathDetector_VersionProbe(t *testing.T) {
	dir := makeFakeBin(t, "claude", "claude version 1.4.2")
	t.Setenv("PATH", dir)

	d := agents.NewPathDetector(agents.PathDetectorConfig{
		Names:        []string{"claude"},
		VersionProbe: true,
	})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 1)
	require.NotEmpty(t, got[0].Version)
	require.Contains(t, got[0].Version, "1.4.2")
}

func TestPathDetector_VersionProbeOff_LeavesVersionEmpty(t *testing.T) {
	dir := makeFakeBin(t, "claude", "claude version 1.4.2")
	t.Setenv("PATH", dir)

	d := agents.NewPathDetector(agents.PathDetectorConfig{
		Names:        []string{"claude"},
		VersionProbe: false,
	})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 1)
	require.Empty(t, got[0].Version, "VersionProbe=false must skip probing")
}

func TestPathDetector_EmptyPathReturnsEmpty(t *testing.T) {
	t.Setenv("PATH", "")
	d := agents.NewPathDetector(agents.PathDetectorConfig{
		Names: []string{"claude"},
	})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Empty(t, got)
}

func TestPathDetector_ContextCancel(t *testing.T) {
	d := agents.NewPathDetector(agents.PathDetectorConfig{
		Names: []string{"claude"},
	})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := d.Detect(ctx)
	require.ErrorIs(t, err, context.Canceled)
}

func TestPathDetector_DefaultNamesUsedWhenUnset(t *testing.T) {
	// No Names => use the package default ("claude", "kiro", …).
	// We verify by detecting "claude" alone and asserting it shows up.
	dir := makeFakeBin(t, "claude", "claude 1.0")
	t.Setenv("PATH", dir)
	d := agents.NewPathDetector(agents.PathDetectorConfig{})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	// claude must be in default list. Other defaults (kiro, codex…)
	// won't be detected because they aren't on PATH; that's fine.
	var names []string
	for _, a := range got {
		names = append(names, a.Name)
	}
	require.Contains(t, names, "claude")
}

func TestPathDetector_VersionProbeRespectsTimeout(t *testing.T) {
	// A binary that hangs forever must not deadlock the detector;
	// VersionProbe internally times out per-binary.
	dir := t.TempDir()
	hangScript := "#!/bin/sh\nsleep 30\n"
	p := filepath.Join(dir, "claude")
	require.NoError(t, os.WriteFile(p, []byte(hangScript), 0o755))
	t.Setenv("PATH", dir)

	d := agents.NewPathDetector(agents.PathDetectorConfig{
		Names:        []string{"claude"},
		VersionProbe: true,
	})

	// Use the package's default per-probe timeout (≤500ms); just
	// assert overall Detect returns within 3s even though the
	// binary would hang for 30s.
	done := make(chan struct{})
	var got []agents.Agent
	var err error
	go func() {
		got, err = d.Detect(context.Background())
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatalf("Detect must enforce a per-probe timeout; hung past 3s")
	}
	require.NoError(t, err)
	require.Len(t, got, 1)
	require.Empty(t, got[0].Version, "hung version probe must yield empty Version, not block")
	require.NotEmpty(t, got[0].Path, "Path must still be detected even if version probe times out")
}
