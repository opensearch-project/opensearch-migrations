# AGENTS — `internal/feature/`

This subtree is where I/O lives. Three rules.

## Rule 1 — One feature per subdirectory, narrow interfaces

`internal/feature/aws` exposes `AWSService`. `internal/feature/agents`
exposes `AgentDetector`. `internal/feature/tools` exposes `ToolDetector`.
Each is a small interface (≤ 6 methods) with a real implementation and a
fake.

The aggregate `Workspace` interface in `internal/ui/workspace` exists *only*
so the root model has a single injection point. Pages do not depend on the
aggregate; they depend on the leaf interface they actually use.

## Rule 2 — Backend → UI goes through `internal/pubsub`

If your feature emits ongoing events (deploy phases, log tail, version-check
polling), publish them to `pubsub.Broker`. The UI subscribes via `tea.Sub`
in the root model. Do not capture a `*tea.Program` in a goroutine. Do not
expose a raw channel from your feature.

## Rule 3 — Every real implementation has a fake

`feature/aws.RealService` ships with `feature/aws.FakeService`.
`feature/deploy.RealDriver` ships with `feature/deploy.FakeDriver`. The
fake is in the same package so test code can construct it without a build
tag.

Fakes are *scripted*: they accept a slice of pre-built responses, return
them in order, and fail the test if the caller invokes a method the script
didn't expect. This makes deploy-phase tests deterministic — see Adjustment
E in the design recommendation.

## Test contract

Feature tests do not use `teatest`. They:

  1. Construct the real implementation with a fake AWS endpoint
     (`smithy-go/middleware` or a local HTTP server) where applicable.
  2. Or construct the fake and assert script-driven behavior.
  3. Run with `-race`. Goroutine leaks fail the build.

Integration tests that hit live AWS live in `internal/feature/aws/integration_test.go`
and are gated by a `// go:build integration` tag — they do not run in the
default `make test` and are not allowed to flake the unit-test suite.
