package aws_test

import (
	"context"
	"errors"
	"testing"
	"time"

	cfntypes "github.com/aws/aws-sdk-go-v2/service/cloudformation/types"
	ekstypes "github.com/aws/aws-sdk-go-v2/service/eks/types"

	cfn "github.com/aws/aws-sdk-go-v2/service/cloudformation"
	eks "github.com/aws/aws-sdk-go-v2/service/eks"
	sts "github.com/aws/aws-sdk-go-v2/service/sts"
	"github.com/stretchr/testify/require"

	pkgaws "github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/aws"
)

// stubSTS / stubCFN / stubEKS are hand-rolled doubles for the three
// SDK clients RealService talks to. The hand-roll is cheaper than
// pulling in a generated mock framework, and these interfaces are
// tiny (one method each).

type stubSTS struct {
	out *sts.GetCallerIdentityOutput
	err error
}

func (s *stubSTS) GetCallerIdentity(ctx context.Context, _ *sts.GetCallerIdentityInput, _ ...func(*sts.Options)) (*sts.GetCallerIdentityOutput, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if s.err != nil {
		return nil, s.err
	}
	return s.out, nil
}

type stubCFN struct {
	out *cfn.ListStacksOutput
	err error
}

func (c *stubCFN) ListStacks(ctx context.Context, _ *cfn.ListStacksInput, _ ...func(*cfn.Options)) (*cfn.ListStacksOutput, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if c.err != nil {
		return nil, c.err
	}
	return c.out, nil
}

type stubEKS struct {
	out *eks.ListClustersOutput
	desc map[string]*eks.DescribeClusterOutput
	err error
}

func (e *stubEKS) ListClusters(ctx context.Context, _ *eks.ListClustersInput, _ ...func(*eks.Options)) (*eks.ListClustersOutput, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if e.err != nil {
		return nil, e.err
	}
	return e.out, nil
}

func (e *stubEKS) DescribeCluster(ctx context.Context, in *eks.DescribeClusterInput, _ ...func(*eks.Options)) (*eks.DescribeClusterOutput, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if e.err != nil {
		return nil, e.err
	}
	if d, ok := e.desc[*in.Name]; ok {
		return d, nil
	}
	return &eks.DescribeClusterOutput{}, nil
}

func newRealForTest(s *stubSTS, c *stubCFN, e *stubEKS) pkgaws.Service {
	return pkgaws.NewRealServiceForTest(s, c, e)
}

// Helpers for SDK pointer types
func sp(s string) *string { return &s }
func tp(t time.Time) *time.Time { return &t }

func TestRealService_WhoAmI_HappyPath(t *testing.T) {
	svc := newRealForTest(
		&stubSTS{out: &sts.GetCallerIdentityOutput{
			Account: sp("123456789012"),
			UserId:  sp("AIDAEXAMPLE"),
			Arn:     sp("arn:aws:iam::123456789012:user/alice"),
		}},
		&stubCFN{},
		&stubEKS{},
	)
	got, err := svc.WhoAmI(context.Background())
	require.NoError(t, err)
	require.Equal(t, "123456789012", got.Account)
	require.Equal(t, "AIDAEXAMPLE", got.UserID)
	require.Equal(t, "arn:aws:iam::123456789012:user/alice", got.ARN)
}

func TestRealService_WhoAmI_ErrorBubblesUp(t *testing.T) {
	want := errors.New("ExpiredToken")
	svc := newRealForTest(&stubSTS{err: want}, &stubCFN{}, &stubEKS{})
	_, err := svc.WhoAmI(context.Background())
	require.ErrorIs(t, err, want)
}

func TestRealService_WhoAmI_HandlesNilFields(t *testing.T) {
	// AWS SDK uses *string for optional fields. A response with all
	// fields nil must not panic — RealService dereferences safely.
	svc := newRealForTest(
		&stubSTS{out: &sts.GetCallerIdentityOutput{}},
		&stubCFN{},
		&stubEKS{},
	)
	got, err := svc.WhoAmI(context.Background())
	require.NoError(t, err)
	require.Equal(t, "", got.Account)
	require.Equal(t, "", got.UserID)
	require.Equal(t, "", got.ARN)
}

