// Package workspace assembles the per-feature leaves of
// internal/feature/* into the aggregate feature.Workspace interface
// the root UI Model depends on.
//
// # Why this lives in internal/ui/workspace, not internal/feature
//
// internal/feature defines the contract; the implementations belong
// here because composing them requires UI-cross-cutting context
// (broker, ctx, lipgloss styles for synthetic warnings) that
// internal/feature must not import — feature is the lower layer in
// the layered import graph.
//
// # Production wiring (NewReal)
//
// RealConfig groups every input the production aggregate needs.
// NewReal:
//
//  1. Wires aws.NewRealService using the SDK default credential
//     chain (or yields a nil AWS leaf when SkipAWS is true — useful
//     when the operator launched with --no-aws or the credential
//     load failed but the rest of the wizard should still work).
//  2. Wires agents.NewPathDetector + tools.NewPathDetector with
//     their package-default name lists. Tests that need to override
//     the name lists construct the leaf themselves and inject via
//     Fake.
//  3. Wires artifacts.NewDirectorySource against the supplied
//     ArtifactsDir. Returns the underlying error if the directory
//     is missing — the caller (cmd/tui/main.go) decides whether to
//     bail or fall back to an empty source.
//  4. Wires deploy.NewRealDriver with the supplied Planner+Runner
//     and the broker as Publisher. The broker is shared with the
//     root Model so PhaseEvents emitted during Run() flow back to
//     the deploy page automatically.
//
// # Test wiring (Fake)
//
// Fake is the test double. Zero value:
//
//   - AWS  → nil (legitimate optional)
//   - all other leaves → empty fakes (NewFakeDetector{}, etc.)
//
// Builders WithAWS / WithAgents / WithTools / WithArtifacts /
// WithDeployDriver replace one leaf at a time and return a NEW Fake
// — pointer-immutable semantics so tests that share a base fake
// don't cross-contaminate.
//
// # No Events() method
//
// Async signals flow through the pubsub.Broker, which is constructed
// once in cmd/tui/main.go and injected separately into BOTH the
// workspace (so deploy etc. publish to it) and the root Model (so
// pages subscribe via Adjustment A's Program.Send pump). The
// Workspace contract stays pure RPC-style — read/call/return.
package workspace

import (
	"context"
	"errors"
	"fmt"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/agents"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/artifacts"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/aws"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/tools"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/pubsub"
)

// ----------------------------------------------------------------------------
// Real
// ----------------------------------------------------------------------------

// RealConfig groups every input NewReal needs to compose the five
// production leaves. Keep this struct small and dumb — it is the
// public surface for "how do I boot the real workspace from
// cmd/tui/main.go".
type RealConfig struct {
	// Ctx is the context used for AWS SDK config load. Required.
	Ctx context.Context

	// Region is the AWS region to use. Empty means "let the SDK
	// resolve it from env/profile."
	Region string

	// SkipAWS, when true, sets the AWS leaf to nil unconditionally
	// (the workspace yields nil from AWS()). Use this when the
	// operator launched with --no-aws or the credential chain
	// failed — the rest of the wizard still works (every page
	// nil-checks AWS).
	SkipAWS bool

	// ArtifactsDir is the absolute path to the artifacts/ directory
	// inside the workdir. Required. NewReal returns an error if the
	// directory doesn't exist.
	ArtifactsDir string

	// Broker is the in-process pubsub broker. The deploy driver
	// publishes PhaseEvents to it; the root Model subscribes. The
	// caller (cmd/tui/main.go) constructs ONE broker and shares it.
	// Required.
	Broker *pubsub.Broker

	// Deploy carries the planner + subprocess runner the production
	// deploy driver wraps. Required. cmd/tui/main.go composes these
	// from internal/feature/deploy's helpers (or test stubs in
	// integration tests).
	Deploy DeployConfig

	// Optional leaf overrides — non-nil values bypass the default
	// constructor. Used by integration tests that want a real
	// driver for some leaves and a fake for others.
	AgentsOverride    agents.Detector
	ToolsOverride     tools.Detector
	ArtifactsOverride artifacts.Source
}

// DeployConfig carries the deploy.Driver inputs that NewReal cannot
// fabricate on its own (planner logic + subprocess runner). Mirrors
// deploy.RealConfig minus Publisher (workspace supplies the broker).
type DeployConfig struct {
	Planner func(ctx context.Context, params deploy.Params) (deploy.Plan, []deploy.ExecStep, error)
	Runner  deploy.SubprocessRunner
}

