# Plan: CFN Deployment in aws-bootstrap.sh + Jenkins Pipeline Refactor

## Plan Overview

Two workstreams: (A) enhance `aws-bootstrap.sh` with CFN deployment capability, and (B) refactor the Jenkins pipeline to use it.

---

## Part A: `aws-bootstrap.sh` Changes

### A1. New flags and validation

New flags:
- `--deploy-create-vpc-cfn` — deploy the create-VPC EKS CFN template
- `--deploy-import-vpc-cfn` — deploy the import-VPC EKS CFN template
- `--build-cfn` — build templates from source via gradle (requires one of the deploy flags)
- `--stack-name <name>` — CFN stack name (required when a deploy flag is set)
- `--vpc-id <id>` — required for import-vpc only
- `--subnet-ids <id1,id2,...>` — required for import-vpc only
- `--eks-access-principal-arn <arn>` — optional; creates an EKS access entry for the given IAM principal with cluster admin policy. Useful for CI roles or cross-account access after a fresh CFN deploy.

Reuse existing `--stage` for the CFN `Stage` parameter (same concept — stage name in export names). Defaults to `dev` when a deploy flag is set and `--stage` is not provided.

Validation rules:
- At most one of `--deploy-create-vpc-cfn` / `--deploy-import-vpc-cfn`
- `--build-cfn` errors if neither deploy flag is set
- `--stack-name` required when either deploy flag is set
- `--vpc-id` and `--subnet-ids` required when `--deploy-import-vpc-cfn` is set, error if used with create-vpc
- `--vpc-id` / `--subnet-ids` error if used without `--deploy-import-vpc-cfn`

### A2. Restructure script execution order

New order:
1. Parse args + validate
2. Check tools
3. **Git pull (MOVED UP from after EKS setup)** — needed before `--build-cfn` can run gradle
4. **CFN deployment (NEW, if deploy flag set)**
   - If `--build-cfn`: run `./gradlew :deployment:migration-assistant-solution:cdkSynthMinified` from `$base_dir`, deploy from local template file
   - If not `--build-cfn`: download S3 template to temp file, deploy from that
   - `aws cloudformation deploy --template-file ... --stack-name ... --parameter-overrides ... --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM`
5. Get CFN exports (existing `get_cfn_export`)
6. Set env vars (existing)
7. `aws eks update-kubeconfig` (existing)
8. **EKS access entry (NEW, if `--eks-access-principal-arn` set)** — after we have the cluster name from exports, before any kubectl commands
9. Nodepool check/re-enable (existing, uses aws eks API)
10. Create namespace + set context (existing, first kubectl usage)
11. Build images (existing, if `--build-images-locally`)
12. Helm install (existing)
13. Dashboards (existing)
14. Disable pool / console exec (existing)

### A3. Template sources

| Mode | Create VPC | Import VPC |
|------|-----------|------------|
| `--build-cfn` | `$base_dir/deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Create-VPC-eks.template.json` | `$base_dir/deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Import-VPC-eks.template.json` |
| No `--build-cfn` | S3 URL (downloaded via curl) | S3 URL (downloaded via curl) |

S3 URLs:
- `https://solutions-reference.s3.amazonaws.com/migration-assistant-for-amazon-opensearch-service/latest/migration-assistant-for-amazon-opensearch-service-create-vpc-eks.template`
- `https://solutions-reference.s3.amazonaws.com/migration-assistant-for-amazon-opensearch-service/latest/migration-assistant-for-amazon-opensearch-service-import-vpc-eks.template`

Note: `aws cloudformation deploy` only supports `--template-file`, not `--template-url`, so S3 templates are downloaded to a temp file first.

### A4. CFN parameter overrides

- **Create VPC**: `Stage=$stage_filter`
- **Import VPC**: `Stage=$stage_filter VPCId=$vpc_id VPCSubnetIds=$subnet_ids`

(Matches `CfnParameter` definitions in `solutions-stack-eks.ts`)

