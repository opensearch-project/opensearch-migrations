# aws-bootstrap.sh CFN Deployment — Implementation Summary

## Files Changed

| File | Change |
|------|--------|
| `deployment/k8s/aws/aws-bootstrap.sh` | New flags, validation, CFN deploy, EKS access entry, discovery commands, config visibility, smart `base_dir`, version pinning, packaged chart as default |
| `vars/eksIntegPipeline.groovy` | 4 stages → 1 combined + 1 post-cluster-setup; cleanup uses `aws cloudformation delete-stack` |
| `deployment/k8s/aws/README.md` | Rewritten: end-user quick start, developer workflow, test clusters with `--post-renderer` |
| `.github/workflows/release-drafter.yml` | Added `aws-bootstrap.sh` and dashboard JSONs to release artifacts |

---

## aws-bootstrap.sh Changes

### Current Flags

```
CloudFormation deployment options (exactly one required):
  --deploy-create-vpc-cfn                   Deploy the Create-VPC EKS CloudFormation template.
  --deploy-import-vpc-cfn                   Deploy the Import-VPC EKS CloudFormation template.
  --skip-cfn-deploy                         Skip CloudFormation deployment (stack already deployed).

CFN sub-options:
  --build-cfn                               Build CFN templates from source (gradle cdkSynthMinified).
  --stack-name <name>                       CloudFormation stack name (required with --deploy-*-cfn).
  --vpc-id <id>                             VPC ID (required with --deploy-import-vpc-cfn).
  --subnet-ids <id1,id2>                    Comma-separated subnet IDs (required with --deploy-import-vpc-cfn).

Version and artifact options:
  --version <tag>                           Pin release version for all downloaded artifacts.
                                            Defaults to latest GitHub release. Required when
                                            mixing --build-* flags to prevent version mismatches.
  --build-images                            Build images from source instead of pulling public ECR.
  --build-chart-and-dashboards              Use chart/dashboards from local repo instead of
                                            downloading from GitHub release (default: download).

EKS access options:
  --eks-access-principal-arn <arn>           Grant cluster-admin access to an IAM principal.

General options:
  --base-dir <path>                         Repo directory (default: derived from script location).
  --namespace <val>                         K8s namespace (default: ma).
  --stage <val>                             Stage name for CFN exports filter and CFN Stage parameter.
  --region <val>                            AWS region.
  --helm-values <path>                      Extra values file for helm install.
  --disable-general-purpose-pool            Disable EKS Auto Mode general-purpose pool.
  --skip-console-exec                       Don't exec into console pod after install.
```

### Flags Removed (vs original script)

- `--skip-git-pull`, `--org-name`, `--repo-name`, `--branch`, `--tag` — git clone block removed entirely. Script never clones the repo; `base_dir` auto-resolves from `BASH_SOURCE[0]` for `--build-*` flags.
- `--build-images-locally` → renamed to `--build-images`
- `git` removed from required tools check

### Key Design Decisions

- **Packaged chart is the default** — no repo checkout needed for end users. `--build-chart-and-dashboards` overrides to use local repo.
- **Single version resolution** — `RELEASE_VERSION` resolved once at startup from GitHub API (or `--version`), threaded through all downloads: S3 CFN templates (versioned URL), public images, helm chart tgz, dashboards.
- **Version mismatch protection** — `--version` required when using some but not all `--build-*` flags.
- **`valuesEks.yaml` extracted from tgz** — packaged chart includes it; extracted before `helm install` so both paths use the same helm command.
- **`STACK_NAME_SUFFIX` cleared for CDK synth** — prevents Jenkins env var from changing template filenames.
- **CFN uses `create-stack`/`update-stack`** — supports both `--template-body file://` (local) and `--template-url` (S3), unlike `deploy` which only supports `--template-file`.
- **Subnet parameters use bash array** — prevents comma-separated subnet IDs from being split by the AWS CLI.
- **Smart `base_dir`** — derived from `BASH_SOURCE[0]/../../..` (script location), works from any directory and any git repo.

### Execution Order

