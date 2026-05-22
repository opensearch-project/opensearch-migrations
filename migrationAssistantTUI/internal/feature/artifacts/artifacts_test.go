package artifacts_test

import (
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/artifacts"
)

type stubSource struct{ items []artifacts.Artifact }

func (s stubSource) All() []artifacts.Artifact { return s.items }
func (s stubSource) ByName(name string) (artifacts.Artifact, bool) {
	for _, a := range s.items {
		if a.Name == name {
			return a, true
		}
	}
	return artifacts.Artifact{}, false
}

func TestSourceContract(t *testing.T) {
	var s artifacts.Source = stubSource{items: []artifacts.Artifact{
		{Name: "cfn-template-3.2.1.yaml", Path: "/abs/cfn", SHA256: "abc123", Size: 4096},
	}}
	require.Len(t, s.All(), 1)

	got, ok := s.ByName("cfn-template-3.2.1.yaml")
	require.True(t, ok)
	require.Equal(t, "abc123", got.SHA256)
	require.Equal(t, int64(4096), got.Size)

	_, ok = s.ByName("nope.zip")
	require.False(t, ok)
}