### A5. Discovery commands

When `--deploy-import-vpc-cfn` is set and required params are missing, error messages include discovery output:
- Missing `--vpc-id`: lists available VPCs via `aws ec2 describe-vpcs`
- Missing `--subnet-ids`: lists subnets in the specified VPC via `aws ec2 describe-subnets`

Pure shell — no interactive tools required.

### A6. EKS access entry

When `--eks-access-principal-arn` is provided:
1. Check if access entry already exists (`aws eks describe-access-entry`)
2. If not, create it (`aws eks create-access-entry --type STANDARD`)
3. Associate `AmazonEKSClusterAdminPolicy` with cluster scope

This runs after `get_cfn_export` (needs cluster name) and before any kubectl commands.

---

## Part B: Jenkins Pipeline Changes

### B1. New stage order

```
1. Checkout                    (unchanged)
2. Test Caller Identity        (unchanged)
3. Build                       (unchanged)
4. Deploy Clusters             (unchanged — creates test ES/OS clusters, outputs vpcId/subnetIds)
5. Deploy & Bootstrap MA       (NEW — single aws-bootstrap.sh call replaces 4 old stages)
6. Post-Cluster Setup          (NEW — test-specific configmaps + security group rules)
7. Perform Python E2E Tests    (unchanged)
```

### B2. Combined "Deploy & Bootstrap MA" stage

**Jenkins pre-bootstrap** (same step, before the script call):
- `withAWS(role: 'JenkinsDeploymentRole', ...)` — assume deployment role
- Extract `vpcId` / `subnetIds` from `env.clusterDetailsJson`
- Set `env.STACK_NAME_SUFFIX`

**aws-bootstrap.sh invocation:**
```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-import-vpc-cfn \
  --build-cfn \
  --stack-name "Migration-Assistant-Infra-Import-VPC-eks-${STACK_NAME_SUFFIX}" \
  --vpc-id "$vpcId" \
  --subnet-ids "$subnetIds" \
  --stage "${maStageName}" \
  --eks-access-principal-arn "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole" \
  --build-images-locally \
  --skip-git-pull \
  --base-dir "$(pwd)" \
  --skip-console-exec \
  --region us-east-1
```

Replaces:
- **Deploy MA Stack** → `--deploy-import-vpc-cfn --build-cfn --stack-name ...`
- **Configure EKS** (infra parts) → existing aws-bootstrap.sh flow + `--eks-access-principal-arn`
- **Build Docker Images** → `--build-images-locally`
- **Install Helm Chart** → existing helm install logic

**Jenkins post-bootstrap** (same step, after the script call):
- Read CFN exports to capture `env.eksClusterName`, `env.clusterSecurityGroup`, `env.registryEndpoint` for cleanup

### B3. New "Post-Cluster Setup" stage (test-specific, NOT in aws-bootstrap.sh)

Extracted from old "Configure EKS" — depends on `clusterDetailsJson`:
- Create source/target cluster configmaps (`kubectl create configmap ...`)
- `authorize-security-group-ingress` to cross-link EKS SG → test cluster SG

### B4. Cleanup (`post.always`)

Structurally unchanged. The `env.eksClusterName` / `env.clusterSecurityGroup` values are captured in B2 post-bootstrap block. One change: cleanup uses `aws cloudformation delete-stack` instead of `cdk destroy` for the MA stack (since we deployed with `aws cloudformation deploy`, not CDK).

---

## User-value boundary

Everything in `aws-bootstrap.sh` is useful to any user:
- Deploy CFN infrastructure
- Create EKS access entries for any IAM principal
- Configure kubectl, namespaces, nodepools
- Build/pull images
- Install helm chart + dashboards

Nothing test-specific or Jenkins-specific leaks into the script. Test-only concerns (configmaps for test clusters, security group cross-linking) stay in the Jenkins pipeline's "Post-Cluster Setup" stage.
