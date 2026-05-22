package tools_test

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/tools"
)

type stubDetector struct{ ret []tools.Tool }

func (s stubDetector) Detect(_ context.Context) ([]tools.Tool, error) {
	return s.ret, nil
}

func TestDetectorContract(t *testing.T) {
	var d tools.Detector = stubDetector{ret: []tools.Tool{
		{Name: "kubectl", Path: "/usr/local/bin/kubectl", Version: "v1.30.0", MinVersion: "v1.28.0"},
	}}
	got, err := d.Detect(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 1)
	require.Equal(t, "kubectl", got[0].Name)
	require.Equal(t, "v1.28.0", got[0].MinVersion)
}
