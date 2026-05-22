package aws_test

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/aws"
)

type stubService struct {
	id   aws.Identity
	stk  []aws.Stack
	cls  []aws.Cluster
	err  error
}

func (s stubService) WhoAmI(_ context.Context) (aws.Identity, error)       { return s.id, s.err }
func (s stubService) ListStacks(_ context.Context) ([]aws.Stack, error)    { return s.stk, s.err }
func (s stubService) ListClusters(_ context.Context) ([]aws.Cluster, error) { return s.cls, s.err }

func TestServiceContract_HappyPath(t *testing.T) {
	var s aws.Service = stubService{
		id:  aws.Identity{Account: "111122223333", UserID: "AIDA…", ARN: "arn:aws:iam::111122223333:user/me"},
		stk: []aws.Stack{{Name: "ma-bootstrap", Status: "CREATE_COMPLETE", Created: "2024-01-01T00:00:00Z"}},
		cls: []aws.Cluster{{Name: "ma-prod", Status: "ACTIVE", Version: "1.30"}},
	}
	id, err := s.WhoAmI(context.Background())
	require.NoError(t, err)
	require.Equal(t, "111122223333", id.Account)

	stk, err := s.ListStacks(context.Background())
	require.NoError(t, err)
	require.Len(t, stk, 1)
	require.Equal(t, "ma-bootstrap", stk[0].Name)

	cls, err := s.ListClusters(context.Background())
	require.NoError(t, err)
	require.Len(t, cls, 1)
	require.Equal(t, "1.30", cls[0].Version)
}

func TestServiceContract_NilForOptional(t *testing.T) {
	// Service is documented as "may be nil" on Workspace; verify the
	// nil interface value compares equal to nil so callers can do the
	// idiomatic check.
	var s aws.Service
	require.Nil(t, s)
}

func TestServiceContract_PropagatesError(t *testing.T) {
	var s aws.Service = stubService{err: errors.New("creds")}
	_, err := s.WhoAmI(context.Background())
	require.Error(t, err)
}
