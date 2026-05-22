package workspace_test

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/agents"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/artifacts"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/aws"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/tools"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/pubsub"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/workspace"
)

// ----------------------------------------------------------------------------
// Compile-time guard: Fake satisfies feature.Workspace.
// ----------------------------------------------------------------------------

var _ feature.Workspace = (*workspace.Fake)(nil)

// ----------------------------------------------------------------------------
// Fake
// ----------------------------------------------------------------------------

func TestFake_ZeroValueExposesNonNilLeaves(t *testing.T) {
	w := workspace.NewFake()
	// AWS legitimately optional; rest must be non-nil.
	require.Nil(t, w.AWS(), "zero-value Fake AWS must be nil — opt in via WithAWS")
	require.NotNil(t, w.Agents())
	require.NotNil(t, w.Tools())
	require.NotNil(t, w.Artifacts())
	require.NotNil(t, w.DeployDriver())
}

func TestFake_WithAWS(t *testing.T) {
	stub := aws.NewFakeService(aws.FakeScript{})
	w := workspace.NewFake().WithAWS(stub)
	require.Same(t, stub, w.AWS())
}

func TestFake_WithAgents(t *testing.T) {
	stub := agents.NewFakeDetector(agents.FakeScript{
		Agents: []agents.Agent{{Name: "claude"}},
	})
	w := workspace.NewFake().WithAgents(stub)
	require.Same(t, stub, w.Agents())
}

func TestFake_WithTools(t *testing.T) {
	stub := tools.NewFakeDetector(tools.FakeScript{
		Tools: []tools.Tool{{Name: "kubectl", Path: "/usr/bin/kubectl"}},
	})
	w := workspace.NewFake().WithTools(stub)
	require.Same(t, stub, w.Tools())
}

func TestFake_WithArtifacts(t *testing.T) {
	stub := artifacts.NewFakeSource(nil)
	w := workspace.NewFake().WithArtifacts(stub)
	require.Same(t, stub, w.Artifacts())
}

func TestFake_WithDeployDriver(t *testing.T) {
	br := pubsub.NewBroker()
	defer br.Close()
	stub := deploy.NewFakeDriver(deploy.FakeScript{}, br)
	w := workspace.NewFake().WithDeployDriver(stub)
	require.Same(t, stub, w.DeployDriver())
}

func TestFake_BuildersAreImmutable(t *testing.T) {
	// WithX returns a new Fake — the original must be untouched, so
	// tests that build up multiple workspaces from a shared base
	// don't accidentally cross-contaminate.
	base := workspace.NewFake()
	other := base.WithAgents(agents.NewFakeDetector(agents.FakeScript{
		Agents: []agents.Agent{{Name: "x"}},
	}))
	require.NotSame(t, base.Agents(), other.Agents())
}

// ----------------------------------------------------------------------------
// Real
// ----------------------------------------------------------------------------

func TestReal_RequiresArtifactsDir(t *testing.T) {
	br := pubsub.NewBroker()
	defer br.Close()
	_, err := workspace.NewReal(workspace.RealConfig{
		Ctx:    context.Background(),
		Broker: br,
		Deploy: minimalDeployCfg(),
	})
	require.Error(t, err, "missing ArtifactsDir must error")
}

func TestReal_RequiresBroker(t *testing.T) {
	dir := t.TempDir()
	_, err := workspace.NewReal(workspace.RealConfig{
		Ctx:          context.Background(),
		ArtifactsDir: dir,
		Deploy:       minimalDeployCfg(),
	})
	require.Error(t, err)
}

func TestReal_RequiresDeployConfig(t *testing.T) {
	dir := t.TempDir()
	br := pubsub.NewBroker()
	defer br.Close()
	_, err := workspace.NewReal(workspace.RealConfig{
		Ctx:          context.Background(),
		ArtifactsDir: dir,
		Broker:       br,
	})
	require.Error(t, err, "missing Deploy planner/runner must error")
}

func TestReal_NoAWS_AllowedWhenAllowNoAWSTrue(t *testing.T) {
	// We simulate "no creds available" by pointing AWS env at an
	// invalid profile name — config.LoadDefaultConfig won't actually
	// fail (it loads lazily), but the workspace must not blow up
	// when we ask it to skip AWS entirely.
	dir := t.TempDir()
	br := pubsub.NewBroker()
	defer br.Close()
	w, err := workspace.NewReal(workspace.RealConfig{
		Ctx:          context.Background(),
		Region:       "us-east-1",
		ArtifactsDir: dir,
		Broker:       br,
		Deploy:       minimalDeployCfg(),
		SkipAWS:      true, // explicit opt-out
	})
	require.NoError(t, err)
	require.Nil(t, w.AWS())
	require.NotNil(t, w.Agents())
	require.NotNil(t, w.Tools())
	require.NotNil(t, w.Artifacts())
	require.NotNil(t, w.DeployDriver())
}

func TestReal_ArtifactsDirReturnsRealSource(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "ma.yaml"), []byte("x: y"), 0o644))

	br := pubsub.NewBroker()
	defer br.Close()
	w, err := workspace.NewReal(workspace.RealConfig{
		Ctx:          context.Background(),
		ArtifactsDir: dir,
		Broker:       br,
		Deploy:       minimalDeployCfg(),
		SkipAWS:      true,
	})
	require.NoError(t, err)
	src := w.Artifacts()
	require.NotNil(t, src)
	list := src.All()
	require.Len(t, list, 1)
	require.Equal(t, "ma.yaml", list[0].Name)
}

func TestReal_BadArtifactsDirIsError(t *testing.T) {
	br := pubsub.NewBroker()
	defer br.Close()
	_, err := workspace.NewReal(workspace.RealConfig{
		Ctx:          context.Background(),
		ArtifactsDir: "/no/such/path/here/please",
		Broker:       br,
		Deploy:       minimalDeployCfg(),
		SkipAWS:      true,
	})
	require.Error(t, err)
}

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

func minimalDeployCfg() workspace.DeployConfig {
	return workspace.DeployConfig{
		Planner: func(ctx context.Context, params deploy.Params) (deploy.Plan, []deploy.ExecStep, error) {
			return deploy.Plan{}, nil, nil
		},
		Runner: stubRunner{},
	}
}

type stubRunner struct{}

func (stubRunner) Run(ctx context.Context, phase, cmd string, args []string, onLine func(string)) error {
	return nil
}
