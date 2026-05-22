// Package handoffbrief writes ./HANDOFF.md — the file the bundled
// agent skill (`@start`) is instructed to read first when the user
// hands control off to claude/codex/kiro/etc. after the TUI exits.
//
// Format on disk:
//
//	---
//	<YAML frontmatter — handoffbrief.Brief, schema_version 1>
//	---
//
//	# Migration goal
//
//	<user's free-text goal from the intent page>
//
// Two design rules are load-bearing:
//
//  1. Credentials are NEVER inlined. Source.AuthMethod identifies
//     the kind of credential ("basic", "sigv4", …) and Source.AuthRef
//     identifies WHERE the agent should fetch it (an OS keychain
//     item ID, an env var name, a vault path). The TUI itself reads
//     the credential to test the source connection but does not
//     persist it to the workdir.
//
//  2. Brief is the single source of truth — wizard.State.ToBrief()
//     populates it so HANDOFF.md and the on-screen review page can
//     never drift. Validate refuses incomplete briefs at the boundary
//     so a half-filled wizard cannot leak a broken handoff.
package handoffbrief

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// SchemaVersion is the on-disk schema version. Bump on any breaking
// field change. Readers (the bundled @start skill, future CLI
// inspectors) gate on this.
const SchemaVersion = 1

// FileName is the workdir-relative filename written by Write.
const FileName = "HANDOFF.md"

// Brief is the machine-parseable handoff record. Every field is
// YAML-tagged; the on-disk frontmatter is exactly this struct.
//
// Pointers + omitempty are deliberately avoided — explicit empty
// strings render as `field: ""` in YAML, which makes "what was
// unset?" obvious to a human inspecting the file.
type Brief struct {
	SchemaVersion int    `yaml:"schema_version"`
	MAVersion     string `yaml:"ma_version"`
	WrittenAt     string `yaml:"written_at"`

	AWSAccount string `yaml:"aws_account"`
	Region     string `yaml:"region"`

	EKSCluster string `yaml:"eks_cluster"`
	Namespace  string `yaml:"namespace"`

	Stage string `yaml:"stage"`

	Source Source `yaml:"source"`
	Target Target `yaml:"target"`

	// ConsoleExec is the in-cluster command the agent should
	// `kubectl exec` to land at the migrations console. Empty before
	// deploy completes.
	ConsoleExec string `yaml:"console_exec"`
}

// Source describes the source cluster.
type Source struct {
	Endpoint      string `yaml:"endpoint"`
	Engine        string `yaml:"engine"`         // "elasticsearch" | "opensearch" | "solr"
	EngineVersion string `yaml:"engine_version"` // "7.10", "9.5", …
	AuthMethod    string `yaml:"auth_method"`    // "none" | "basic" | "sigv4" | "header"

	// AuthRef points to where the credential is stored. Format:
	//   "keychain:<id>"   — OS keychain item ID
	//   "env:<NAME>"      — environment variable name
	//   "vault:<path>"    — Vault KV path
	// Never inline a credential here.
	AuthRef string `yaml:"auth_ref"`

	// ApproxSize is operator-supplied free text ("1.2 TB", "300M
	// docs"). Renders verbatim in HANDOFF.md and informs the agent's
	// downtime estimates.
	ApproxSize string `yaml:"approx_size"`
}

// Target describes the destination cluster.
type Target struct {
	// Type is "new-opensearch-domain" | "existing-aos" | "self-managed".
	Type     string `yaml:"type"`
	Endpoint string `yaml:"endpoint"` // empty until the deploy resolves it
}

// ----------------------------------------------------------------------------
// Validation
// ----------------------------------------------------------------------------

// ErrInvalidBrief is the sentinel returned by Validate / Write when
// the brief is missing required fields or has an out-of-range value.
// errors.Is for the sentinel; errors.As for the typed *ValidationError
// to enumerate the missing fields.
var ErrInvalidBrief = errors.New("handoffbrief: invalid brief")

// ValidationError lists every field that's missing or invalid.
// Wraps ErrInvalidBrief so callers can errors.Is(err, ErrInvalidBrief).
type ValidationError struct {
	Missing []string
}

func (v *ValidationError) Error() string {
	return fmt.Sprintf("handoffbrief: missing required fields: %s", strings.Join(v.Missing, ", "))
}

// Is reports a match against ErrInvalidBrief.
func (v *ValidationError) Is(target error) bool { return target == ErrInvalidBrief }

var validSourceEngines = map[string]struct{}{
	"elasticsearch": {},
	"opensearch":    {},
	"solr":          {},
}

var validAuthMethods = map[string]struct{}{
	"none":   {},
	"basic":  {},
	"sigv4":  {},
	"header": {},
}

var validTargetTypes = map[string]struct{}{
	"new-opensearch-domain": {},
	"existing-aos":          {},
	"self-managed":          {},
}

