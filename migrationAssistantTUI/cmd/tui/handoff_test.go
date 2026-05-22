package main

// handoff_test.go — regression test for the AGENTS.md syscall.Exec
// contract, specifically Rule 3 + the "syscall.Exec contract" section:
//
//   "If lookup fails, the handoff is rejected *before* tea.Quit, so the
//    user is never stranded with no shell."
//
// We can't actually exercise syscall.Exec in a unit test (it would
// replace the test binary), but we can exercise the lookup gate that
// MUST run *before* Quit is committed. The unit under test is
// resolveHandoffBin, which the main loop calls in two places:
//
//   1. Before submitting the HandoffMsg result back into the model
//      (so the handoff page can show an error and stay open).
//   2. Defense-in-depth: in main() after Run() returns, before
//      reaching syscall.Exec, in case a future code path skips (1).
//
// If (1) is moved or removed, (2) MUST still be present — that's the
// "user is never stranded with no shell" guarantee.

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestResolveHandoffBin_AbsolutePathPassesThrough(t *testing.T) {
	// An existing absolute path resolves to itself.
	tmp := t.TempDir()
	bin := filepath.Join(tmp, "fake-agent")
	require.NoError(t, os.WriteFile(bin, []byte("#!/bin/sh\nexit 0\n"), 0o755))

	got, err := resolveHandoffBin(bin)
	require.NoError(t, err)
	require.Equal(t, bin, got)
}

func TestResolveHandoffBin_RelativePathFromPATH(t *testing.T) {
	// A bare name is resolved via PATH. We seed PATH with our temp
	// dir so we don't depend on the host having a particular tool.
	tmp := t.TempDir()
	bin := filepath.Join(tmp, "fake-agent")
	require.NoError(t, os.WriteFile(bin, []byte("#!/bin/sh\nexit 0\n"), 0o755))
	t.Setenv("PATH", tmp)

	got, err := resolveHandoffBin("fake-agent")
	require.NoError(t, err)
	require.Equal(t, bin, got)
}

func TestResolveHandoffBin_NonexistentNameIsError(t *testing.T) {
	t.Setenv("PATH", t.TempDir()) // PATH with nothing installed.
	_, err := resolveHandoffBin("definitely-not-a-real-agent-cli-aaaa")
	require.Error(t, err, "lookup MUST fail loudly so main can refuse Quit")
}

func TestResolveHandoffBin_AbsoluteNonexistentIsError(t *testing.T) {
	_, err := resolveHandoffBin("/definitely/does/not/exist/agent")
	require.Error(t, err, "absolute path that doesn't exist is fatal — caller must not Exec")
}

func TestResolveHandoffBin_EmptyIsError(t *testing.T) {
	_, err := resolveHandoffBin("")
	require.Error(t, err, "empty bin is a programmer bug; refuse it")
}

func TestVersionsHaveDefaults(t *testing.T) {
	// Per AGENTS.md Rule 3: Version + MAVersion are package-level vars
	// populated by -ldflags. Untouched, they must not be empty —
	// otherwise the welcome page renders "(dev)" / "0.0.0" garbage.
	require.NotEmpty(t, Version, "Version package var must have a default")
	require.NotEmpty(t, MAVersion, "MAVersion package var must have a default")
}

func TestSetVersionsForTest_RestoresOnCleanup(t *testing.T) {
	origV, origMA := Version, MAVersion
	t.Cleanup(func() {
		// Defense-in-depth in case setVersionsForTest's own t.Cleanup
		// is broken: assert restoration here too.
		require.Equal(t, origV, Version)
		require.Equal(t, origMA, MAVersion)
	})
	setVersionsForTest(t, "v9.9.9-test", "9.9.9-test")
	require.Equal(t, "v9.9.9-test", Version)
	require.Equal(t, "9.9.9-test", MAVersion)
}

func TestPlatformIsUnix(t *testing.T) {
	// Sanity: this binary is unix-only by design (syscall.Exec
	// semantics + symlink-based CAS in marelease). The build tags
	// in main.go enforce this; the test pins the assumption.
	if runtime.GOOS == "windows" {
		t.Fatal("migrationAssistantTUI does not support Windows")
	}
}
