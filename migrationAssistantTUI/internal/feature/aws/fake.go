package aws

import (
	"context"
	"errors"
)

// ErrUnscripted is returned by FakeService methods when Strict mode
// is on and the test forgot to script that method's response. Tests
// gate via errors.Is(err, ErrUnscripted) for fast feedback.
var ErrUnscripted = errors.New("aws: fake method called without script")

// FakeScript is the recipe for a FakeService. Per-method fields:
//
//   - Identity / IdentityError: returned by WhoAmI.
//   - Stacks / StacksError: returned by ListStacks.
//   - Clusters / ClustersError: returned by ListClusters.
//
// Strict, when true, makes any unscripted method call return
// ErrUnscripted instead of the typed zero value. Use Strict in tests
// that must prove their code path didn't make extra AWS calls.
type FakeScript struct {
	Identity      Identity
	IdentityError error

	Stacks      []Stack
	StacksError error

	Clusters      []Cluster
	ClustersError error

	Strict bool
}

type fakeService struct{ s FakeScript }

// NewFakeService returns a Service that replays the given script.
//
// Concurrency: safe — the script is read-only after construction.
// All three methods just return scripted values; they don't touch
// shared state.
func NewFakeService(s FakeScript) Service { return &fakeService{s: s} }

func (f *fakeService) WhoAmI(ctx context.Context) (Identity, error) {
	if err := ctx.Err(); err != nil {
		return Identity{}, err
	}
	if f.s.IdentityError != nil {
		return Identity{}, f.s.IdentityError
	}
	if f.s.Strict && f.s.Identity == (Identity{}) {
		return Identity{}, ErrUnscripted
	}
	return f.s.Identity, nil
}

func (f *fakeService) ListStacks(ctx context.Context) ([]Stack, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if f.s.StacksError != nil {
		return nil, f.s.StacksError
	}
	if f.s.Stacks == nil {
		if f.s.Strict {
			return nil, ErrUnscripted
		}
		return nil, nil
	}
	out := make([]Stack, len(f.s.Stacks))
	copy(out, f.s.Stacks)
	return out, nil
}

func (f *fakeService) ListClusters(ctx context.Context) ([]Cluster, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if f.s.ClustersError != nil {
		return nil, f.s.ClustersError
	}
	if f.s.Clusters == nil {
		if f.s.Strict {
			return nil, ErrUnscripted
		}
		return nil, nil
	}
	out := make([]Cluster, len(f.s.Clusters))
	copy(out, f.s.Clusters)
	return out, nil
}