// NewReal composes all five leaves and returns a feature.Workspace.
//
// Required fields: ArtifactsDir, Broker, Deploy.Planner, Deploy.Runner.
// AWS is opt-out via SkipAWS.
func NewReal(cfg RealConfig) (feature.Workspace, error) {
	if cfg.ArtifactsDir == "" {
		return nil, errors.New("workspace.NewReal: ArtifactsDir is required")
	}
	if cfg.Broker == nil {
		return nil, errors.New("workspace.NewReal: Broker is required")
	}
	if cfg.Deploy.Planner == nil || cfg.Deploy.Runner == nil {
		return nil, errors.New("workspace.NewReal: Deploy.Planner and Deploy.Runner are required")
	}

	ctx := cfg.Ctx
	if ctx == nil {
		ctx = context.Background()
	}

	// AWS — opt-out via SkipAWS, otherwise propagate load error.
	var awsSvc aws.Service
	if !cfg.SkipAWS {
		svc, err := aws.NewRealService(ctx, cfg.Region)
		if err != nil {
			return nil, fmt.Errorf("workspace.NewReal: aws: %w", err)
		}
		awsSvc = svc
	}

	// Agents.
	ag := cfg.AgentsOverride
	if ag == nil {
		ag = agents.NewPathDetector(agents.PathDetectorConfig{})
	}

	// Tools.
	tl := cfg.ToolsOverride
	if tl == nil {
		tl = tools.NewPathDetector(tools.PathDetectorConfig{})
	}

	// Artifacts. Failure here is fatal — the review page can't
	// render without a source, so we'd rather fail at boot than
	// nil-panic three pages in.
	art := cfg.ArtifactsOverride
	if art == nil {
		src, err := artifacts.NewDirectorySource(cfg.ArtifactsDir)
		if err != nil {
			return nil, fmt.Errorf("workspace.NewReal: artifacts: %w", err)
		}
		art = src
	}

	// Deploy driver — broker is publisher.
	dd := deploy.NewRealDriver(deploy.RealConfig{
		Publisher: cfg.Broker,
		Planner:   cfg.Deploy.Planner,
		Runner:    cfg.Deploy.Runner,
	})

	return &realWorkspace{
		aws:       awsSvc,
		agents:    ag,
		tools:     tl,
		artifacts: art,
		deploy:    dd,
	}, nil
}

type realWorkspace struct {
	aws       aws.Service
	agents    agents.Detector
	tools     tools.Detector
	artifacts artifacts.Source
	deploy    deploy.Driver
}

func (r *realWorkspace) AWS() aws.Service            { return r.aws }
func (r *realWorkspace) Agents() agents.Detector     { return r.agents }
func (r *realWorkspace) Tools() tools.Detector       { return r.tools }
func (r *realWorkspace) Artifacts() artifacts.Source { return r.artifacts }
func (r *realWorkspace) DeployDriver() deploy.Driver { return r.deploy }

// ----------------------------------------------------------------------------
// Fake
// ----------------------------------------------------------------------------

// Fake is the test double for feature.Workspace. Zero-via-NewFake
// gives every required leaf a no-op fake; AWS stays nil so tests
// for "missing AWS" code paths get the realistic shape for free.
//
// All WithX builders are pointer-immutable: they shallow-copy the
// receiver and return a new pointer, so a base fake shared across
// subtests is safe.
type Fake struct {
	aws       aws.Service
	agents    agents.Detector
	tools     tools.Detector
	artifacts artifacts.Source
	deploy    deploy.Driver
}

// NewFake returns a Fake with empty fakes for every required leaf
// and nil for AWS. Tests opt into AWS by calling WithAWS.
func NewFake() *Fake {
	br := pubsub.NewBroker()
	// NOTE: the broker constructed here is owned by the fake's
	// deploy driver; in practice the test almost always overrides
	// the deploy driver via WithDeployDriver before exercising any
	// publish-bearing code path, so this internal broker is
	// rarely touched and never closed by the workspace itself.
	return &Fake{
		aws:       nil,
		agents:    agents.NewFakeDetector(agents.FakeScript{}),
		tools:     tools.NewFakeDetector(tools.FakeScript{}),
		artifacts: artifacts.NewFakeSource(nil),
		deploy:    deploy.NewFakeDriver(deploy.FakeScript{}, br),
	}
}

// WithAWS returns a copy of f with the AWS leaf replaced.
func (f *Fake) WithAWS(s aws.Service) *Fake {
	out := *f
	out.aws = s
	return &out
}

// WithAgents returns a copy of f with the agents leaf replaced.
func (f *Fake) WithAgents(d agents.Detector) *Fake {
	out := *f
	out.agents = d
	return &out
}

// WithTools returns a copy of f with the tools leaf replaced.
func (f *Fake) WithTools(d tools.Detector) *Fake {
	out := *f
	out.tools = d
	return &out
}

// WithArtifacts returns a copy of f with the artifacts leaf replaced.
func (f *Fake) WithArtifacts(s artifacts.Source) *Fake {
	out := *f
	out.artifacts = s
	return &out
}

// WithDeployDriver returns a copy of f with the deploy driver replaced.
func (f *Fake) WithDeployDriver(d deploy.Driver) *Fake {
	out := *f
	out.deploy = d
	return &out
}

func (f *Fake) AWS() aws.Service            { return f.aws }
func (f *Fake) Agents() agents.Detector     { return f.agents }
func (f *Fake) Tools() tools.Detector       { return f.tools }
func (f *Fake) Artifacts() artifacts.Source { return f.artifacts }
func (f *Fake) DeployDriver() deploy.Driver { return f.deploy }
