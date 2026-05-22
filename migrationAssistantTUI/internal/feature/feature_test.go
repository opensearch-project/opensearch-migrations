package feature_test

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/agents"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/artifacts"
	awsf "github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/aws"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/tools"
)

// stubWorkspace is the smallest implementation that satisfies
// feature.Workspace. If a future change to Workspace adds a method,
// this stub stops compiling — that's the point. It guards against
// silent interface drift between the aggregate and the leaf
// interfaces it re-exports.
type stubWorkspace struct {
	aws       awsf.Service
	agents    agents.Detector
	tools     tools.Detector
	artifacts artifacts.Source
	deploy    deploy.Driver
}

func (w stubWorkspace) AWS() awsf.Service             { return w.aws }
func (w stubWorkspace) Agents() agents.Detector       { return w.agents }
func (w stubWorkspace) Tools() tools.Detector         { return w.tools }
func (w stubWorkspace) Artifacts() artifacts.Source   { return w.artifacts }
func (w stubWorkspace) DeployDriver() deploy.Driver   { return w.deploy }

// minimal stubs for each leaf, kept private to this test.
type aDet struct{}
func (aDet) Detect(_ context.Context) ([]agents.Agent, error) { return nil, nil }

type tDet struct{}
func (tDet) Detect(_ context.Context) ([]tools.Tool, error) { return nil, nil }

type aSrc struct{}
func (aSrc) All() []artifacts.Artifact                    { return nil }
func (aSrc) ByName(string) (artifacts.Artifact, bool)     { return artifacts.Artifact{}, false }

type dDrv struct{}
func (dDrv) PreviewPlan(_ context.Context, _ deploy.Params) (deploy.Plan, error) {
	return deploy.Plan{}, nil
}
func (dDrv) Run(_ context.Context, _ deploy.Params) error { return nil }

func TestWorkspaceContract(t *testing.T) {
	var ws feature.Workspace = stubWorkspace{
		aws:       nil, // documented as nullable
		agents:    aDet{},
		tools:     tDet{},
		artifacts: aSrc{},
		deploy:    dDrv{},
	}

	// AWS is the only nullable leaf; the rest must be non-nil.
	require.Nil(t, ws.AWS())
	require.NotNil(t, ws.Agents())
	require.NotNil(t, ws.Tools())
	require.NotNil(t, ws.Artifacts())
	require.NotNil(t, ws.DeployDriver())
}

// TestWorkspaceMethodCount pins the aggregate method count. Bumping
// it forces a deliberate decision: do you want to add a method to the
// aggregate (and ripple it into every Workspace implementation), or
// should this be a per-page leaf interface?
func TestWorkspaceMethodCount(t *testing.T) {
	// Use reflection-free approach: list the method names by hand.
	// If the interface gains/loses a method, this test won't compile
	// (because the methods are referenced) — exactly the tripwire we
	// want.
	var ws feature.Workspace = stubWorkspace{}
	_ = ws.AWS
	_ = ws.Agents
	_ = ws.Tools
	_ = ws.Artifacts
	_ = ws.DeployDriver
	// Five methods. Adding a sixth here without updating
	// stubWorkspace fails compilation. Renaming any fails too.
}
