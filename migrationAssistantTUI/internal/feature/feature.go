// Package feature is the aggregate facade that the root UI model
// depends on. It re-exports each leaf feature interface
// (internal/feature/aws, internal/feature/agents, …) into a single
// Workspace contract so cmd/tui/main.go can wire one struct and pass
// it down.
//
// # Why the aggregate exists at all
//
// The root model has to be testable. A test that boots the model
// needs ONE thing it can stub: a Workspace. Without the aggregate, the
// model constructor would take five separate parameters and every
// integration test would need to construct each leaf separately.
//
// # Why pages do NOT depend on this package
//
// Adjustment C in the design doc: pages declare the SINGLE leaf
// interface they need. The wizard page takes an
// agents.Detector + tools.Detector. The deploy page takes a
// deploy.Driver. None of them import internal/feature itself; they
// receive the leaf they care about as a constructor parameter, which
// the root model extracts from Workspace.AWS() / Agents() / etc.
//
// This means: a page test never has to satisfy the full Workspace
// interface to stub one method. Adding a new aggregate method (e.g.
// Workspace.Telemetry()) doesn't ripple into every page test.
//
// # Why there is no Events() method
//
// The design draft (lines 188-195) showed Events() <-chan tea.Msg on
// Workspace. That would couple internal/feature to bubbletea and
// invert the layered import order (feature must not import ui).
// Instead, the broker — internal/pubsub.Broker — is injected
// separately into both features (which Publish to it) and the root
// model (which Subscribes via tea.Sub, Adjustment A). Workspace stays
// pure RPC-style: read/call/return. Async signals flow through the
// broker.
//
// # Optionality
//
// AWS() may legitimately return nil (offline mode, --no-aws). Every
// other leaf is required: features the user CAN'T turn off live in
// the always-present interfaces, and "I have no agents installed" is
// a valid Detector return value, not a missing Detector.
package feature

import (
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/agents"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/artifacts"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/aws"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/tools"
)

// Workspace is the aggregate the root model depends on. Each method
// returns a leaf interface that lives in its own subpackage.
//
// Implementations:
//   - workspace.NewReal(ctx, cfg) wires production drivers.
//   - workspace.NewFake() returns a workspace.Fake whose leaves are
//     test doubles you can WithX(...) at construction.
//
// Both live in internal/ui/workspace, NOT here, because constructing
// them needs UI-cross-cutting context that internal/feature can't see.
type Workspace interface {
	// AWS returns the AWS read-only service caller. May be nil if the
	// operator launched with --no-aws or the SDK couldn't load
	// credentials. Callers MUST nil-check.
	AWS() aws.Service

	// Agents returns the agent detector. Never nil; an empty result
	// means "none installed."
	Agents() agents.Detector

	// Tools returns the tool presence + version detector. Never nil.
	Tools() tools.Detector

	// Artifacts returns the local-artifact metadata source. Never nil.
	Artifacts() artifacts.Source

	// DeployDriver returns the deploy.Driver. Never nil; production
	// wires deploy.RealDriver, tests wire deploy.FakeDriver
	// (Adjustment E).
	DeployDriver() deploy.Driver
}
