package tools_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/tools"
)

// FakeDetector

func TestFakeDetector_ReturnsScriptedTools(t *testing.T) {
	want := []tools.Tool{
		{Name: "kubectl", Path: "/usr/local/bin/kubectl", Version: "v1.30.0", MinVersion: "v1.28.0"},
		{Name: "helm", Path: "/usr/local/bin/helm", Version: "v3.13.0", MinVersion: "v3.12.0"},
	}
	d := tools.NewFakeDetector(tools.FakeScript{Tools: want})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Equal(t, want, got)
}

func TestFakeDetector_PropagatesScriptedError(t *testing.T) {
	want := errors.New("synthetic detect error")
	d := tools.NewFakeDetector(tools.FakeScript{DetectError: want})
	_, err := d.Detect(context.Background())
	require.ErrorIs(t, err, want)
}

func TestFakeDetector_ContextCancel(t *testing.T) {
	d := tools.NewFakeDetector(tools.FakeScript{Tools: []tools.Tool{{Name: "helm"}}})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := d.Detect(ctx)
	require.ErrorIs(t, err, context.Canceled)
}

// PathDetector

func makeFakeBin(t *testing.T, dir, name, body string) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("PATH probe tests use shell scripts; not supported on Windows")
	}
	if dir == "" {
		dir = t.TempDir()
	}
	p := filepath.Join(dir, name)
	require.NoError(t, os.WriteFile(p, []byte("#!/bin/sh\n"+body+"\n"), 0o755))
	return dir
}

func TestPathDetector_DetectsRequiredTools_ReturnsCompleteSlice(t *testing.T) {
	// Even tools NOT on PATH appear in the result with empty Path —
	// that's the contract documented on Detector. Lets the UI render
	// a checklist with explicit absent rows.
	dir := makeFakeBin(t, "", "kubectl", "echo 'Client Version: v1.30.0'")
	t.Setenv("PATH", dir)

	d := tools.NewPathDetector(tools.PathDetectorConfig{
		Required: []tools.Required{
			{Name: "kubectl", MinVersion: "v1.28.0"},
			{Name: "helm", MinVersion: "v3.12.0"},
		},
		VersionProbe: true,
	})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 2)
	require.Equal(t, "kubectl", got[0].Name)
	require.NotEmpty(t, got[0].Path)
	require.Contains(t, got[0].Version, "v1.30.0")
	require.Equal(t, "v1.28.0", got[0].MinVersion)
	require.Equal(t, "helm", got[1].Name)
	require.Empty(t, got[1].Path, "helm not on PATH; Path must be empty but row must exist")
}

func TestPathDetector_DefaultRequiredUsedWhenUnset(t *testing.T) {
	d := tools.NewPathDetector(tools.PathDetectorConfig{})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.NotEmpty(t, got)
	// kubectl, helm, aws are all in the default set.
	var names []string
	for _, tt := range got {
		names = append(names, tt.Name)
	}
	require.Contains(t, names, "kubectl")
	require.Contains(t, names, "helm")
	require.Contains(t, names, "aws")
}

func TestPathDetector_VersionProbeOff_LeavesVersionEmpty(t *testing.T) {
	dir := makeFakeBin(t, "", "kubectl", "echo 'v1.30.0'")
	t.Setenv("PATH", dir)
	d := tools.NewPathDetector(tools.PathDetectorConfig{
		Required: []tools.Required{{Name: "kubectl"}},
	})
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 1)
	require.Empty(t, got[0].Version)
}

func TestPathDetector_VersionProbeRespectsTimeout(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "kubectl"), []byte("#!/bin/sh\nsleep 30\n"), 0o755))
	t.Setenv("PATH", dir)
	d := tools.NewPathDetector(tools.PathDetectorConfig{
		Required:     []tools.Required{{Name: "kubectl"}},
		VersionProbe: true,
	})
	done := make(chan struct{})
	var got []tools.Tool
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
	require.NotEmpty(t, got[0].Path)
	require.Empty(t, got[0].Version)
}

func TestPathDetector_ContextCancel(t *testing.T) {
	d := tools.NewPathDetector(tools.PathDetectorConfig{})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := d.Detect(ctx)
	require.ErrorIs(t, err, context.Canceled)
}
