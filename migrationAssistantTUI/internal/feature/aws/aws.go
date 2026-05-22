// Package aws declares the read-only AWS service caller interface.
// "Read-only" is load-bearing: the TUI never creates / modifies /
// deletes AWS resources directly. Mutations happen via the deploy
// driver, which shells out to the operator's tools (helm, kubectl,
// aws cloudformation deploy) — never via the SDK from in-process.
//
// This boundary keeps the TUI's blast radius small and makes the
// "what did the TUI just do?" answer auditable to "called STS
// GetCallerIdentity, listed CFN stacks, listed EKS clusters."
package aws

import "context"

// Identity is the result of an STS GetCallerIdentity call.
type Identity struct {
	Account string
	UserID  string
	ARN     string
}

// Stack is one CloudFormation stack summary.
type Stack struct {
	Name    string
	Status  string // "CREATE_COMPLETE", "UPDATE_FAILED", …
	Created string // ISO8601 — kept as string to avoid forcing a time.Time on every UI string conversion
}

// Cluster is one EKS cluster summary.
type Cluster struct {
	Name    string
	Status  string
	Version string // Kubernetes minor ("1.30")
}

// Service is the aggregate read-only AWS interface.
//
// Implementations:
//   - Real wraps aws-sdk-go-v2 with the default credential chain.
//     Per-call ctx is honoured for cancellation.
//   - Fake scripts each method's return for tests; never panics on
//     unscripted methods (returns the typed zero value + a sentinel
//     "fake: unscripted" error so a test that forgot to wire one
//     fails loudly rather than seeing empty).
//
// All methods MUST be safe to call concurrently. The TUI fans out
// these calls during preflight (welcome page may call STS while
// review page calls CFN).
type Service interface {
	// WhoAmI returns the caller identity. Used on the welcome page
	// banner and embedded into HANDOFF.md so the bundled skill knows
	// which account it was generated for.
	WhoAmI(ctx context.Context) (Identity, error)

	// ListStacks returns CloudFormation stacks in the configured
	// region. Read-only; never paginates internally — returns the
	// first page and the UI never asks for more (operators with 100+
	// stacks aren't the TUI's audience).
	ListStacks(ctx context.Context) ([]Stack, error)

	// ListClusters returns EKS clusters in the configured region.
	// Same pagination behaviour as ListStacks.
	ListClusters(ctx context.Context) ([]Cluster, error)
}