// Validate returns a *ValidationError listing every missing or
// invalid field, or nil if the brief is fit for handoff.
//
// "Required" means the agent CANNOT do its job without this field.
// Optional fields (ConsoleExec before deploy, Source.ApproxSize,
// Target.Endpoint before resolve) are intentionally not in the list.
func (b Brief) Validate() error {
	var miss []string
	if b.MAVersion == "" {
		miss = append(miss, "ma_version")
	}
	if b.AWSAccount == "" {
		miss = append(miss, "aws_account")
	}
	if b.Region == "" {
		miss = append(miss, "region")
	}
	if b.EKSCluster == "" {
		miss = append(miss, "eks_cluster")
	}
	if b.Namespace == "" {
		miss = append(miss, "namespace")
	}
	if b.Stage == "" {
		miss = append(miss, "stage")
	}
	if b.Source.Engine == "" {
		miss = append(miss, "source.engine")
	} else if _, ok := validSourceEngines[b.Source.Engine]; !ok {
		miss = append(miss, "source.engine (invalid value)")
	}
	if b.Source.AuthMethod == "" {
		miss = append(miss, "source.auth_method")
	} else if _, ok := validAuthMethods[b.Source.AuthMethod]; !ok {
		miss = append(miss, "source.auth_method (invalid value)")
	}
	// auth_ref required when AuthMethod != "none".
	if b.Source.AuthMethod != "" && b.Source.AuthMethod != "none" && b.Source.AuthRef == "" {
		miss = append(miss, "source.auth_ref")
	}
	// auth_ref must be a recognized indirection, never an inlined cred.
	if b.Source.AuthRef != "" && !looksLikeAuthRef(b.Source.AuthRef) {
		miss = append(miss, "source.auth_ref (must be 'keychain:<id>', 'env:<NAME>', or 'vault:<path>')")
	}
	if b.Target.Type == "" {
		miss = append(miss, "target.type")
	} else if _, ok := validTargetTypes[b.Target.Type]; !ok {
		miss = append(miss, "target.type (invalid value)")
	}
	if len(miss) > 0 {
		return &ValidationError{Missing: miss}
	}
	return nil
}

func looksLikeAuthRef(s string) bool {
	for _, prefix := range []string{"keychain:", "env:", "vault:"} {
		if strings.HasPrefix(s, prefix) && len(s) > len(prefix) {
			return true
		}
	}
	return false
}

// ----------------------------------------------------------------------------
// Write / Read
// ----------------------------------------------------------------------------

// Write atomically writes b + goal to <workdir>/HANDOFF.md.
//
// Validation runs first; an invalid brief returns *ValidationError
// without touching disk. SchemaVersion is forced to the package's
// SchemaVersion (callers don't need to set it). WrittenAt is filled
// to UTC now if zero.
//
// Atomicity: write to .tmp + rename. A crash mid-write leaves the
// previous HANDOFF.md (if any) intact.
func Write(workdir string, b Brief, goal string) error {
	b.SchemaVersion = SchemaVersion
	if b.WrittenAt == "" {
		b.WrittenAt = time.Now().UTC().Format(time.RFC3339)
	}
	if err := b.Validate(); err != nil {
		return err
	}
	body, err := render(b, goal)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(workdir, 0o755); err != nil {
		return fmt.Errorf("handoffbrief: mkdir workdir: %w", err)
	}
	final := filepath.Join(workdir, FileName)
	tmp := final + ".tmp"
	if err := os.WriteFile(tmp, body, 0o644); err != nil {
		return fmt.Errorf("handoffbrief: write tmp: %w", err)
	}
	if err := os.Rename(tmp, final); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("handoffbrief: rename: %w", err)
	}
	return nil
}

func render(b Brief, goal string) ([]byte, error) {
	fm, err := yaml.Marshal(b)
	if err != nil {
		return nil, fmt.Errorf("handoffbrief: marshal: %w", err)
	}
	var sb strings.Builder
	sb.WriteString("---\n")
	sb.Write(fm)
	sb.WriteString("---\n\n# Migration goal\n\n")
	g := strings.TrimSpace(goal)
	if g == "" {
		sb.WriteString("(user skipped intent capture; ask before proceeding)\n")
	} else {
		sb.WriteString(g)
		sb.WriteString("\n")
	}
	return []byte(sb.String()), nil
}

// Read parses an existing HANDOFF.md into Brief + goal. Used by the
// review page on resume and by tests for round-trip checks.
//
// Returns ErrInvalidBrief if schema_version doesn't match. Other
// parse errors are returned wrapped.
func Read(workdir string) (Brief, string, error) {
	p := filepath.Join(workdir, FileName)
	raw, err := os.ReadFile(p)
	if err != nil {
		return Brief{}, "", fmt.Errorf("handoffbrief: read %s: %w", p, err)
	}
	return parse(raw)
}

func parse(raw []byte) (Brief, string, error) {
	const sep = "---\n"
	if !strings.HasPrefix(string(raw), sep) {
		return Brief{}, "", fmt.Errorf("handoffbrief: missing leading frontmatter")
	}
	rest := raw[len(sep):]
	end := strings.Index(string(rest), "\n"+sep)
	if end < 0 {
		return Brief{}, "", fmt.Errorf("handoffbrief: missing closing frontmatter")
	}
	fm := rest[:end+1]
	body := rest[end+len("\n"+sep):]

	var b Brief
	if err := yaml.Unmarshal(fm, &b); err != nil {
		return Brief{}, "", fmt.Errorf("handoffbrief: unmarshal: %w", err)
	}
	if b.SchemaVersion != SchemaVersion {
		return Brief{}, "", fmt.Errorf("%w: schema_version=%d, want %d",
			ErrInvalidBrief, b.SchemaVersion, SchemaVersion)
	}

	// Drop the "# Migration goal" header line. If the body equals the
	// "skipped" notice, return an empty goal (round-trip stability).
	goal := strings.TrimSpace(string(body))
	goal = strings.TrimPrefix(goal, "# Migration goal")
	goal = strings.TrimSpace(goal)
	if goal == "(user skipped intent capture; ask before proceeding)" {
		goal = ""
	}
	return b, goal, nil
}
