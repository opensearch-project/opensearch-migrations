package aws

import (
	"context"
	"fmt"

	awssdk "github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	cfn "github.com/aws/aws-sdk-go-v2/service/cloudformation"
	eks "github.com/aws/aws-sdk-go-v2/service/eks"
	sts "github.com/aws/aws-sdk-go-v2/service/sts"
)

// ----------------------------------------------------------------------------
// RealService — production. Wraps the AWS SDK v2.
//
// Architecture: three narrow client interfaces (stsAPI, cfnAPI,
// eksAPI), one per backing service. Each interface contains exactly
// the SDK methods RealService calls. Tests substitute hand-rolled
// stubs; production wires the real *sts.Client / *cfn.Client /
// *eks.Client (which satisfy the interfaces structurally).
//
// The interfaces are unexported because they're not part of the
// package's public API — only the constructor seam
// NewRealServiceForTest uses them, and that's clearly named to
// discourage misuse from production callsites.
//
// Why hand-rolled stubs and not a mock framework: each interface is
// 1-2 methods. A generated mock would dwarf the production code in
// size, and the hand-roll lets tests express exact stub behaviour
// (per-cluster Describe responses) inline.
// ----------------------------------------------------------------------------

// stsAPI is the tiny slice of the STS SDK we use.
type stsAPI interface {
	GetCallerIdentity(ctx context.Context, in *sts.GetCallerIdentityInput, opts ...func(*sts.Options)) (*sts.GetCallerIdentityOutput, error)
}

// cfnAPI is the tiny slice of the CloudFormation SDK we use.
// ListStacks pagination is intentionally not exercised — the TUI
// renders the first page only, per the documented contract on
// Service.ListStacks.
type cfnAPI interface {
	ListStacks(ctx context.Context, in *cfn.ListStacksInput, opts ...func(*cfn.Options)) (*cfn.ListStacksOutput, error)
}

// eksAPI is the tiny slice of the EKS SDK we use. ListClusters
// returns names only; we Describe each cluster to get Status and
// Version. The Describe loop runs sequentially — operator clusters
// per region are typically <10, fanning out is unnecessary risk.
type eksAPI interface {
	ListClusters(ctx context.Context, in *eks.ListClustersInput, opts ...func(*eks.Options)) (*eks.ListClustersOutput, error)
	DescribeCluster(ctx context.Context, in *eks.DescribeClusterInput, opts ...func(*eks.Options)) (*eks.DescribeClusterOutput, error)
}

type realService struct {
	sts stsAPI
	cfn cfnAPI
	eks eksAPI
}

// NewRealService constructs a Service backed by the AWS SDK using
// the default credential chain (env, shared profile, IMDS, …) and
// the given region. Returns the load error from config.LoadDefault
// so the welcome page can surface "no creds" before any API call.
//
// Region selection: if region == "", config.LoadDefaultConfig
// resolves it from AWS_REGION / AWS_DEFAULT_REGION / shared profile
// in that order. Operators with a region pinned in config will get
// it for free; tests shouldn't use NewRealService at all.
func NewRealService(ctx context.Context, region string) (Service, error) {
	loadOpts := []func(*config.LoadOptions) error{}
	if region != "" {
		loadOpts = append(loadOpts, config.WithRegion(region))
	}
	cfg, err := config.LoadDefaultConfig(ctx, loadOpts...)
	if err != nil {
		return nil, fmt.Errorf("aws: load default config: %w", err)
	}
	return &realService{
		sts: sts.NewFromConfig(cfg),
		cfn: cfn.NewFromConfig(cfg),
		eks: eks.NewFromConfig(cfg),
	}, nil
}

// NewRealServiceForTest is a wide constructor that lets tests
// substitute the three SDK clients with stubs. NOT for production —
// production should always call NewRealService. The name is
// deliberately ugly so reviewers catch any production callsites.
func NewRealServiceForTest(stsClient stsAPI, cfnClient cfnAPI, eksClient eksAPI) Service {
	return &realService{sts: stsClient, cfn: cfnClient, eks: eksClient}
}

func (r *realService) WhoAmI(ctx context.Context) (Identity, error) {
	if err := ctx.Err(); err != nil {
		return Identity{}, err
	}
	out, err := r.sts.GetCallerIdentity(ctx, &sts.GetCallerIdentityInput{})
	if err != nil {
		return Identity{}, err
	}
	return Identity{
		Account: awssdk.ToString(out.Account),
		UserID:  awssdk.ToString(out.UserId),
		ARN:     awssdk.ToString(out.Arn),
	}, nil
}

func (r *realService) ListStacks(ctx context.Context) ([]Stack, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	out, err := r.cfn.ListStacks(ctx, &cfn.ListStacksInput{})
	if err != nil {
		return nil, err
	}
	res := make([]Stack, 0, len(out.StackSummaries))
	for _, s := range out.StackSummaries {
		stack := Stack{
			Name:   awssdk.ToString(s.StackName),
			Status: string(s.StackStatus),
		}
		if s.CreationTime != nil {
			stack.Created = s.CreationTime.UTC().Format("2006-01-02T15:04:05Z")
		}
		res = append(res, stack)
	}
	return res, nil
}

func (r *realService) ListClusters(ctx context.Context) ([]Cluster, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	listed, err := r.eks.ListClusters(ctx, &eks.ListClustersInput{})
	if err != nil {
		return nil, err
	}
	res := make([]Cluster, 0, len(listed.Clusters))
	for _, name := range listed.Clusters {
		if err := ctx.Err(); err != nil {
			return res, err
		}
		c := Cluster{Name: name}
		// Per-cluster Describe failure is non-fatal — the operator
		// still sees the cluster name with empty status/version, can
		// investigate the AccessDenied/ResourceNotFound on their own.
		desc, derr := r.eks.DescribeCluster(ctx, &eks.DescribeClusterInput{Name: &name})
		if derr == nil && desc != nil && desc.Cluster != nil {
			c.Status = string(desc.Cluster.Status)
			c.Version = awssdk.ToString(desc.Cluster.Version)
		}
		res = append(res, c)
	}
	return res, nil
}
