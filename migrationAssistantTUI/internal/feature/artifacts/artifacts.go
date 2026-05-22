// Package artifacts declares the local-artifact-metadata source
// interface. After internal/launch.FetchArtifacts has resolved the
// per-version artifact set into the workdir, the UI needs a way to
// read back "what artifacts are present and where are they?"
package artifacts

// Artifact is one local artifact entry.
type Artifact struct {
	// Name is the exact filename ("cfn-template-3.2.1.yaml", …).
	// Stable contract with internal/launch.ArtifactNames.
	Name string

	// Path is the absolute filesystem path to the artifact (a
	// symlink published by marelease into <workdir>/artifacts/).
	Path string

	// SHA256 is the hex-encoded SHA-256 of the artifact body.
	// Surfaced for the review page so the operator can verify
	// against an out-of-band channel if they want.
	SHA256 string

	// Size is the artifact body size in bytes. Lets the UI render
	// "helm-chart-3.2.1.tgz (8.3 MB)" without a stat.
	Size int64
}

// Source returns the artifact metadata.
//
// All() returns every known artifact. Production reads from the
// workdir's artifacts/ dir + .cache index; tests script a fixed
// slice. ByName(name) is sugar; missing names return ok=false rather
// than an error to keep call-sites tight (the review page renders
// "—" for missing names rather than an error banner).
//
// The Source contract does NOT include a Resolve / Fetch method —
// fetching is internal/launch's job, finished before the UI is
// allowed to ask.
type Source interface {
	All() []Artifact
	ByName(name string) (Artifact, bool)
}
