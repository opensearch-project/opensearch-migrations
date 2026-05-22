package aws_test

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/aws"
)

// FakeService — scripted test double used by every page-level test
// that touches AWS (welcome, wizard, review). Nothing in this package
// imports the AWS SDK; tests stay fast and offline.

func TestFakeService_WhoAmI_ReturnsScripted(t *testing.T) {
	want := aws.Identity{Account: "123456789012", UserID: "AIDAEXAMPLE", ARN: "arn:aws:iam::123456789012:user/alice"}
	s := aws.NewFakeService(aws.FakeScript{Identity: want})

	got, err := s.WhoAmI(context.Background())
	require.NoError(t, err)
	require.Equal(t, want, got)
}

func TestFakeService_WhoAmI_PropagatesError(t *testing.T) {
	want := errors.New("expired credentials")
	s := aws.NewFakeService(aws.FakeScript{IdentityError: want})
	_, err := s.WhoAmI(context.Background())
	require.ErrorIs(t, err, want)
}

func TestFakeService_ListStacks_ReturnsScripted(t *testing.T) {
	want := []aws.Stack{
		{Name: "ma-cfn", Status: "CREATE_COMPLETE", Created: "2025-01-15T10:30:00Z"},
		{Name: "vpc", Status: "UPDATE_COMPLETE", Created: "2024-12-01T09:00:00Z"},
	}
	s := aws.NewFakeService(aws.FakeScript{Stacks: want})
	got, err := s.ListStacks(context.Background())
	require.NoError(t, err)
	require.Equal(t, want, got)
}

func TestFakeService_ListClusters_ReturnsScripted(t *testing.T) {
	want := []aws.Cluster{
		{Name: "ma-eks", Status: "ACTIVE", Version: "1.30"},
	}
	s := aws.NewFakeService(aws.FakeScript{Clusters: want})
	got, err := s.ListClusters(context.Background())
	require.NoError(t, err)
	require.Equal(t, want, got)
}

func TestFakeService_AllMethods_RespectContextCancel(t *testing.T) {
	s := aws.NewFakeService(aws.FakeScript{
		Identity: aws.Identity{Account: "123"},
		Stacks:   []aws.Stack{{Name: "x"}},
		Clusters: []aws.Cluster{{Name: "y"}},
	})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := s.WhoAmI(ctx)
	require.ErrorIs(t, err, context.Canceled)
	_, err = s.ListStacks(ctx)
	require.ErrorIs(t, err, context.Canceled)
	_, err = s.ListClusters(ctx)
	require.ErrorIs(t, err, context.Canceled)
}

func TestFakeService_UnscriptedMethods_ReturnEmptyNotPanic(t *testing.T) {
	// Forgetting to script a method must NOT panic — that would
	// cause flaky-feeling test failures depending on which page test
	// runs first. The interface contract: zero value + nil error.
	// (Tests that DO want strict-fake behaviour should set
	// FakeScript.Strict and assert ErrUnscripted.)
	s := aws.NewFakeService(aws.FakeScript{})

	id, err := s.WhoAmI(context.Background())
	require.NoError(t, err)
	require.Equal(t, aws.Identity{}, id)

	stacks, err := s.ListStacks(context.Background())
	require.NoError(t, err)
	require.Nil(t, stacks)

	clusters, err := s.ListClusters(context.Background())
	require.NoError(t, err)
	require.Nil(t, clusters)
}

func TestFakeService_StrictMode_UnscriptedReturnsErrUnscripted(t *testing.T) {
	// In strict mode, a missing script for a called method is a
	// loud test failure — used by handoff tests that need to
	// guarantee they only touched expected APIs.
	s := aws.NewFakeService(aws.FakeScript{Strict: true})

	_, err := s.WhoAmI(context.Background())
	require.ErrorIs(t, err, aws.ErrUnscripted)
	_, err = s.ListStacks(context.Background())
	require.ErrorIs(t, err, aws.ErrUnscripted)
	_, err = s.ListClusters(context.Background())
	require.ErrorIs(t, err, aws.ErrUnscripted)
}

func TestFakeService_StrictMode_ScriptedMethodSucceeds(t *testing.T) {
	s := aws.NewFakeService(aws.FakeScript{
		Strict:   true,
		Identity: aws.Identity{Account: "123"},
	})
	got, err := s.WhoAmI(context.Background())
	require.NoError(t, err)
	require.Equal(t, "123", got.Account)
}
