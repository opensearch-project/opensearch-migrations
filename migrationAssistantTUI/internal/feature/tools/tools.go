// Package tools declares the local-tool-presence detector contract.
// A "tool" is a CLI binary the deploy phase calls (kubectl, helm,
// aws, docker). The detector is read-only; it never installs.
package tools

import "context"

// Tool is one detected tool.
type Tool struct {
	// Name is the binary name as it would appear on PATH ("kubectl",
	// "helm", "aws", "docker"). Stable.
	Name string

	// Path is the absolute path from exec.LookPath, or empty if not
	// found. Callers gate on Path != "" rather than a separate Found
	// bool — keeps the struct tight.
	Path string

	// Version is the parsed version string, or empty if the probe
	// failed or the tool isn't installed. Format is tool-specific
	// ("v1.30.0" for kubectl, "v3.12.0+abcd" for helm). The detector
	// does NOT canonicalise — the UI shows it raw.
	Version string

	// MinVersion is the minimum version the deploy phase requires
	// for this tool. Pinned in the detector, surfaced here so the UI
	// can render "kubectl 1.28 < 1.30 required" without owning the
	// version table.
	MinVersion string
}

// Detector enumerates required tools and their detected status.
//
// Detect MUST be safe to call concurrently and SHOULD complete in
// <500ms total. Returning a slice (rather than a map) preserves the
// ordering the UI uses to render the preflight checklist; any
// reordering is a UX change, not just a refactor.
//
// A nil error with all entries having Path == "" means "we looked
// successfully and nothing was installed." A non-nil error means the
// probe itself failed.
type Detector interface {
	Detect(ctx context.Context) ([]Tool, error)
}
