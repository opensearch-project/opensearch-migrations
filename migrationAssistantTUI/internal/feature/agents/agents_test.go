// Package agents declares the agent-detector contract.
//
// This file is intentionally minimal — it holds only the test that
// pins the Detector interface shape. Production types live in
// agents.go (see Adjustment C in the design doc).
package agents_test

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/agents"
)

// stubDetector intentionally has the EXACT method set Detector
// requires. A compile error here when this file is read against a
// later interface change is a tripwire — any change that breaks the
// stub breaks every page test that satisfies Detector for stubbing.
type stubDetector struct{ ret []agents.Agent }

func (s stubDetector) Detect(_ context.Context) ([]agents.Agent, error) {
	return s.ret, nil
}

func TestDetectorContract(t *testing.T) {
	var d agents.Detector = stubDetector{ret: []agents.Agent{
		{Name: "claude", Path: "/usr/local/bin/claude", Version: "1.0.0"},
	}}
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 1)
	require.Equal(t, "claude", got[0].Name)
	require.Equal(t, "/usr/local/bin/claude", got[0].Path)
	require.Equal(t, "1.0.0", got[0].Version)
}