```
1. Parse args + resolve base_dir + validate
2. Resolve RELEASE_VERSION (once)
3. Check tools (jq, kubectl, helm)
4. CFN deploy (if --deploy-*-cfn, using --template-url or --template-body)
5. Get CFN exports
6. Validate required vars + print resolved config (version, account, region, stage, cluster, ECR, role)
7. Update kubeconfig
8. EKS access entry (if --eks-access-principal-arn)
9. Nodepool check / re-enable
10. Create namespace
11. Build images (if --build-images)
12. Set image flags (private ECR tags or public ECR with RELEASE_VERSION)
13. Download chart + dashboards from GitHub release, or use local repo (if --build-chart-and-dashboards)
14. Extract valuesEks.yaml from tgz (if using packaged chart)
15. Helm install (10m timeout)
16. Deploy CloudWatch dashboards
17. Disable general-purpose pool (if --disable-general-purpose-pool) / console exec
```

---

## Discovery Commands

### Missing `--vpc-id` — lists available VPCs

```
Error: --vpc-id is required with --deploy-import-vpc-cfn.

Available VPCs in us-east-1:
+---------------+-------------------------+-----------------------------------+------------+
|     CIDR      |           ID            |               Name                |   State    |
+---------------+-------------------------+-----------------------------------+------------+
|  10.212.0.0/16|  vpc-...  |  migration-assistant-vpc-src      |  available |
|  172.31.0.0/16|  vpc-...  |  None                             |  available |
+---------------+-------------------------+-----------------------------------+------------+

Re-run with: --vpc-id <vpc-id> --subnet-ids <subnet1,subnet2>
```

### Missing `--subnet-ids` — lists subnets with internet access info

```
Error: --subnet-ids is required with --deploy-import-vpc-cfn.

Available subnets in VPC vpc-... (us-east-1):
  (checking route tables for internet access...)
  subnet-...  us-east-1a  10..../18  NAT
  subnet-...  us-east-1b  10..../18   IGW (public)
  subnet-...  us-east-1b  10..../18  NAT
  subnet-...  us-east-1a  10..../18    IGW (public)

Select subnets with NAT or IGW routes (pods need internet access to pull images).
Re-run with: --subnet-ids <subnet1,subnet2>
```

---

## Config Visibility

```
Resolved configuration:
  Version                = 2.6.4
  AWS_ACCOUNT              = ACCOUNT_ID
  AWS_CFN_REGION           = us-east-1
  STAGE                    = test4
  EKS Cluster              = migration-eks-cluster-test4-us-east-1
  ECR Registry             = ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-test4-us-east-1
  Snapshot Role            = arn:aws:iam::ACCOUNT_ID:role/migration-eks-cluster-test4-us-east-1-snapshot-role

  Region source: --region flag ('us-east-1')
  Stage source:  --stage flag ('test4')
```

---

## Integration Test Results (2026-02-17/18)

| Test | CFN | Chart/Dashboards | Subnets | Result |
|------|-----|-----------------|---------|--------|
| 1. Create-VPC end-user | S3 `--template-url` | GitHub release | N/A (new VPC) | ✅ CFN + EKS. ❌ Dashboard 404 (not in 2.6.4 release yet) |
| 2. Import-VPC end-user | S3 `--template-url` | GitHub release | NAT-routed | ✅ CFN + EKS. ❌ Dashboard 404 (expected) |
| 3. Create-VPC + local build | S3 `--template-url` | Local repo | N/A (new VPC) | ✅ Full success |
| 4a. Import-VPC (first attempt) | S3 | Local repo | Mixed (1 NAT + 1 no-internet) | ❌ ImagePullBackOff — pod on no-internet subnet |
| 4b. Import-VPC (retry) | S3 `--template-url` | Local repo | NAT-routed | ✅ Full success |
| 5. Developer full build | Local `--template-body` | Local repo | N/A (new VPC) | ✅ Full success |

### Test 4a failure analysis

Used `subnet-01fdd099b179fb61c` which had no NAT gateway route. The installer pod couldn't reach `public.ecr.aws` to pull images. Root cause: the import-VPC template does NOT create VPC endpoints (ECR, S3, etc.) — only the create-VPC template does. Subnets without NAT/IGW routes cannot pull public images.

This led to adding internet access info (NAT/IGW/no-internet) to the subnet discovery output.

---

## Jenkins Pipeline Changes