func TestRealService_ListStacks_HappyPath(t *testing.T) {
	now := time.Date(2025, 1, 15, 10, 30, 0, 0, time.UTC)
	svc := newRealForTest(&stubSTS{}, &stubCFN{out: &cfn.ListStacksOutput{
		StackSummaries: []cfntypes.StackSummary{
			{StackName: sp("ma-cfn"), StackStatus: cfntypes.StackStatusCreateComplete, CreationTime: tp(now)},
			{StackName: sp("vpc"), StackStatus: cfntypes.StackStatusUpdateComplete, CreationTime: tp(now.Add(-24 * time.Hour))},
		},
	}}, &stubEKS{})
	got, err := svc.ListStacks(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 2)
	require.Equal(t, "ma-cfn", got[0].Name)
	require.Equal(t, string(cfntypes.StackStatusCreateComplete), got[0].Status)
	require.Equal(t, "2025-01-15T10:30:00Z", got[0].Created)
}

func TestRealService_ListStacks_ErrorBubblesUp(t *testing.T) {
	want := errors.New("AccessDenied")
	svc := newRealForTest(&stubSTS{}, &stubCFN{err: want}, &stubEKS{})
	_, err := svc.ListStacks(context.Background())
	require.ErrorIs(t, err, want)
}

func TestRealService_ListStacks_HandlesNilFields(t *testing.T) {
	svc := newRealForTest(&stubSTS{}, &stubCFN{out: &cfn.ListStacksOutput{
		StackSummaries: []cfntypes.StackSummary{
			{}, // all nil
		},
	}}, &stubEKS{})
	got, err := svc.ListStacks(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 1)
	require.Equal(t, "", got[0].Name)
}

func TestRealService_ListClusters_HappyPath(t *testing.T) {
	svc := newRealForTest(&stubSTS{}, &stubCFN{}, &stubEKS{
		out: &eks.ListClustersOutput{Clusters: []string{"ma-eks", "other"}},
		desc: map[string]*eks.DescribeClusterOutput{
			"ma-eks": {Cluster: &ekstypes.Cluster{
				Name: sp("ma-eks"), Status: ekstypes.ClusterStatusActive, Version: sp("1.30"),
			}},
			"other": {Cluster: &ekstypes.Cluster{
				Name: sp("other"), Status: ekstypes.ClusterStatusCreating, Version: sp("1.29"),
			}},
		},
	})
	got, err := svc.ListClusters(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 2)
	require.Equal(t, "ma-eks", got[0].Name)
	require.Equal(t, string(ekstypes.ClusterStatusActive), got[0].Status)
	require.Equal(t, "1.30", got[0].Version)
}

func TestRealService_ListClusters_DescribeFailureSkipsClusterButContinues(t *testing.T) {
	// Per-cluster Describe is a separate API call. One failing must
	// not break the page — surface the rest with empty Status/Version
	// so the operator sees the cluster name and can investigate.
	svc := newRealForTest(&stubSTS{}, &stubCFN{}, &stubEKS{
		out: &eks.ListClustersOutput{Clusters: []string{"ok", "broken"}},
		desc: map[string]*eks.DescribeClusterOutput{
			"ok": {Cluster: &ekstypes.Cluster{Name: sp("ok"), Status: ekstypes.ClusterStatusActive, Version: sp("1.30")}},
			// "broken" intentionally absent — stub returns empty Output.
		},
	})
	got, err := svc.ListClusters(context.Background())
	require.NoError(t, err)
	require.Len(t, got, 2)
	require.Equal(t, "ok", got[0].Name)
	require.Equal(t, "broken", got[1].Name)
	require.Equal(t, "", got[1].Status, "describe-not-found yields empty Status, not error")
}

func TestRealService_ListClusters_ErrorBubblesUp(t *testing.T) {
	want := errors.New("AccessDenied")
	svc := newRealForTest(&stubSTS{}, &stubCFN{}, &stubEKS{err: want})
	_, err := svc.ListClusters(context.Background())
	require.ErrorIs(t, err, want)
}

func TestRealService_AllMethods_RespectContextCancel(t *testing.T) {
	svc := newRealForTest(
		&stubSTS{out: &sts.GetCallerIdentityOutput{Account: sp("x")}},
		&stubCFN{out: &cfn.ListStacksOutput{}},
		&stubEKS{out: &eks.ListClustersOutput{}},
	)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := svc.WhoAmI(ctx)
	require.ErrorIs(t, err, context.Canceled)
	_, err = svc.ListStacks(ctx)
	require.ErrorIs(t, err, context.Canceled)
	_, err = svc.ListClusters(ctx)
	require.ErrorIs(t, err, context.Canceled)
}
