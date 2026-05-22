// Package marelease resolves Migration Assistant release artifacts
// (CFN template, helm chart, skill bundle) to a local on-disk path,
// with a content-addressable cache keyed by SHA-256.
//
// This is Adjustment B from the design doc: "make the artifact fetcher
// idempotent and content-addressable."
//
// # Resolution order (UX §0.7)
//
//  1. GitHub release asset for the pinned tag (e.g. helm-chart-3.2.1.tgz).
//  2. Tag-pinned raw repo contents (raw.githubusercontent.com/.../v3.2.1/...).
//  3. Hard fail. No fallback to "latest". No fallback to main. No auto-retry
//     on SHA mismatch.
//
// Each artifact has a companion <name>.sha256 file published with the
// release; SHA mismatch is a hard, non-auto-resolved failure with a
// human-readable "this release was retagged — re-run with --reset-cache"
// message routed via internal/log at ERROR.
//
// Every fallback to step (2) is logged at WARN so postmortems can see
// "release asset was missing for v3.2.1, fell back to raw repo."
//
// # Cache layout (UX §0.7)
//
//	<workdir>/.cache/<sha256>/<name>           CAS-keyed file
//	<workdir>/artifacts/<name>      -> ../.cache/<sha256>/<name>  (symlink)
//
// The symlink target is what callers use; the CAS dir is implementation
// detail. Re-fetches verify the cached SHA before re-downloading; if the
// cached file matches, no network call is made.
//
// # What this package does NOT do
//
//   - HTTP. The Fetcher interface is injectable; tests stay hermetic.
//     Production wires net/http.Client; tests wire a fake.
//
//   - Trigger upgrades. The TUI's MAVersion is fixed at build time
//     (UX §0.5, locked rule r6). marelease only resolves the artifacts
//     for the version the binary already commits to.
//
//   - Decide where the workdir lives. That's internal/workdir.
//
// # Platform support
//
// Symlink-based: linux + darwin only. Windows is out of scope per the
// goreleaser config; Resolve returns ErrUnsupportedPlatform on Windows.
package marelease
