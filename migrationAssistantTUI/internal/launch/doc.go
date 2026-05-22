// Package launch is the workspace-prep state machine. It sits between
// internal/workdir (which knows path conventions) and the UI (which
// knows how to ask the operator) and decides what should happen on
// startup, then performs the FS work.
//
// # Responsibilities
//
//  1. Decide. Pure function. Given a workdir.DetectResult and the
//     operator's preference (resume or fresh), return an Action enum.
//     The UI uses this to drive a confirm dialog before any disk write.
//
//  2. Prepare. Creates the workdir, writes the initial state, ensures
//     the .cache and artifacts subdirs exist, truncates the log file.
//     Idempotent: re-running on an already-prepared workdir is a no-op
//     beyond a state refresh.
//
//  3. FetchArtifacts. Iterates over the fixed list of MA artifact
//     names and asks an ArtifactResolver to resolve each. Returns the
//     map of name → local path (the symlinks marelease publishes).
//     Errors short-circuit; partial work remains in the workdir for
//     the next launch to resume from.
//
// # What this package does NOT do
//
//   - HTTP. ArtifactResolver is injectable; production wires marelease.
//
//   - UI. Decide is a pure data transform; Prepare/FetchArtifacts take
//     a context.Context and return an error. The Bubble Tea command
//     wrapper lives in internal/ui/msg, not here.
//
//   - State persistence beyond the initial write. Wizard updates go
//     through internal/workdir.WriteState directly.
//
// # Determinism
//
// Decide is deterministic — same inputs, same Action. Prepare is
// idempotent up to wall-clock state-file timestamps (we don't currently
// stamp any). FetchArtifacts is idempotent insofar as marelease.Resolver
// is content-addressable; re-running with cached artifacts makes one
// network call per artifact (the .sha256 verification) and no body
// downloads.
package launch