### Before (5 stages)
```
Deploy Clusters → Deploy MA Stack → Configure EKS → Build Docker Images → Install Helm Chart
```

### After (3 stages)
```
Deploy Clusters → Deploy & Bootstrap MA → Post-Cluster Setup
```

**Deploy & Bootstrap MA** — single `aws-bootstrap.sh` invocation:
```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-import-vpc-cfn \
  --build-cfn \
  --stack-name "Migration-Assistant-Infra-Import-VPC-eks-${STACK_NAME_SUFFIX}" \
  --vpc-id "$vpcId" \
  --subnet-ids "$subnetIds" \
  --stage "${maStageName}" \
  --eks-access-principal-arn "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole" \
  --build-images \
  --build-chart-and-dashboards \
  --base-dir "$(pwd)" \
  --skip-console-exec \
  --region us-east-1
```

**Post-Cluster Setup** — test-specific only (not in aws-bootstrap.sh):
- Source/target cluster configmaps
- Security group cross-linking

**Cleanup** — `aws cloudformation delete-stack` replaces `cdk destroy` for the MA stack.

---

## Bugs Fixed

1. **`$prefix` unbound variable** — error handler referenced local variable from `get_cfn_export`. Now hardcoded.
2. **Discovery output swallowed** — `2>/dev/null >&2` sent both streams to /dev/null. Fixed to `>&2`.
3. **`gradlew` not found from other directories** — now uses `$base_dir/gradlew -p $base_dir`.
4. **`base_dir` wrong in other git repos** — now uses `BASH_SOURCE[0]/../../..` instead of `git rev-parse`.
5. **Comma-separated subnet IDs split by AWS CLI** — `--parameters` now uses bash array with proper quoting.
6. **`STACK_NAME_SUFFIX` env var changed CDK template filenames** — cleared before `cdkSynthMinified`.
7. **`--template-file` not supported by `create-stack`/`update-stack`** — switched to `--template-body file://` for local files, `--template-url` for S3.
8. **Helm install timeout** — bumped from default 5m to 10m for EKS Auto Mode node provisioning.
9. **Subnet discovery missing internet access info** — now checks route tables for NAT/IGW per subnet.

## Known Limitations

- **Import-VPC now supports optional VPC endpoint creation** — via `--create-vpc-endpoints` flag (or `--create-vpc-endpoints s3,ecr,ecrDocker` for a subset). CFN parameters `CreateS3Endpoint`, `CreateECREndpoint`, `CreateECRDockerEndpoint`, `CreateCloudWatchLogsEndpoint`, `CreateEFSEndpoint` added to the import-VPC template using L1 `CfnResource` constructs (imported VPCs lack route table and CIDR info needed by L2 constructs).
- **Isolated subnets require `--build-images`** — `public.ecr.aws` has no VPC endpoint, so public images can't be pulled. The script enforces this with an early check (before any slow operations). However, `--build-images` itself has a limitation: buildkit pods on the EKS cluster need to pull base images from Docker Hub and other public registries, which also have no VPC endpoints. **Workaround**: build images on a machine with internet access and push to the private ECR before running the bootstrap script.
- **Dashboard downloads fail on 2.6.4** — release-drafter change not yet published. Next release will include dashboard JSONs and `aws-bootstrap.sh` as release artifacts.
- **Subnet route check falls back to main route table** — subnets without explicit route table associations inherit the VPC's main route table. The check handles this correctly.

## VPC Endpoint Tests (2026-02-18)

| Test | Subnets | --build-images | --create-vpc-endpoints | Result |
|------|---------|---------------|----------------------|--------|
| Isolated, no --build-images | Isolated | ❌ not set | N/A | ✅ Instant error: "--build-images is required" |
| Isolated, --build-images, no endpoints | Isolated | ✅ set | not set | ✅ Early check passes, CFN deploys, post-deploy check finds missing endpoints → error |
| Isolated, --build-images, --create-vpc-endpoints | Isolated | ✅ set | all | ✅ CFN creates 5 endpoints, post-deploy check passes (all ✅), image build fails (buildkit can't pull from Docker Hub) |
| NAT subnets, no --build-images | NAT | ❌ not set | N/A | ✅ Check passes, continues normally |
| --ignore-checks | Isolated | ✅ set | N/A | ✅ Bypasses all checks |
