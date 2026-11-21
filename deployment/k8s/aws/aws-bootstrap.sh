#!/bin/bash
# =============================================================================
# Bootstrap EKS Environment for OpenSearch Migration Assistant
#
# This script handles the full lifecycle of deploying the Migration Assistant
# onto AWS EKS:
#   1. (Optional) Deploy the CloudFormation stack that creates the EKS cluster,
#      ECR registry, IAM roles, and networking (VPC or imported VPC).
#   2. Read CloudFormation exports to discover cluster details.
#   3. Configure kubectl and (optionally) grant EKS access to an IAM principal.
#   4. Install the Migration Assistant Helm chart with CloudWatch dashboards.
#
# THREE MODES OF OPERATION:
#
#   1. DEFAULT (no --build, no --version):
#      Downloads all artifacts from the latest GitHub release. This is the
#      simplest way to deploy — no repo checkout needed.
#
#   2. --build:
#      Builds all artifacts from a local repo checkout: container images,
#      CFN templates, Helm chart, and dashboards. Requires --base-dir or
#      running from within the repo.
#      When combined with --ma-images-source, images are copied from another
#      ECR registry instead of being built (CFN + chart are still built).
#
#   3. --version <tag>:
#      Downloads all artifacts for a specific release version (e.g., 2.9.0).
#      Use this to pin a known-good release.
#
# ARCHITECTURE NOTES FOR FUTURE CHANGES:
#   - Version is resolved ONCE at startup and threaded through all downloads.
#     To add a new downloaded artifact, use $BOOTSTRAP_ARTIFACT_VERSION for its URL.
#   - The $build flag gates all build-from-source sections. To add a new
#     buildable component, add a conditional block that switches between
#     download and local build based on $build.
#   - CFN parameters are in the "CFN deployment" section. The parameters come
#     from the CDK stack in deployment/migration-assistant-solution/lib/solutions-stack-eks.ts.
#   - Helm values: when using the packaged chart, valuesEks.yaml is extracted
#     from the tgz. When using the local chart, it's referenced directly.
#     To add new values files, update both the extract and the direct path.
# =============================================================================

set -euo pipefail

# --- defaults ---
base_dir=""
namespace="ma"
build=false
use_public_images=true
skip_console_exec=false
helm_upgrade=false
stage_filter=""
extra_helm_values=""
disable_general_purpose_pool=false
use_general_node_pool=false
region="${AWS_CFN_REGION:-}"
deploy_create_vpc=false
deploy_import_vpc=false
cfn_stack_name=""
vpc_id=""
subnet_ids=""
availability_zone_count=3
availability_zone_count_set=false
eks_access_principal_arn=""
grant_eks_access_only=false
skip_cfn_deploy=false
tls_mode="none"
pca_arn=""
version=""
ma_chart_dir=""
create_vpc_endpoints=""
ignore_checks=false
push_images_to_ecr=true
ma_images_source=""
skip_setting_k8s_context=false
with_load_test_images=false
image_tag="latest"
kubectl_context=""
tags_raw=()
enforce_tags_on_create_for_tests=false

# --- argument parsing ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-dir) base_dir="$2"; shift 2 ;;
    --ma-chart-dir) ma_chart_dir="$2"; shift 2 ;;
    --namespace) namespace="$2"; shift 2 ;;
    --build) build=true; shift 1 ;;
    --skip-console-exec) skip_console_exec=true; shift 1 ;;
    # Undocumented on purpose (not in --help): Migration Assistant releases aren't backward compatible yet,
    # so upgrading an existing release in place is unsupported. For developers redeploying their own builds.
    --helm-upgrade) helm_upgrade=true; shift 1 ;;
    --stage) stage_filter="$2"; shift 2 ;;
    --helm-values) extra_helm_values="$2"; shift 2 ;;
    --disable-general-purpose-pool) disable_general_purpose_pool=true; shift 1 ;;
    --use-general-node-pool) use_general_node_pool=true; shift 1 ;;
    --region) region="$2"; shift 2 ;;
    --deploy-create-vpc-cfn) deploy_create_vpc=true; shift 1 ;;
    --deploy-import-vpc-cfn) deploy_import_vpc=true; shift 1 ;;
    --stack-name) cfn_stack_name="$2"; shift 2 ;;
    --vpc-id) vpc_id="$2"; shift 2 ;;
    --subnet-ids) subnet_ids="$2"; shift 2 ;;
    --availability-zone-count) availability_zone_count="$2"; availability_zone_count_set=true; shift 2 ;;
    --eks-access-principal-arn) eks_access_principal_arn="$2"; shift 2 ;;
    --grant-eks-access-only) grant_eks_access_only=true; shift 1 ;;
    --skip-cfn-deploy) skip_cfn_deploy=true; shift 1 ;;
    --version) version="$2"; shift 2 ;;
    --create-vpc-endpoints)
      if [[ "${2:-}" == "" || "${2:-}" == --* ]]; then
        create_vpc_endpoints="all"
        shift 1
      else
        create_vpc_endpoints="$2"
        shift 2
      fi
      ;;
    --ignore-checks) ignore_checks=true; shift 1 ;;
    --use-public-images) push_images_to_ecr=false; shift 1 ;;
    --ma-images-source) ma_images_source="$2"; shift 2 ;;
    --skip-setting-k8s-context) skip_setting_k8s_context=true; shift 1 ;;
    --kubectl-context) kubectl_context="$2"; shift 2 ;;
    # Accepted for older callers; the EKS build never includes test-only images now.
    --skip-test-images) shift 1 ;;
    --with-load-test-images) with_load_test_images=true; shift 1 ;;
    --image-tag) image_tag="$2"; shift 2 ;;
    --tls-mode) tls_mode="$2"; shift 2 ;;
    --pca-arn) pca_arn="$2"; shift 2 ;;
    --tags) tags_raw+=("$2"); shift 2 ;;
    --enforce-tags-on-create-for-tests) enforce_tags_on_create_for_tests=true; shift 1 ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo ""
      echo "Bootstrap an EKS cluster for the OpenSearch Migration Assistant."
      echo "Optionally deploy the Migration Assistant CloudFormation stack first."
      echo ""
      echo "CloudFormation deployment variants (one is required):"
      echo "  --deploy-create-vpc-cfn                   Deploy the Create-VPC EKS CloudFormation template."
      echo "                                            Creates a new VPC with all required networking."
      echo "  --deploy-import-vpc-cfn                   Deploy the Import-VPC EKS CloudFormation template."
      echo "                                            Uses an existing VPC — requires --vpc-id and --subnet-ids."
      echo "  --skip-cfn-deploy                         Skip CloudFormation deployment. Use when the Migration"
      echo "                                            Assistant stack is already deployed and you only want to"
      echo "                                            bootstrap the EKS cluster (install helm chart, images, etc)."
      echo "                                            For a Terraform-provisioned cluster, first load the module's"
      echo "                                            migration_environment output into the shell."
      echo ""
      echo "CloudFormation deployment options:"
      echo "  --stack-name <name>                       CloudFormation stack name (required with --deploy-*-cfn)."
      echo "  --vpc-id <id>                             VPC ID (required with --deploy-import-vpc-cfn)."
      echo "  --subnet-ids <id1,id2>                    Comma-separated subnet IDs, each in a different AZ"
      echo "                                            (required with --deploy-import-vpc-cfn)."
      echo "  --availability-zone-count <2|3>           Number of AZs for a newly created VPC (default: 3)."
      echo "                                            Only valid with --deploy-create-vpc-cfn. EKS requires"
      echo "                                            cluster subnets in at least two Availability Zones."
      echo "  --create-vpc-endpoints [list]             Create VPC endpoints for private subnet connectivity."
      echo "                                            Only valid with --deploy-import-vpc-cfn."
      echo "                                            No argument or 'all' creates: s3,ecr,ecrDocker,cloudwatchLogs,monitoring,efs,sts,eksAuth."
      echo "                                            Or specify a comma-separated subset, e.g. 's3,ecr,ecrDocker'."
      echo "  --tags <Key=Value,Key2=Value2>            Tags to apply to everything this deployment creates."
      echo "                                            Repeatable; values cannot contain commas."
      echo "                                            These become CloudFormation stack tags -- the same thing the"
      echo "                                            console's Tags step sets -- so CloudFormation propagates them"
      echo "                                            to every resource it creates. They are ALSO threaded into the"
      echo "                                            cluster so that the resources EKS Auto Mode creates later"
      echo "                                            (EC2 instances, EBS volumes, load balancers) carry them, which"
      echo "                                            CloudFormation cannot do on its own. See 'Tag propagation'"
      echo "                                            below for what that entails."
      echo "  --enforce-tags-on-create-for-tests        TEST ONLY: additionally DENY the cluster role from creating"
      echo "                                            any covered resource without the --tags keys. Reproduces an"
      echo "                                            SCP that requires tags on create: an untagged create then fails"
      echo "                                            immediately instead of being discovered later. This can stop"
      echo "                                            cluster scaling and is not a production enforcement mechanism."
      echo "                                            Requires --tags and is intended only for integration tests."
      echo "  --ignore-checks                           Skip subnet connectivity and VPC endpoint pre-flight checks."
      echo "  --use-public-images                       Opt out of mirroring images to private ECR. Use public images"
      echo "                                            from public.ecr.aws/opensearchproject and public helm chart"
      echo "                                            registries directly. Requires internet access from the cluster."
      echo "  --ma-images-source <registry>             Copy MA images from another ECR registry instead of"
      echo "                                            public.ecr.aws. Use when images were built on a separate"
      echo "                                            cluster (e.g. one with internet access)."
      echo ""
      echo "EKS access options:"
      echo "  --eks-access-principal-arn <arn>          Grant an IAM principal (role/user) cluster-admin access"
      echo "                                            to the EKS cluster. Useful after a fresh CFN deploy when"
      echo "                                            the deploying principal needs kubectl access, or to grant"
      echo "                                            access to a CI role or teammate."
      echo "  --grant-eks-access-only                   Add the --eks-access-principal-arn access entry to an"
      echo "                                            ALREADY-bootstrapped cluster and exit -- no image mirroring,"
      echo "                                            no Helm install. Use this to add an admin later WITHOUT"
      echo "                                            re-running the whole bootstrap. Requires"
      echo "                                            --eks-access-principal-arn. The cluster must already exist;"
      echo "                                            it is resolved from CloudFormation exports (respects --stage)."
      echo ""
      echo "Deployment options:"
      echo "  --namespace <val>                         K8s namespace (default: $namespace)"
      echo "  --helm-values <path>                      Extra values file for helm install"
      echo "  --disable-general-purpose-pool            Disable EKS Auto Mode general-purpose pool, which will"
      echo "                                            require another nodepool to be configured (such as with"
      echo "                                            'cluster.useCustomKarpenterNodePool: true' in the file"
      echo "                                            passed to --helm-values)"
      echo "  --use-general-node-pool                   Use the EKS Auto Mode general-purpose pool instead of"
      echo "                                            the custom Karpenter NodePool. Useful for CI/test"
      echo "                                            environments that don't need production-safe settings."
      echo "  --stage <val>                             Stage name for CFN exports filter and CFN Stage parameter (default: dev)"
      echo "  --region <val>                            AWS region"
      echo "  --skip-console-exec                       Don't exec into console pod (default: $skip_console_exec)"
      echo "  --skip-setting-k8s-context                Don't set the kubectl current-context to the EKS cluster."
      echo "                                            The kubeconfig entry is still created, but the active context"
      echo "                                            is left unchanged. Use this on hosts that manage multiple K8s"
      echo "                                            deployments. You will need to pass --context=<context-name>"
      echo "                                            to every kubectl/helm command, or set the context yourself."
      echo "  --kubectl-context <name>                  Custom alias for the kubectl context (default: EKS cluster name)."
      echo "                                            Useful for CI systems that need a predictable context name."
      echo "  --with-load-test-images                   Also build the load-test runner and patched operator images."
      echo "                                            They are not part of the standard image set. Only the k6"
      echo "                                            load-test cases (Test008x) need them. Requires --build."
      echo "  --image-tag <tag>                         Override the image tag (default: git short SHA)"
      echo ""
      echo "Build options:"
      echo "  --build                                   Build ALL artifacts from source: images, CFN templates,"
      echo "                                            and Helm chart + dashboards. Mutually exclusive with --version."
      echo "                                            When combined with --ma-images-source, images are copied from"
      echo "                                            another ECR registry instead of being built locally."
      echo "                                            Requires a repo checkout (see --base-dir)."
      echo "  --base-dir <path>                         opensearch-migrations directory"
      echo "                                            (default: ../../.. from the script location)"
      echo "  --version <tag>                           Use published release artifacts for the given version."
      echo "                                            Defaults to latest GitHub release when not specified."
      echo "                                            Mutually exclusive with --build."
      echo ""
      echo "TLS / Certificate options:"
      echo "  --tls-mode <mode>                         TLS certificate strategy for the capture proxy."
      echo "                                            none         - No TLS (default)"
      echo "                                            self-signed  - Use cert-manager self-signed issuer"
      echo "                                            pca-existing - Use an existing AWS Private CA"
      echo "                                            pca-create   - Create a new AWS Private CA via ACK"
      echo "  --pca-arn <arn>                           ARN of existing AWS Private CA"
      echo "                                            (required with --tls-mode pca-existing)"
      echo ""
      echo "Tag propagation (--tags):"
      echo "  CloudFormation stack tags only reach resources CloudFormation itself creates. Nodes,"
      echo "  volumes and load balancers are created later, at runtime, by EKS Auto Mode -- so"
      echo "  --tags additionally:"
      echo "    * creates a custom EKS Auto Mode NodeClass carrying the tags, because the built-in"
      echo "      'default' NodeClass is owned by EKS and cannot be edited;"
      echo "    * disables the built-in 'system' and 'general-purpose' NodePools, whose nodes always"
      echo "      use the untaggable 'default' NodeClass, and replaces them with a NodePool bound to"
      echo "      the custom NodeClass;"
      echo "    * sets tagSpecification parameters on the StorageClass so provisioned EBS volumes"
      echo "      are tagged, and annotates any load balancer the chart creates."
      echo "  This requires the cluster IAM role to permit user-defined tags on Auto Mode resources."
      echo "  --tags ensures the required inline policy even for older, adopted, or hand-built clusters;"
      echo "  the caller therefore needs iam:PutRolePolicy on the cluster role."
      echo ""
      echo "Examples:"
      echo "  # Mode 1 — Default: download latest release artifacts, create new VPC:"
      echo "  $0 --deploy-create-vpc-cfn --stack-name MA-Dev --stage dev --region us-east-1"
      echo ""
      echo "  # Mode 2 — Build everything from source:"
      echo "  $0 --deploy-create-vpc-cfn --stack-name MA-Dev --stage dev --region us-east-1 --build"
      echo ""
      echo "  # Mode 3 — Pin a specific release version:"
      echo "  $0 --deploy-create-vpc-cfn --stack-name MA-Dev --stage dev --region us-east-1 --version 2.9.0"
      echo ""
      echo "  # Deploy into an existing VPC:"
      echo "  $0 --deploy-import-vpc-cfn --stack-name MA-Dev --stage dev \\"
      echo "     --vpc-id vpc-0abc123 --subnet-ids subnet-111,subnet-222 --region us-east-1"
      echo ""
      echo "  # Bootstrap only (CloudFormation stack already deployed):"
      echo "  $0 --skip-cfn-deploy --stage dev --region us-east-1"
      echo ""
      echo "  # Bootstrap a cluster provisioned by deployment/terraform/aws:"
      echo "  eval \"\$(terraform -chdir=deployment/terraform/aws output -raw migration_environment)\""
      echo "  $0 --skip-cfn-deploy --stage dev --region us-east-1"
      echo ""
      echo "  # Add an admin to an existing cluster WITHOUT re-running the whole bootstrap:"
      echo "  $0 --grant-eks-access-only --stage dev --region us-east-1 \\"
      echo "     --eks-access-principal-arn arn:aws:iam::123456789012:role/AnotherAdmin"
      echo ""
      echo "  # Tag every created resource, including the nodes and volumes EKS creates later:"
      echo "  $0 --deploy-create-vpc-cfn --stack-name MA-Dev --stage dev --region us-east-1 \\"
      echo "     --tags CostCenter=1234,Owner=platform-team"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

if [[ "$disable_general_purpose_pool" == "true" && "$use_general_node_pool" == "true" ]]; then
  echo "ERROR: --disable-general-purpose-pool and --use-general-node-pool are mutually exclusive"
  exit 1
fi

if [[ "$build" == "true" ]]; then
  use_public_images=false
fi

# --ma-images-source implies mirroring to private ECR
if [[ -n "$ma_images_source" ]]; then
  push_images_to_ecr=true
fi

# --- parse --tags into parallel key/value arrays ---
# One flat pass so every consumer (CloudFormation stack tags, the Auto Mode NodeClass, the
# StorageClass, load balancer annotations) works from the same normalized set. Entries are split
# on commas and newlines, which is what makes a comma illegal inside a tag value.
tag_keys=()
tag_values=()

# Tag keys that EKS, Karpenter and AWS itself own. Passing one of these through would either be
# silently dropped or rejected at NodeClass admission, so fail loudly instead of half-applying.
RESERVED_TAG_PREFIXES=('aws:' 'eks:' 'eks.amazonaws.com/' 'karpenter.sh/' 'kubernetes.io/cluster/')

trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  printf '%s' "${s%"${s##*[![:space:]]}"}"
}

validate_and_add_tag() {
  local key="$1"
  local value="$2"
  local source="$3"
  local prefix
  if [[ -z "$key" ]]; then
    echo "Error: ${source} has an empty key." >&2
    exit 1
  fi
  for prefix in "${RESERVED_TAG_PREFIXES[@]}"; do
    if [[ "$key" == "$prefix"* ]]; then
      echo "Error: ${source} key '$key' uses the reserved prefix '$prefix'." >&2
      echo "  These prefixes are owned by AWS, EKS or Karpenter and cannot be set by a deployer." >&2
      exit 1
    fi
  done
  tag_keys+=("$key")
  tag_values+=("$value")
}

parse_tags() {
  local spec entry key value
  # `set -u` makes an unquoted expansion of an empty array an error on bash 3.2 (macOS), so
  # bail out before touching it rather than relying on `${arr[@]+...}` gymnastics.
  [[ ${#tags_raw[@]} -eq 0 ]] && return 0
  for spec in "${tags_raw[@]}"; do
    while IFS= read -r entry; do
      entry="$(trim "$entry")"
      [[ -z "$entry" ]] && continue
      if [[ "$entry" != *=* ]]; then
        echo "Error: --tags entry '$entry' is not in Key=Value form." >&2
        exit 1
      fi
      key="$(trim "${entry%%=*}")"
      value="$(trim "${entry#*=}")"
      validate_and_add_tag "$key" "$value" "--tags entry '$entry'"
      # Trailing newline matters: without it `read` returns non-zero on the final entry and the
      # loop body is skipped, silently dropping the last tag.
    done < <(printf '%s\n' "$spec" | tr ',' '\n')
  done
}

parse_tags

if [[ ${#tag_keys[@]} -gt 0 ]]; then
  # --use-general-node-pool points the chart's workloads at the built-in general-purpose NodePool,
  # which --tags has to delete: its nodes come from the EKS-owned "default" NodeClass and cannot
  # carry tags. Honouring both would schedule onto a NodePool that no longer exists.
  if [[ "$use_general_node_pool" == "true" ]]; then
    echo "ERROR: --tags and --use-general-node-pool are mutually exclusive." >&2
    # (--enforce-tags-on-create-for-tests is validated below; it needs tags to require.)
    echo "  --tags replaces the built-in general-purpose NodePool with a tagged one, because the" >&2
    echo "  NodeClass behind the built-in pools is owned by EKS and cannot carry user tags." >&2
    exit 1
  fi
  # Already implied -- and stronger, since --tags disables the system pool too.
  if [[ "$disable_general_purpose_pool" == "true" ]]; then
    echo "Note: --disable-general-purpose-pool is redundant with --tags; --tags disables both"
    echo "      built-in NodePools (system and general-purpose) for the life of the cluster."
    disable_general_purpose_pool=false
  fi
fi

# There is nothing to require without tags, and silently doing nothing would make a test that relies
# on enforcement pass for the wrong reason.
if [[ "$enforce_tags_on_create_for_tests" == "true" && ${#tag_keys[@]} -eq 0 ]]; then
  echo "ERROR: --enforce-tags-on-create-for-tests requires --tags: there would be no tag to require." >&2
  exit 1
fi

# --- derive state from parsed arguments ---
deploy_cfn=false
if [[ "$deploy_create_vpc" == "true" || "$deploy_import_vpc" == "true" ]]; then
  deploy_cfn=true
fi
if [[ "$deploy_cfn" == "true" && -z "$stage_filter" ]]; then
  stage_filter="dev"
  echo "No --stage specified, defaulting to 'dev' for CFN Stage parameter."
fi

# --- resolve base_dir ---
if [[ -z "$base_dir" ]]; then
  # Script lives at deployment/k8s/aws/aws-bootstrap.sh — repo root is three levels up
  base_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
fi

# --- validation (reads globals, exits on error, mutates nothing) ---
validate_args() {
  if [[ "$deploy_create_vpc" == "true" && "$deploy_import_vpc" == "true" ]]; then
    echo "Error: --deploy-create-vpc-cfn and --deploy-import-vpc-cfn are mutually exclusive." >&2
    exit 1
  fi
  # --grant-eks-access-only only adds an EKS access entry to an existing cluster, so it must not be
  # combined with a deploy/skip-deploy/build, and it needs a principal to grant.
  if [[ "$grant_eks_access_only" == "true" ]]; then
    if [[ "$deploy_cfn" == "true" || "$skip_cfn_deploy" == "true" || "$build" == "true" ]]; then
      echo "Error: --grant-eks-access-only cannot be combined with --deploy-*-cfn, --skip-cfn-deploy, or --build." >&2
      echo "  It only adds an EKS access entry to an already-bootstrapped cluster, then exits." >&2
      exit 1
    fi
    if [[ -z "$eks_access_principal_arn" ]]; then
      echo "Error: --grant-eks-access-only requires --eks-access-principal-arn <arn>." >&2
      exit 1
    fi
  fi
  if [[ "$deploy_cfn" == "false" && "$skip_cfn_deploy" == "false" && "$grant_eks_access_only" == "false" ]]; then
    echo "Error: One of --deploy-create-vpc-cfn, --deploy-import-vpc-cfn, --skip-cfn-deploy, or --grant-eks-access-only is required." >&2
    echo "  Use --deploy-create-vpc-cfn to create a new VPC and EKS cluster." >&2
    echo "  Use --deploy-import-vpc-cfn to deploy into an existing VPC." >&2
    echo "  Use --skip-cfn-deploy if the stack is already deployed." >&2
    echo "  Use --grant-eks-access-only to add EKS access to an existing cluster without re-bootstrapping." >&2
    exit 1
  fi
  if [[ "$deploy_cfn" == "true" && "$skip_cfn_deploy" == "true" ]]; then
    echo "Error: --skip-cfn-deploy cannot be combined with --deploy-create-vpc-cfn or --deploy-import-vpc-cfn." >&2
    exit 1
  fi
  if [[ "$build" == "true" && "$deploy_cfn" == "false" && "$skip_cfn_deploy" == "false" ]]; then
    echo "Error: --build requires --deploy-create-vpc-cfn, --deploy-import-vpc-cfn, or --skip-cfn-deploy." >&2
    exit 1
  fi
  if [[ "$deploy_cfn" == "true" && -z "$cfn_stack_name" ]]; then
    echo "Error: --stack-name is required with --deploy-create-vpc-cfn or --deploy-import-vpc-cfn." >&2
    exit 1
  fi
  if [[ "$deploy_import_vpc" == "true" ]]; then
    if [[ -z "$vpc_id" ]]; then
      echo "Error: --vpc-id is required with --deploy-import-vpc-cfn." >&2
      echo "" >&2
      echo "Available VPCs${region:+ in $region}:" >&2
      aws ec2 describe-vpcs ${region:+--region "$region"} \
        --query 'Vpcs[].{ID:VpcId,Name:Tags[?Key==`Name`].Value|[0],CIDR:CidrBlock,State:State}' \
        --output table >&2 || true
      echo "" >&2
      echo "Re-run with: --vpc-id <vpc-id> --subnet-ids <subnet1,subnet2>" >&2
      exit 1
    fi
    if [[ -z "$subnet_ids" ]]; then
      echo "Error: --subnet-ids is required with --deploy-import-vpc-cfn." >&2
      echo "" >&2
      echo "Available subnets in VPC $vpc_id${region:+ ($region)}:" >&2
      echo "  (checking route tables for internet access...)" >&2
      aws ec2 describe-subnets ${region:+--region "$region"} \
        --filters "Name=vpc-id,Values=$vpc_id" \
        --query 'Subnets[].SubnetId' --output text | tr '\t' '\n' | while read -r sid; do
        has_nat=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
          --filters "Name=association.subnet-id,Values=$sid" \
          --query 'RouteTables[0].Routes[?NatGatewayId!=null] | length(@)' --output text 2>/dev/null || echo 0)
        has_igw=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
          --filters "Name=association.subnet-id,Values=$sid" \
          --query 'RouteTables[0].Routes[?GatewayId!=null && starts_with(GatewayId, `igw-`)] | length(@)' --output text 2>/dev/null || echo 0)
        # Fall back to main route table if no explicit association
        if [[ "${has_nat:-0}" -eq 0 && "${has_igw:-0}" -eq 0 ]]; then
          has_nat=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
            --filters "Name=vpc-id,Values=$vpc_id" "Name=association.main,Values=true" \
            --query 'RouteTables[0].Routes[?NatGatewayId!=null] | length(@)' --output text 2>/dev/null || echo 0)
          has_igw=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
            --filters "Name=vpc-id,Values=$vpc_id" "Name=association.main,Values=true" \
            --query 'RouteTables[0].Routes[?GatewayId!=null && starts_with(GatewayId, `igw-`)] | length(@)' --output text 2>/dev/null || echo 0)
        fi
        info=$(aws ec2 describe-subnets ${region:+--region "$region"} --subnet-ids "$sid" \
          --query 'Subnets[0].[SubnetId,AvailabilityZone,CidrBlock]' --output text 2>/dev/null)
        route="no-internet"
        [[ "${has_nat:-0}" -gt 0 ]] && route="NAT"
        [[ "${has_igw:-0}" -gt 0 ]] && route="IGW (public)"
        echo "  $info  $route"
      done >&2
      echo "" >&2
      echo "Select subnets with NAT or IGW routes (pods need internet access to pull images)." >&2
      echo "Re-run with: --subnet-ids <subnet1,subnet2>" >&2
      exit 1
    fi
  fi
  if [[ "$deploy_import_vpc" == "false" ]]; then
    if [[ -n "$vpc_id" ]]; then
      echo "Error: --vpc-id is only valid with --deploy-import-vpc-cfn." >&2
      exit 1
    fi
    if [[ -n "$subnet_ids" ]]; then
      echo "Error: --subnet-ids is only valid with --deploy-import-vpc-cfn." >&2
      exit 1
    fi
  fi
  case "$availability_zone_count" in
    2|3) ;;
    *)
      echo "Error: --availability-zone-count must be 2 or 3; EKS requires at least two Availability Zones." >&2
      exit 1
      ;;
  esac
  if [[ "$availability_zone_count_set" == "true" && "$deploy_create_vpc" != "true" ]]; then
    echo "Error: --availability-zone-count is only valid with --deploy-create-vpc-cfn." >&2
    exit 1
  fi
  # Validate repo checkout exists when --build is specified
  if [[ "$build" == "true" ]]; then
    if [[ ! -f "$base_dir/gradlew" ]]; then
      echo "Error: --build requires a repo checkout." >&2
      echo "  Expected repo root at: $base_dir" >&2
      echo "  Use --base-dir to specify the repo location, or run this script from within the repo." >&2
      exit 1
    fi
  fi
  # --build and --version are mutually exclusive
  if [[ "$build" == "true" && -n "$version" ]]; then
    echo "Error: --build and --version are mutually exclusive." >&2
    echo "  --build builds everything from source (no version needed)." >&2
    echo "  --version downloads published artifacts for a specific release." >&2
    exit 1
  fi
  # Load-test images are built from source only. No release publishes them, and
  # --ma-images-source has no entry for them in MA_IMAGES.
  if [[ "$with_load_test_images" == "true" ]]; then
    if [[ "$build" != "true" ]]; then
      echo "Error: --with-load-test-images requires --build." >&2
      echo "  The load-test images (migrations/k6_runner) are not published in a release." >&2
      exit 1
    fi
    if [[ -n "$ma_images_source" ]]; then
      echo "Error: --with-load-test-images cannot be combined with --ma-images-source." >&2
      echo "  Mirrored runs copy a fixed image set that does not include the load-test images." >&2
      exit 1
    fi
  fi
  if [[ -n "$create_vpc_endpoints" && "$deploy_import_vpc" != "true" ]]; then
    echo "Error: --create-vpc-endpoints is only valid with --deploy-import-vpc-cfn." >&2
    exit 1
  fi
  # Validate TLS mode
  case "$tls_mode" in
    none|self-signed) ;;
    pca-existing)
      if [[ -z "$pca_arn" ]]; then
        echo "Error: --pca-arn is required with --tls-mode pca-existing." >&2
        exit 1
      fi ;;
    pca-create) ;;
    *)
      echo "Error: Unknown --tls-mode: $tls_mode (expected: none, self-signed, pca-existing, pca-create)" >&2
      exit 1 ;;
  esac

  # Early-resolve region and fail fast if unresolvable. Without this, a missing
  # region only surfaces as "You must specify a region" from the AWS CLI deep
  # into the run (e.g. after a multi-minute CDK build with --build). We check
  # --region, then $AWS_CFN_REGION, then `aws configure get region` — mirroring
  # the fallback used later at deploy time — so the error happens in seconds,
  # not minutes.
  if [[ -z "$region" ]]; then
    region="$(aws configure get region 2>/dev/null || true)"
  fi
  if [[ -z "$region" ]]; then
    echo "Error: AWS region is not set." >&2
    echo "  Pass --region <region>, set AWS_CFN_REGION, or run 'aws configure'." >&2
    exit 1
  fi

  # A mistyped --helm-values path would otherwise only surface at the cluster check after CFN,
  # 15+ minutes into a first deploy.
  if [[ -n "$extra_helm_values" ]]; then
    local f
    local -a files
    IFS=',' read -ra files <<< "$extra_helm_values"
    for f in "${files[@]}"; do
      if [[ ! -f "$f" || ! -r "$f" ]]; then
        echo "Error: --helm-values file '$f' does not exist or is not readable." >&2
        exit 1
      fi
    done
  fi
}

validate_args

# --- early subnet isolation check (before any slow operations) ---
if [[ "$deploy_import_vpc" == "true" && -n "$subnet_ids" && "$ignore_checks" != "true" ]]; then
  echo "Checking subnet connectivity..."
  has_internet=false
  IFS=',' read -ra _early_subnets <<< "$subnet_ids"
  for sid in "${_early_subnets[@]}"; do
    for rt_filter in "Name=association.subnet-id,Values=$sid" "Name=vpc-id,Values=$vpc_id Name=association.main,Values=true"; do
      n=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
        --filters $rt_filter \
        --query 'RouteTables[0].Routes[?NatGatewayId!=null || (GatewayId!=null && starts_with(GatewayId, `igw-`))]' \
        --output json 2>/dev/null | grep -c '"NatGatewayId"\|"GatewayId"' || true)
      [[ "${n:-0}" -gt 0 ]] && { has_internet=true; break; }
    done
    [[ "$has_internet" == "true" ]] && break
  done
  if [[ "$has_internet" == "false" ]]; then
    echo "  Subnets are isolated (no NAT/IGW routes)."
    if [[ "$build" == "true" && -z "$ma_images_source" ]]; then
      echo "" >&2
      echo "Error: --build cannot be used on isolated subnets (no NAT/IGW) without --ma-images-source." >&2
      echo "  Buildkit and Dockerfile builds require internet access for base images." >&2
      echo "  Build images on a cluster with internet access, then use --ma-images-source" >&2
      echo "  to copy them to the isolated deployment." >&2
      exit 1
    fi
    if [[ "$push_images_to_ecr" != "true" ]]; then
      echo "" >&2
      echo "Error: --use-public-images cannot be used with isolated subnets (no NAT/IGW)." >&2
      echo "  Images cannot be pulled from public registries (docker.io, quay.io, etc)." >&2
      echo "  Remove --use-public-images to mirror public images to private ECR." >&2
      echo "  Use --ma-images-source to copy MA images from a build cluster's ECR." >&2
      echo "  Use --ignore-checks to skip this check." >&2
      exit 1
    fi
    echo "  Subnets are isolated — images will be mirrored to private ECR. ✅"
  else
    echo "  Subnets have internet access. ✅"
  fi
fi

# --- expand --create-vpc-endpoints value ---
if [[ "$create_vpc_endpoints" == "all" ]]; then
  create_vpc_endpoints="s3,ecr,ecrDocker,cloudwatchLogs,monitoring,efs,sts,eksAuth"
fi

# --- resolve version once ---
# Grant-only mode never touches release artifacts, so avoid an unnecessary GitHub lookup.
if [[ "$grant_eks_access_only" == "true" ]]; then
  BOOTSTRAP_ARTIFACT_VERSION="grant-access-only"
  echo "Grant-only mode: skipping release version resolution."
# Skip version resolution when building everything from source (--build)
elif [[ "$build" == "true" ]]; then
  BOOTSTRAP_ARTIFACT_VERSION="local-build"
  echo "Building all artifacts from source (no release version needed)"
elif [[ -z "$version" || "$version" == "latest" ]]; then
  BOOTSTRAP_ARTIFACT_VERSION=$(curl -sf https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
  BOOTSTRAP_ARTIFACT_VERSION=$(echo "$BOOTSTRAP_ARTIFACT_VERSION" | tr -d '[:space:]')
  [[ -n "$BOOTSTRAP_ARTIFACT_VERSION" ]] || { echo "Error: Could not determine latest release version from GitHub."; exit 1; }
  echo "Resolved latest release version: $BOOTSTRAP_ARTIFACT_VERSION"
else
  BOOTSTRAP_ARTIFACT_VERSION="$version"
  echo "Using specified version: $BOOTSTRAP_ARTIFACT_VERSION"
fi

TOOLS_ARCH=$(uname -m)

# --- compute image tag ---
# Default to "latest" for simple dev workflows (build, delete pod, done).
# CI passes --image-tag with a git SHA for immutable, reproducible deploys.
IMAGE_TAG="$image_tag"
echo "Image tag: $IMAGE_TAG"
case "$TOOLS_ARCH" in
  x86_64 | amd64) TOOLS_ARCH="amd64" ;;
  aarch64 | arm64) TOOLS_ARCH="arm64" ;;
  *) echo "Unsupported architecture: $TOOLS_ARCH"; exit 1 ;;
esac
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
HELM_VERSION="3.14.0"

default_ma_chart_dir="${base_dir}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo"

resolve_chart_source() {
  if [[ "$build" == "true" ]]; then
    if [[ -z "$ma_chart_dir" ]]; then
      ma_chart_dir="$default_ma_chart_dir"
    fi
    return
  fi

  if [[ -n "$ma_chart_dir" && ( -f "$ma_chart_dir" || -d "$ma_chart_dir" ) ]]; then
    return
  fi

  local release_base_url
  release_base_url="https://github.com/opensearch-project/opensearch-migrations/releases/download/${BOOTSTRAP_ARTIFACT_VERSION}"
  echo "Downloading release Helm chart (${BOOTSTRAP_ARTIFACT_VERSION}) from GitHub..." >&2
  curl -fLO "${release_base_url}/migration-assistant-${BOOTSTRAP_ARTIFACT_VERSION}.tgz" \
    || { echo "Failed to download Helm chart for version ${BOOTSTRAP_ARTIFACT_VERSION}"; exit 1; }
  ma_chart_dir="./migration-assistant-${BOOTSTRAP_ARTIFACT_VERSION}.tgz"
}

resolve_mirror_manifest_file() {
  resolve_chart_source

  if [[ -d "$ma_chart_dir" ]]; then
    echo "${ma_chart_dir}/infra/mirror/private-ecr-manifest.yaml"
    return
  fi

  local manifest_tmp_dir
  manifest_tmp_dir=$(mktemp -d)
  tar xzf "$ma_chart_dir" -C "$manifest_tmp_dir" migration-assistant/infra/mirror/private-ecr-manifest.yaml
  echo "${manifest_tmp_dir}/migration-assistant/infra/mirror/private-ecr-manifest.yaml"
}

install_helm() {
  echo "Installing Helm ${HELM_VERSION} for ${OS}/${TOOLS_ARCH}..."

  tmp_dir=$(mktemp -d)
  cd "$tmp_dir" || exit 1

  curl -fLO "https://get.helm.sh/helm-v${HELM_VERSION}-${OS}-${TOOLS_ARCH}.tar.gz"
  tar -zxvf "helm-v${HELM_VERSION}-${OS}-${TOOLS_ARCH}.tar.gz"
  sudo mv "${OS}-${TOOLS_ARCH}/helm" /usr/local/bin/helm
  cd - >/dev/null || exit 1
  rm -rf "$tmp_dir"

  echo "Helm installed successfully."
}

get_cfn_export() {
  prefix="MigrationsExportString"
  names=()
  values=()

  # Example CFN stack output value will look like: export MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-dev-us-east-2;
  # export MIGRATIONS_ECR_REGISTRY=123456789012.dkr.ecr.us-east-2.amazonaws.com/migration-ecr-dev-us-east-2;...
  while read -r name value; do
    # If stage_filter is set, only include exports that contain the stage name
    if [[ -n "$stage_filter" && "$name" != *"-${stage_filter}-"* ]]; then
      continue
    fi
    names+=("$name")
    values+=("$value")
  done < <(aws cloudformation list-exports \
    ${region:+--region "$region"} \
    --query "Exports[?starts_with(Name, \`${prefix}\`)].[Name,Value]" \
    --output text)

  if [ ${#names[@]} -eq 0 ]; then
    echo "Error: No exports found starting with '$prefix'${stage_filter:+ matching stage '$stage_filter'}" >&2
    return 1
  elif [ ${#names[@]} -eq 1 ]; then
    echo "${values[0]}"
  else
    echo "Multiple Cloudformation stacks with migration exports found:" >&2
    for i in "${!names[@]}"; do
      echo "  [$i] ${names[$i]}" >&2
    done
    echo >&2
    echo "Please re-run with the --stage flag to select the correct stack." >&2
    echo "For example:" >&2
    echo "  $0 --stage <stage-name>" >&2
    return 1
  fi
}

# Dump diagnostics when the Migration Assistant helm install fails. The install fails via
# a hook Job (pre-install ma-dependency-installer, post-install create-s3-bucket-ma, etc.)
# whose pod holds the only record of why it failed -- and the observability stack
# (fluent-bit, migration-console) may not exist on a hook failure, so grab it directly.
dump_helm_debug_info() {
  local release_namespace="$1"
  local kctx=()
  [[ -n "${KUBE_CONTEXT:-}" ]] && kctx=("--context=${KUBE_CONTEXT}")

  echo "=== BEGIN HELM INSTALL DEBUG INFO ===" >&2
  kubectl "${kctx[@]}" get pods -n "$release_namespace" -o wide >&2 2>&1 || true
  echo "--- Jobs in $release_namespace ---" >&2
  kubectl "${kctx[@]}" get jobs -n "$release_namespace" -o wide >&2 2>&1 || true
  echo "--- Recent events ---" >&2
  kubectl "${kctx[@]}" get events -n "$release_namespace" --sort-by=.lastTimestamp >&2 2>&1 || true

  # Logs + describe for every non-succeeded pod in the namespace (the failed hook pod is here).
  local pods
  pods=$(kubectl "${kctx[@]}" get pods -n "$release_namespace" \
    --field-selector=status.phase!=Running,status.phase!=Succeeded \
    -o jsonpath='{.items[*].metadata.name}' 2>/dev/null) || true
  for p in $pods; do
    echo "--- Logs for $p (--tail=200, all containers) ---" >&2
    kubectl "${kctx[@]}" logs -n "$release_namespace" "$p" --all-containers --tail=200 >&2 2>&1 || true
    echo "--- Describe $p ---" >&2
    kubectl "${kctx[@]}" describe pod "$p" -n "$release_namespace" >&2 2>&1 || true
  done
  echo "=== END HELM INSTALL DEBUG INFO ===" >&2
}

check_existing_ma_release() {
  local release_name="$1"
  local release_namespace="$2"
  local helm_ctx=()
  [[ -n "${KUBE_CONTEXT:-}" ]] && helm_ctx=("--kube-context=${KUBE_CONTEXT}")

  if helm "${helm_ctx[@]}" status "$release_name" -n "$release_namespace" >/dev/null 2>&1; then
    echo
    echo "A Migration Assistant Helm release named '$release_name' already exists in namespace '$release_namespace'."
    echo "This usually means Migration Assistant is already installed on this cluster."
    echo
    echo "To reinstall, first uninstall the existing release with:"
    echo "  helm uninstall $release_name -n $release_namespace"
    echo
    echo "Then re-run this bootstrap script."
    exit 1
  fi
}

# Check required tools. Grant-only mode only talks to AWS; it never downloads artifacts, installs
# Helm, or touches kubectl.
missing=0
if [[ "$grant_eks_access_only" != "true" ]]; then
  for cmd in jq kubectl; do
    if ! command -v $cmd &>/dev/null; then
      echo "Missing required tool: $cmd"
      missing=1
    fi
  done
fi

# Install helm if missing
if [[ "$grant_eks_access_only" != "true" ]] && ! command -v helm &>/dev/null; then
  echo "Helm is not installed. Installing it now..."
  install_helm
fi

# Exit if any tool was missing and not resolved
[ "$missing" -ne 0 ] && exit 1

# The deployer's own --helm-values files. extra_helm_values also collects files the script
# generates later (tags, private ECR), so keep this copy for the cluster check after CFN.
user_helm_values="$extra_helm_values"

# --- source helper scripts (inlined by assemble-bootstrap.sh for release) ---
# @source deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcrUtils.sh
# @source deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcr.sh
# @source deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/generatePrivateEcrValues.sh
# @source deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/bootstrapMirror.sh
_bootstrap_source_helpers() {
  local scripts_dir="${base_dir}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts"
  # shellcheck source=../../../charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcrUtils.sh
  . "$scripts_dir/mirrorToEcrUtils.sh"
  # shellcheck source=../../../charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcr.sh
  . "$scripts_dir/mirrorToEcr.sh"
  # shellcheck source=../../../charts/aggregates/migrationAssistantWithArgo/scripts/generatePrivateEcrValues.sh
  . "$scripts_dir/generatePrivateEcrValues.sh"
  # shellcheck source=../../../charts/aggregates/migrationAssistantWithArgo/scripts/bootstrapMirror.sh
  . "$scripts_dir/bootstrapMirror.sh"
}
# Only source if functions aren't already defined (i.e., not assembled).
if ! type mirror_bootstrap_artifacts &>/dev/null; then
  _bootstrap_source_helpers
fi

mirror_pid=""
mirror_registry_file=""
ecr_values_file=""
mirror_inputs_prepared=false
include_ma_images=false

cleanup_bootstrap_mirroring() {
  [[ "${BASH_SUBSHELL:-0}" -eq 0 ]] || return
  if [[ -n "$mirror_pid" ]] && kill -0 "$mirror_pid" 2>/dev/null; then
    echo "Stopping in-flight ECR mirroring..." >&2
    kill "$mirror_pid" 2>/dev/null || true
    wait "$mirror_pid" 2>/dev/null || true
  fi
  [[ -z "$mirror_registry_file" ]] || rm -f "$mirror_registry_file"
  [[ -z "$ecr_values_file" ]] || rm -f "$ecr_values_file"
}
trap cleanup_bootstrap_mirroring EXIT

prepare_bootstrap_mirroring() {
  [[ "$mirror_inputs_prepared" == "true" ]] && return

  echo "Preparing private ECR mirror inputs..."
  mirror_manifest_file=$(resolve_mirror_manifest_file)
  echo "Using ECR mirror manifest: ${mirror_manifest_file}"
  load_private_ecr_manifest "$mirror_manifest_file"

  include_ma_images=true
  if [[ "$build" == "true" && -z "$ma_images_source" ]]; then
    include_ma_images=false
  fi

  mirror_registry_file=$(mktemp)
  ecr_values_file=$(mktemp)
  if [[ -n "$extra_helm_values" ]]; then
    extra_helm_values="$extra_helm_values,$ecr_values_file"
  else
    extra_helm_values="$ecr_values_file"
  fi
  mirror_inputs_prepared=true
}

start_bootstrap_mirroring() {
  local registry_source="$1" registry_value="$2"
  [[ -n "$mirror_pid" ]] && return
  prepare_bootstrap_mirroring

  (
    local registry_uri mirror_child_pid="" mirror_rc=0
    stop_mirror_child() {
      if [[ -n "$mirror_child_pid" ]] && kill -0 "$mirror_child_pid" 2>/dev/null; then
        kill "$mirror_child_pid" 2>/dev/null || true
        wait "$mirror_child_pid" 2>/dev/null || true
      fi
    }
    trap 'stop_mirror_child; exit 129' HUP
    trap 'stop_mirror_child; exit 130' INT
    trap 'stop_mirror_child; exit 143' TERM
    trap 'stop_mirror_child' EXIT

    if [[ "$registry_source" == "repository" ]]; then
      registry_uri=$(wait_for_ecr_repository_uri "$region" "$registry_value")
    else
      registry_uri="$registry_value"
    fi
    printf '%s\n' "$registry_uri" > "$mirror_registry_file"
    echo "Starting private ECR mirroring against $registry_uri..."
    mirror_bootstrap_artifacts "$registry_uri" "$region" "$IMAGES" "$CHARTS" \
      "$include_ma_images" "$BOOTSTRAP_ARTIFACT_VERSION" "$IMAGE_TAG" \
      "$ma_images_source" "$ecr_values_file" &
    mirror_child_pid=$!
    wait "$mirror_child_pid" || mirror_rc=$?
    mirror_child_pid=""
    trap - EXIT HUP INT TERM
    exit "$mirror_rc"
  ) &
  mirror_pid=$!
}

wait_for_bootstrap_mirroring() {
  local mirrored_registry
  [[ -n "$mirror_pid" ]] || return

  echo "Waiting for private ECR mirroring to finish..."
  if ! wait "$mirror_pid"; then
    mirror_pid=""
    echo "Private ECR mirroring failed." >&2
    exit 1
  fi
  mirror_pid=""

  mirrored_registry=$(cat "$mirror_registry_file")
  if [[ "$mirrored_registry" != "$MIGRATIONS_ECR_REGISTRY" ]]; then
    echo "Error: Mirrored into '$mirrored_registry', but CloudFormation exported" >&2
    echo "       '$MIGRATIONS_ECR_REGISTRY'." >&2
    exit 1
  fi
  echo "Private ECR mirroring completed successfully."
}

if [[ "$push_images_to_ecr" == "true" && "$grant_eks_access_only" != "true" ]]; then
  prepare_bootstrap_mirroring
fi

# --- CFN deployment (optional) ---
if [[ "$deploy_cfn" == "true" ]]; then
  # Determine template source
  if [[ "$build" == "true" ]]; then
    echo "Building CloudFormation templates from source..."
    # Clear STACK_NAME_SUFFIX so CDK produces predictable template filenames.
    # The stack name is controlled by --stack-name, not by CDK stack IDs.
    # Other CDK env vars (CODE_BUCKET, SOLUTION_NAME, CODE_VERSION) are left
    # intact — they affect template content (such as S3 paths) and
    # the CDK has safe defaults when they're unset.
    STACK_NAME_SUFFIX="" \
      "$base_dir/gradlew" -p "$base_dir" :deployment:migration-assistant-solution:cdkSynthMinified -x test
    if [[ "$deploy_create_vpc" == "true" ]]; then
      cfn_template_file="$base_dir/deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Create-VPC-eks.template.json"
    else
      cfn_template_file="$base_dir/deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Import-VPC-eks.template.json"
    fi
  else
    if [[ "$deploy_create_vpc" == "true" ]]; then
      cfn_template_name="Migration-Assistant-Infra-Create-VPC-eks.template.json"
    else
      cfn_template_name="Migration-Assistant-Infra-Import-VPC-eks.template.json"
    fi
    cfn_template_file=$(mktemp)
    echo "Downloading CFN template from GitHub release: ${cfn_template_name}"
    curl -fL -o "$cfn_template_file" \
      "https://github.com/opensearch-project/opensearch-migrations/releases/download/${BOOTSTRAP_ARTIFACT_VERSION}/${cfn_template_name}" \
      || { echo "Failed to download CFN template for version ${BOOTSTRAP_ARTIFACT_VERSION}"; rm -f "$cfn_template_file"; exit 1; }
  fi

  # Build parameter overrides for `aws cloudformation deploy`
  cfn_params=("Stage=${stage_filter}")
  if [[ "$deploy_create_vpc" == "true" ]]; then
    cfn_params+=("AvailabilityZoneCount=${availability_zone_count}")
  fi
  if [[ "$deploy_import_vpc" == "true" ]]; then
    cfn_params+=("VPCId=${vpc_id}")
    cfn_params+=("VPCSubnetIds=${subnet_ids}")
    # Add VPC endpoint creation parameters
    if [[ -n "$create_vpc_endpoints" ]]; then
      IFS=',' read -ra ep_arr <<< "$create_vpc_endpoints"
      for ep in "${ep_arr[@]}"; do
        case "$ep" in
          s3)
            cfn_params+=("CreateS3Endpoint=true")
            # Resolve route tables for the subnets so the S3 gateway endpoint gets associated
            s3_route_table_ids=$(aws ec2 describe-route-tables \
              --filters "Name=association.subnet-id,Values=${subnet_ids}" \
              --query 'RouteTables[].RouteTableId' --output text ${region:+--region "$region"} | tr '\t' ',')
            # Fall back to main route table if subnets use implicit association
            if [[ -z "$s3_route_table_ids" ]]; then
              s3_route_table_ids=$(aws ec2 describe-route-tables \
                --filters "Name=vpc-id,Values=${vpc_id}" "Name=association.main,Values=true" \
                --query 'RouteTables[0].RouteTableId' --output text ${region:+--region "$region"})
            fi
            cfn_params+=("S3EndpointRouteTableIds=${s3_route_table_ids}")
            ;;
          ecr)             cfn_params+=("CreateECREndpoint=true") ;;
          ecrDocker)       cfn_params+=("CreateECRDockerEndpoint=true") ;;
          cloudwatchLogs)  cfn_params+=("CreateCloudWatchLogsEndpoint=true") ;;
          monitoring)      cfn_params+=("CreateCloudWatchMonitoringEndpoint=true") ;;
          efs)             cfn_params+=("CreateEFSEndpoint=true") ;;
          sts)             cfn_params+=("CreateSTSEndpoint=true") ;;
          eksAuth)         cfn_params+=("CreateEKSAuthEndpoint=true") ;;
          *) echo "Warning: Unknown VPC endpoint type: $ep (valid: s3,ecr,ecrDocker,cloudwatchLogs,monitoring,efs,sts,eksAuth)" >&2 ;;
        esac
      done
    fi
  fi

  # Stack tags. CloudFormation propagates these to every resource it creates that supports
  # tagging -- the same behaviour as filling in the console's Tags step. Resources created later
  # by EKS Auto Mode are handled separately, further down.
  cfn_tag_args=()
  if [[ ${#tag_keys[@]} -gt 0 ]]; then
    cfn_tag_args=(--tags)
    for i in "${!tag_keys[@]}"; do
      cfn_tag_args+=("${tag_keys[$i]}=${tag_values[$i]}")
    done
    echo "Applying ${#tag_keys[@]} stack tag(s): ${cfn_tag_args[*]:1}"
  fi

  echo "Deploying CloudFormation stack: $cfn_stack_name"

  # Check for stack states that prevent deployment
  cfn_operation_in_progress=false
  if aws cloudformation describe-stacks --stack-name "$cfn_stack_name" ${region:+--region "$region"} >/dev/null 2>&1; then
    _stack_status=$(aws cloudformation describe-stacks --stack-name "$cfn_stack_name" ${region:+--region "$region"} \
      --query 'Stacks[0].StackStatus' --output text 2>/dev/null)
    case "$_stack_status" in
      CREATE_IN_PROGRESS|UPDATE_IN_PROGRESS|UPDATE_COMPLETE_CLEANUP_IN_PROGRESS|IMPORT_IN_PROGRESS)
        echo "Stack $cfn_stack_name is already in $_stack_status state."
        echo "Attaching to the in-progress operation instead of starting another update."
        cfn_operation_in_progress=true
        ;;
      *_IN_PROGRESS)
        echo "Error: Stack $cfn_stack_name is in $_stack_status state." >&2
        echo "Wait for that operation to finish before re-running." >&2
        exit 1
        ;;
    esac
    if [[ "$_stack_status" == "DELETE_FAILED" || "$_stack_status" == "UPDATE_ROLLBACK_FAILED" ]]; then
      echo "Error: Stack $cfn_stack_name is in $_stack_status state." >&2
      echo "Manual intervention required before re-running." >&2
      exit 1
    fi
  fi

  # Stream CloudFormation stack events in the background while deploy runs.
  # Polls describe-stack-events every 10s, prints new resource status changes.
  # This is a progress display, not a full history: --no-paginate fetches only the
  # first (newest) page of events instead of the whole stack history on every poll.
  # The API returns events newest first; the query reverses that page so events print
  # in the order they happened. (Reversing in the query rather than piping through
  # `tac` keeps this working on macOS, which does not ship `tac`. The query is applied
  # per page, which is another reason to fetch only one.)
  stream_cfn_events() {
    local seen_file
    seen_file=$(mktemp)
    trap "rm -f '$seen_file'" RETURN
    while true; do
      aws cloudformation describe-stack-events --stack-name "$cfn_stack_name" ${region:+--region "$region"} \
        --no-paginate \
        --query 'reverse(StackEvents)[].[EventId,Timestamp,ResourceStatus,ResourceType,LogicalResourceId,ResourceStatusReason]' \
        --output text 2>/dev/null | while IFS=$'\t' read -r eid ts status rtype logical reason; do
          grep -qxF "$eid" "$seen_file" 2>/dev/null && continue
          echo "$eid" >> "$seen_file"
          [[ "$reason" == "None" ]] && reason=""
          printf "  %-26s %-30s %-40s %s%s\n" "$ts" "$status" "$rtype" "$logical" "${reason:+  ($reason)}"
        done
      sleep 10
    done
  }

  run_cfn_deploy() {
    stream_cfn_events &
    local stream_pid=$!

    # `${arr[@]+"${arr[@]}"}` so an empty tag list expands to nothing instead of tripping `set -u`.
    aws cloudformation deploy \
      --template-file "$cfn_template_file" \
      --stack-name "$cfn_stack_name" \
      --parameter-overrides "${cfn_params[@]}" \
      ${cfn_tag_args[@]+"${cfn_tag_args[@]}"} \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
      --no-fail-on-empty-changeset \
      ${region:+--region "$region"}
    local rc=$?

    kill $stream_pid 2>/dev/null; wait $stream_pid 2>/dev/null
    return $rc
  }

  wait_for_cfn_operation() {
    local stream_pid stack_status rc=0
    local poll_interval="${CFN_POLL_INTERVAL:-10}"
    stream_cfn_events &
    stream_pid=$!
    while true; do
      stack_status=$(aws cloudformation describe-stacks \
        --stack-name "$cfn_stack_name" \
        ${region:+--region "$region"} \
        --query 'Stacks[0].StackStatus' \
        --output text 2>/dev/null || true)
      case "$stack_status" in
        *_IN_PROGRESS)
          sleep "$poll_interval"
          ;;
        CREATE_COMPLETE|UPDATE_COMPLETE|IMPORT_COMPLETE)
          break
          ;;
        *)
          echo "CloudFormation operation ended in $stack_status." >&2
          rc=1
          break
          ;;
      esac
    done
    kill "$stream_pid" 2>/dev/null || true
    wait "$stream_pid" 2>/dev/null || true
    return "$rc"
  }

  if [[ "$push_images_to_ecr" == "true" ]]; then
    start_bootstrap_mirroring \
      repository "migration-ecr-${stage_filter}-${region}"
  fi

  if [[ "$cfn_operation_in_progress" == "true" ]]; then
    wait_for_cfn_operation \
      || { echo "CloudFormation deployment failed for: $cfn_stack_name"; exit 1; }
  else
    run_cfn_deploy \
      || { echo "CloudFormation deployment failed for: $cfn_stack_name"; exit 1; }
  fi

  echo "CloudFormation stack deployed successfully: $cfn_stack_name"
fi

# The AWS Terraform module's migration_environment output exports the same values as the
# MigrationsExportString, so an environment that already holds them needs no CloudFormation stack.
exports_from_environment=false
if [[ "$deploy_cfn" == "false" && -n "${MIGRATIONS_EKS_CLUSTER_NAME:-}" &&
      -n "${MIGRATIONS_ECR_REGISTRY:-}" && -n "${SNAPSHOT_ROLE+set}" ]]; then
  if [[ -n "$stage_filter" && "$stage_filter" != "${STAGE:-}" ]]; then
    echo "Error: --stage '$stage_filter' does not match STAGE='${STAGE:-}' from the environment." >&2
    echo "  Unset the MIGRATIONS_* exports to use CloudFormation, or load the matching environment." >&2
    exit 1
  fi
  if [[ -n "$region" && -n "${AWS_CFN_REGION:-}" && "$region" != "$AWS_CFN_REGION" ]]; then
    echo "Error: --region '$region' does not match AWS_CFN_REGION='$AWS_CFN_REGION' from the environment." >&2
    echo "  Unset the MIGRATIONS_* exports to use CloudFormation, or load the matching environment." >&2
    exit 1
  fi
  exports_from_environment=true
  echo "Using Migration Assistant exports from the environment (cluster ${MIGRATIONS_EKS_CLUSTER_NAME});" \
    "skipping the CloudFormation export lookup."
else
  if ! output=$(get_cfn_export); then
    echo "Unable to find any CloudFormation stacks with a 'MigrationsExportString' export${region:+ in region '$region'}${stage_filter:+ matching stage '$stage_filter'}." >&2
    echo "Has the Migration Assistant CloudFormation template been deployed?" >&2
    if [[ -z "$region" ]]; then
      echo "No --region specified; used default region from AWS CLI config." >&2
    fi
    echo "If the stack is in a different region, re-run with --region <region>." >&2
    echo "To deploy the stack now, re-run with --deploy-create-vpc-cfn or --deploy-import-vpc-cfn." >&2
    echo "For a Terraform-provisioned environment, first run: eval \"\$(terraform output -raw migration_environment)\"" >&2
    exit 1
  fi
  echo "Setting ENV variables: $output"
  eval "$output"
fi

AWS_ACCOUNT="${AWS_ACCOUNT:-$(aws sts get-caller-identity --query Account --output text)}"
AWS_CFN_REGION="${AWS_CFN_REGION:-$(aws configure get region)}"
STAGE="${STAGE:-${MA_STAGE:-dev}}"
MA_QUALIFIER="${MA_QUALIFIER:-${MIGRATIONS_QUALIFIER:-default}}"

# Validate required variables from CFN exports
required_vars=(MIGRATIONS_EKS_CLUSTER_NAME MIGRATIONS_ECR_REGISTRY)
# Terraform exports an empty SNAPSHOT_ROLE when create_opensearch_service_snapshot_role is false.
if [[ "$exports_from_environment" != "true" ]]; then
  required_vars+=(SNAPSHOT_ROLE)
fi
missing_vars=()
for var in "${required_vars[@]}"; do
  if [[ -z "${!var:-}" ]]; then
    missing_vars+=("$var")
  fi
done
if [[ ${#missing_vars[@]} -gt 0 ]]; then
  echo "Error: The following required variables were not set by CloudFormation exports:" >&2
  printf '  %s\n' "${missing_vars[@]}" >&2
  echo "This may indicate the stack was deployed with an older template version." >&2
  exit 1
fi

if [[ "$push_images_to_ecr" == "true" && "$grant_eks_access_only" != "true" && -z "$mirror_pid" ]]; then
  start_bootstrap_mirroring registry "$MIGRATIONS_ECR_REGISTRY"
fi

# Show resolved configuration and sources
echo ""
echo "Resolved configuration:"
if [[ "$grant_eks_access_only" == "true" ]]; then
  echo "  Mode                   = Grant EKS access only (--grant-eks-access-only)"
elif [[ "$BOOTSTRAP_ARTIFACT_VERSION" == "local-build" ]]; then
  echo "  Mode                   = Build from source (--build)"
  if [[ "$with_load_test_images" == "true" ]]; then
    echo "  Load-test images       = Included (--with-load-test-images)"
  fi
else
  echo "  Mode                   = Published artifacts"
  echo "  Version                = ${BOOTSTRAP_ARTIFACT_VERSION}"
fi
echo "  AWS_ACCOUNT              = ${AWS_ACCOUNT}"
echo "  AWS_CFN_REGION           = ${AWS_CFN_REGION}"
echo "  STAGE                    = ${STAGE}"
echo "  EKS Cluster              = ${MIGRATIONS_EKS_CLUSTER_NAME}"
echo "  ECR Registry             = ${MIGRATIONS_ECR_REGISTRY}"
echo "  Snapshot Role            = ${SNAPSHOT_ROLE}"
echo ""
if [[ -n "$region" ]]; then
  echo "  Region source: --region flag ('$region')"
else
  echo "  Region source: CFN exports / AWS_CFN_REGION env / aws configure default"
fi
if [[ -n "$stage_filter" ]]; then
  echo "  Stage source:  --stage flag ('$stage_filter')"
else
  echo "  Stage source:  CFN exports / MA_STAGE env / default 'dev'"
fi
echo ""

# The AWS partition of an ARN (arn:<partition>:...), e.g. aws, aws-cn or aws-iso-e.
# Falls back to aws for anything that isn't an ARN.
arn_partition() {
  local rest="${1#arn:}"
  if [[ "$rest" == "$1" || -z "${rest%%:*}" ]]; then
    echo "aws"
  else
    echo "${rest%%:*}"
  fi
}

# --- EKS access entry (optional) ---
grant_eks_cluster_admin() {
  local principal_arn="$1"
  echo "Configuring EKS access for principal: $principal_arn"
  if aws eks describe-access-entry --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
       --principal-arn "$principal_arn" ${region:+--region "$region"} >/dev/null 2>&1; then
    echo "Access entry already exists, skipping create."
  else
    aws eks create-access-entry \
      --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
      --principal-arn "$principal_arn" \
      --type STANDARD \
      ${region:+--region "$region"}
  fi
  aws eks associate-access-policy \
    --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --principal-arn "$principal_arn" \
    --policy-arn "arn:$(arn_partition "$principal_arn"):eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy" \
    --access-scope type=cluster \
    ${region:+--region "$region"}
  echo "EKS access configured for $principal_arn"
}

if [[ -n "$eks_access_principal_arn" ]]; then
  grant_eks_cluster_admin "$eks_access_principal_arn"
fi

# In grant-only mode, adding the access entry above is the whole job. Exit before image mirroring and
# the Helm install so an admin can be added long after the initial bootstrap. It also doesn't need a
# kubeconfig entry because it never uses kubectl.
if [[ "$grant_eks_access_only" == "true" ]]; then
  echo "Done -- EKS access entry applied. Skipped image mirroring and Helm install (--grant-eks-access-only)."
  exit 0
fi

KUBE_CONTEXT="${kubectl_context:-${MIGRATIONS_EKS_CLUSTER_NAME}}"
aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --alias "${KUBE_CONTEXT}"
export KUBE_CONTEXT

if [[ "$skip_setting_k8s_context" == "true" ]]; then
  echo "Skipping setting kubectl current-context (--skip-setting-k8s-context)."
  echo "Use --context=${KUBE_CONTEXT} with kubectl or --kube-context=${KUBE_CONTEXT} with helm."
else
  kubectl config use-context "${KUBE_CONTEXT}" >/dev/null 2>&1
fi

# NodeClass that the Helm chart's NodePools will reference. The built-in EKS-managed class is the
# default, while --tags creates the named class below. On --skip-cfn-deploy this is resolved from
# the live cluster before any builds or Helm installs.
auto_mode_node_class="default"
AUTO_MODE_TAGGED_NODE_CLASS="migrations-tagged"
AUTO_MODE_BOOTSTRAP_NODE_POOL="migrations-bootstrap-pool"
configure_tagged_auto_mode_compute_requested=false
user_node_class_override=""

extract_rendered_node_class_names() {
  awk '
    {
      match($0, /^[[:space:]]*/)
      indent = RLENGTH
      line = $0
      sub(/^[[:space:]]*/, "", line)

      if (in_ref && indent <= ref_indent && line != "") {
        in_ref = 0
      }
      if (line == "nodeClassRef:") {
        in_ref = 1
        ref_indent = indent
        next
      }
      if (in_ref && line ~ /^name:[[:space:]]*/) {
        sub(/^name:[[:space:]]*/, "", line)
        gsub(/^"|"$/, "", line)
        print line
        in_ref = 0
      }
    }
  ' | sort -u
}

# --- validate --helm-values against the cluster ---
# This is the first point the cluster is reachable, and it comes before anything slow or
# cluster-mutating: the Auto Mode compute changes for --tags, image mirroring and --build. Render
# workload NodePools from the chart and the deployer's --helm-values files, and have the API server
# check it against the NodePool CRD that EKS Auto Mode installs. --dry-run=server persists nothing,
# so a bad value fails in seconds. Only the NodePool is checked: it is what the overrides
# configure, and the rest of the chart needs values (images, private ECR, TLS) that don't exist yet.
validate_helm_values_against_cluster() {
  [[ -z "$user_helm_values" || "$use_general_node_pool" == "true" ]] && return 0
  resolve_chart_source

  local eks_values nodepool node_class_probe f node_class_values_file
  local node_class_sentinel="auto-mode-discovery-sentinel"
  local -a values_args=() files rendered_node_classes=()
  if [[ -f "$ma_chart_dir" ]]; then
    eks_values=$(tar -xzOf "$ma_chart_dir" migration-assistant/valuesEks.yaml)
  else
    eks_values=$(<"$ma_chart_dir/valuesEks.yaml")
  fi

  # Put a valid sentinel before the deployer's values. If the rendered NodePools replace it, the
  # deployer explicitly selected aws.nodeClassName and that selection takes precedence over
  # automatic cluster discovery. A --set flag cannot be used here because Helm always gives it
  # precedence over values files regardless of argument order.
  node_class_values_file=$(mktemp)
  printf 'aws:\n  nodeClassName: %s\n' "$node_class_sentinel" > "$node_class_values_file"
  values_args=(-f "$node_class_values_file")
  IFS=',' read -ra files <<< "$user_helm_values"
  for f in "${files[@]}"; do values_args+=(-f "$f"); done

  echo "Validating workload NodePools from --helm-values (${user_helm_values}) against the cluster..."
  nodepool=$(helm template "$namespace" "$ma_chart_dir" --kube-version 1.35.0 -f - "${values_args[@]}" \
    --set stageName="$STAGE" --set aws.region="$AWS_CFN_REGION" --set-string aws.account="$AWS_ACCOUNT" \
    --set aws.eksClusterName="$MIGRATIONS_EKS_CLUSTER_NAME" \
    --show-only templates/resources/aws/workloadsNodePool.yaml <<< "$eks_values") \
    || { rm -f "$node_class_values_file"
         echo "Error: failed to render workload NodePools from --helm-values." >&2
         exit 1; }
  # The workload pools can be disabled by a values file while the dedicated migration-console
  # NodePool remains enabled. Probe that template too so any effective aws.nodeClassName is still
  # validated and passed consistently to BuildKit.
  node_class_probe=$(helm template "$namespace" "$ma_chart_dir" --kube-version 1.35.0 -f - "${values_args[@]}" \
    --set stageName="$STAGE" --set aws.region="$AWS_CFN_REGION" --set-string aws.account="$AWS_ACCOUNT" \
    --set aws.eksClusterName="$MIGRATIONS_EKS_CLUSTER_NAME" \
    --show-only templates/resources/migrationConsole.yaml <<< "$eks_values") \
    || { rm -f "$node_class_values_file"
         echo "Error: failed to resolve aws.nodeClassName from --helm-values." >&2
         exit 1; }
  rm -f "$node_class_values_file"

  while IFS= read -r f; do
    [[ -n "$f" ]] && rendered_node_classes+=("$f")
  done < <(printf '%s\n%s\n' "$nodepool" "$node_class_probe" | extract_rendered_node_class_names)
  if [[ ${#rendered_node_classes[@]} -gt 1 ]]; then
    echo "Error: --helm-values rendered workload NodePools with multiple NodeClasses:" >&2
    printf '  %s\n' "${rendered_node_classes[@]}" >&2
    exit 1
  fi
  if [[ ${#rendered_node_classes[@]} -eq 1 &&
        "${rendered_node_classes[0]}" != "$node_class_sentinel" ]]; then
    user_node_class_override="${rendered_node_classes[0]}"
    echo "  Explicit aws.nodeClassName override: ${user_node_class_override}"
  fi

  # A values file may deliberately disable the chart-managed NodePools. There is then no manifest
  # for the API server to validate, and its aws.nodeClassName value is irrelevant to this chart.
  if [[ -n "${nodepool//[[:space:]]/}" ]]; then
    kubectl --context="$KUBE_CONTEXT" apply --dry-run=server -f - <<< "$nodepool" >/dev/null \
      || { echo "Error: the cluster rejected workload NodePools as rendered from --helm-values:" >&2
           echo "$nodepool" >&2
           echo "  Nothing was changed. Fix the workloadsNodePool values and re-run the bootstrap." >&2
           exit 1; }
  fi
}
validate_helm_values_against_cluster

# =============================================================================
# Tag propagation to EKS Auto Mode resources
#
# CloudFormation stack tags stop at the resources CloudFormation creates. Nodes, volumes, ENIs
# and load balancers are created later and by EKS Auto Mode, which reads the tags it should apply
# from in-cluster objects: a NodeClass for compute, a StorageClass for volumes, Service/Ingress
# annotations for load balancers. Nothing in AWS bridges those two worlds, so this section does.
#
# The built-in "default" NodeClass is reconciled by EKS and cannot carry user tags, and the
# built-in "system"/"general-purpose" NodePools are hard-wired to it. Tagging every node therefore
# means replacing all three with our own, which is why this only happens when --tags is given.
# =============================================================================

# Inherit the deployed stack's tags when --tags was not passed, so that a re-run (in particular
# --skip-cfn-deploy, which never touches stack tags) keeps the cluster's tagging in step with the
# stack instead of silently reverting the nodes to untagged.
if [[ ${#tag_keys[@]} -eq 0 && -n "$cfn_stack_name" ]]; then
  while IFS=$'\t' read -r _tag_key _tag_value; do
    [[ -z "$_tag_key" ]] && continue
    validate_and_add_tag "$_tag_key" "$_tag_value" "inherited stack tag"
  done < <(aws cloudformation describe-stacks --stack-name "$cfn_stack_name" ${region:+--region "$region"} \
             --query 'Stacks[0].Tags[].[Key,Value]' --output text 2>/dev/null || true)
  if [[ ${#tag_keys[@]} -gt 0 ]]; then
    echo "Inherited ${#tag_keys[@]} tag(s) from stack $cfn_stack_name (no --tags given)."
  fi
fi

emit_resource_tags_yaml() {
  local i yaml_key yaml_value
  for i in "${!tag_keys[@]}"; do
    # A JSON string is also a valid YAML double-quoted scalar. Let jq escape quotes, backslashes,
    # newlines and other control characters rather than interpolating external tag text into YAML.
    yaml_key=$(jq -Rn --arg text "${tag_keys[$i]}" '$text')
    yaml_value=$(jq -Rn --arg text "${tag_values[$i]}" '$text')
    printf '    %s: %s\n' "$yaml_key" "$yaml_value"
  done
}

print_auto_mode_compute_state() {
  local nodepools_json="$1"
  local nodeclasses_json="$2"
  echo "  In-cluster NodePool references:" >&2
  jq -r '
    if (.items | length) == 0 then
      "    <none>"
    else
      .items[] |
        "    NodePool/\(.metadata.name) -> NodeClass/\(.spec.template.spec.nodeClassRef.name // "<missing>")"
    end
  ' <<< "$nodepools_json" >&2
  echo "  In-cluster NodeClasses:" >&2
  jq -r '
    if (.items | length) == 0 then
      "    <none>"
    else
      .items[] |
        ([.status.conditions[]? | select(.type == "Ready") | .status] | last // "Missing") as $ready |
        "    NodeClass/\(.metadata.name) Ready=\($ready)"
    end
  ' <<< "$nodeclasses_json" >&2
}

restore_auto_mode_node_class() {
  local node_class_name="$1"
  local restore_resource_tags="${2:-false}"
  local nodeclass_json ready_status tag_key tag_value restored_count=0

  if ! nodeclass_json=$(kubectl --context="${KUBE_CONTEXT}" \
      get nodeclass "$node_class_name" -o json 2>/dev/null); then
    echo "Error: selected EKS Auto Mode NodeClass '${node_class_name}' does not exist." >&2
    echo "  Refusing to install NodePools that would remain unusable." >&2
    return 1
  fi
  ready_status=$(jq -r \
    '[.status.conditions[]? | select(.type == "Ready") | .status] | last // "Missing"' \
    <<< "$nodeclass_json")
  if [[ "$ready_status" != "True" ]]; then
    echo "Error: selected EKS Auto Mode NodeClass '${node_class_name}' is not Ready" >&2
    echo "  (Ready=${ready_status}). Refusing to install NodePools that cannot provision nodes." >&2
    return 1
  fi

  if [[ "$restore_resource_tags" == "true" && "$node_class_name" != "default" ]]; then
    while IFS= read -r -d '' tag_key && IFS= read -r -d '' tag_value; do
      validate_and_add_tag "$tag_key" "$tag_value" "NodeClass/${node_class_name} tag"
      restored_count=$((restored_count + 1))
    done < <(jq -j '(.spec.tags // {}) | to_entries[] | .key, "\u0000", (.value | tostring), "\u0000"' \
      <<< "$nodeclass_json")
    echo "  Restored ${restored_count} resource tag(s) from NodeClass/${node_class_name}."
  fi
}

discover_auto_mode_node_class() {
  local nodepools_json nodeclasses_json candidate="" candidate_source=""
  local bootstrap_count
  local -a referenced_node_classes=() available_node_classes=()

  echo "Discovering the existing cluster's EKS Auto Mode NodeClass..."
  if ! nodepools_json=$(kubectl --context="${KUBE_CONTEXT}" get nodepools -o json); then
    echo "Error: could not list EKS Auto Mode NodePools in context ${KUBE_CONTEXT}." >&2
    return 1
  fi
  if ! nodeclasses_json=$(kubectl --context="${KUBE_CONTEXT}" get nodeclasses -o json); then
    echo "Error: could not list EKS Auto Mode NodeClasses in context ${KUBE_CONTEXT}." >&2
    return 1
  fi

  # This pool is deliberately kept outside the MA Helm release, so it survives the documented
  # uninstall/redeploy flow. Its reference is therefore the strongest signal of the class that the
  # bootstrap previously selected, even if other application NodePools are temporarily present.
  bootstrap_count=$(jq -r --arg name "$AUTO_MODE_BOOTSTRAP_NODE_POOL" \
    '[.items[] | select(.metadata.name == $name)] | length' <<< "$nodepools_json")
  if [[ "$bootstrap_count" -gt 0 ]]; then
    candidate=$(jq -r --arg name "$AUTO_MODE_BOOTSTRAP_NODE_POOL" \
      '.items[] | select(.metadata.name == $name) |
       .spec.template.spec.nodeClassRef.name // empty' <<< "$nodepools_json")
    if [[ -z "$candidate" ]]; then
      echo "Error: NodePool/${AUTO_MODE_BOOTSTRAP_NODE_POOL} has no NodeClass reference." >&2
      print_auto_mode_compute_state "$nodepools_json" "$nodeclasses_json"
      return 1
    fi
    candidate_source="persistent NodePool/${AUTO_MODE_BOOTSTRAP_NODE_POOL}"
  else
    while IFS= read -r candidate; do
      [[ -n "$candidate" ]] && referenced_node_classes+=("$candidate")
    done < <(jq -r '[.items[].spec.template.spec.nodeClassRef.name // empty] | unique[]' \
      <<< "$nodepools_json")

    if [[ ${#referenced_node_classes[@]} -eq 1 ]]; then
      candidate="${referenced_node_classes[0]}"
      candidate_source="the cluster's NodePool references"
    elif [[ ${#referenced_node_classes[@]} -gt 1 ]]; then
      echo "Error: EKS Auto Mode NodeClass discovery is ambiguous; NodePools reference multiple classes." >&2
      print_auto_mode_compute_state "$nodepools_json" "$nodeclasses_json"
      echo "  Set aws.nodeClassName explicitly with --helm-values to select one." >&2
      return 1
    else
      while IFS= read -r candidate; do
        [[ -n "$candidate" ]] && available_node_classes+=("$candidate")
      done < <(jq -r '[.items[].metadata.name] | unique[]' <<< "$nodeclasses_json")
      if [[ ${#available_node_classes[@]} -eq 1 ]]; then
        candidate="${available_node_classes[0]}"
        candidate_source="the only in-cluster NodeClass"
      elif [[ ${#available_node_classes[@]} -gt 1 ]]; then
        echo "Error: EKS Auto Mode NodeClass discovery is ambiguous; no NodePool selects among the classes." >&2
        print_auto_mode_compute_state "$nodepools_json" "$nodeclasses_json"
        echo "  Set aws.nodeClassName explicitly with --helm-values to select one." >&2
        return 1
      else
        echo "Error: no EKS Auto Mode NodeClass exists in context ${KUBE_CONTEXT}." >&2
        print_auto_mode_compute_state "$nodepools_json" "$nodeclasses_json"
        return 1
      fi
    fi
  fi

  auto_mode_node_class="$candidate"
  echo "  Preserving NodeClass/${auto_mode_node_class} from ${candidate_source}."
  restore_auto_mode_node_class "$auto_mode_node_class" true
}

resolve_auto_mode_configuration() {
  if [[ "$use_general_node_pool" == "true" ]]; then
    if [[ ${#tag_keys[@]} -gt 0 ]]; then
      echo "Error: --use-general-node-pool cannot be combined with resource tags restored from" >&2
      echo "  the existing stack; the built-in NodeClass/default cannot carry those tags." >&2
      return 1
    fi
    auto_mode_node_class="default"
    if [[ "$skip_cfn_deploy" == "true" ]]; then
      restore_auto_mode_node_class "$auto_mode_node_class" false
    fi
    return 0
  fi

  # Capture this before discovery restores tags from an existing class. Only explicit/stack tags
  # should reconfigure compute; restored tags are for Helm-created storage and load balancers.
  if [[ ${#tag_keys[@]} -gt 0 ]]; then
    configure_tagged_auto_mode_compute_requested=true
    auto_mode_node_class="$AUTO_MODE_TAGGED_NODE_CLASS"
  fi

  if [[ -n "$user_node_class_override" ]]; then
    if [[ "$configure_tagged_auto_mode_compute_requested" == "true" &&
          "$user_node_class_override" == "default" ]]; then
      echo "Error: --helm-values selects NodeClass/default, but --tags disables and removes it." >&2
      echo "  Select a Ready custom NodeClass or remove one of those explicit configurations." >&2
      return 1
    fi
    auto_mode_node_class="$user_node_class_override"
    if [[ "$configure_tagged_auto_mode_compute_requested" != "true" ||
          "$auto_mode_node_class" != "$AUTO_MODE_TAGGED_NODE_CLASS" ]]; then
      # Explicit --tags remain authoritative for resourceTags. Without them, restore the selected
      # custom class's tags so EBS and load-balancer resources stay aligned with its compute.
      restore_auto_mode_node_class "$auto_mode_node_class" \
        "$([[ ${#tag_keys[@]} -eq 0 ]] && echo true || echo false)"
    fi
    return 0
  fi

  if [[ "$configure_tagged_auto_mode_compute_requested" == "true" ]]; then
    return 0
  fi
  if [[ "$skip_cfn_deploy" == "true" ]]; then
    discover_auto_mode_node_class
  fi
}

resolve_auto_mode_configuration || exit 1

# Write the resolved NodeClass and resource tags into a generated values file. It is prepended to
# the deployer's --helm-values files so explicit user values retain their established precedence.
emit_auto_mode_helm_values() {
  local values_file
  values_file=$(mktemp)
  {
    echo "aws:"
    echo "  nodeClassName: \"${auto_mode_node_class}\""
    if [[ ${#tag_keys[@]} -gt 0 ]]; then
      echo "  resourceTags:"
      emit_resource_tags_yaml
    else
      echo "  resourceTags: {}"
    fi
  } > "$values_file"
  printf '%s' "$values_file"
}

prepend_auto_mode_helm_values() {
  auto_mode_values_file=$(emit_auto_mode_helm_values)
  echo "  Auto Mode values written to $auto_mode_values_file"
  if [[ -n "$extra_helm_values" ]]; then
    extra_helm_values="$auto_mode_values_file,$extra_helm_values"
  else
    extra_helm_values="$auto_mode_values_file"
  fi
}

set_buildkit_node_class_helm_arg() {
  # Put the generated value first so an explicit BUILDKIT_HELM_ARGS value retains precedence.
  BUILDKIT_HELM_ARGS="--set-string nodeClassName=${auto_mode_node_class}${BUILDKIT_HELM_ARGS:+ $BUILDKIT_HELM_ARGS}"
  export BUILDKIT_HELM_ARGS
}

AUTO_MODE_TAG_PROPAGATION_POLICY_NAME="AutoModeTagPropagationPolicy"

# EKS Auto Mode's managed cluster policy only permits its own eks:* request tags. A custom
# NodeClass carrying deployer tags therefore needs the additional Allow policy documented by EKS.
# The solution template declares the same named inline policy, but assert it here as well so --tags
# works against older stacks and clusters created outside this solution without manual IAM changes.
ensure_auto_mode_tag_propagation_policy() {
  local cluster_role_arn cluster_role_name part policy_document
  cluster_role_arn=$(aws eks describe-cluster \
    --name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --region "${AWS_CFN_REGION}" \
    --query 'cluster.roleArn' \
    --output text)
  if [[ -z "$cluster_role_arn" || "$cluster_role_arn" == "None" ]]; then
    echo "Error: could not resolve the EKS cluster role required for --tags." >&2
    exit 1
  fi
  cluster_role_name="${cluster_role_arn##*/}"
  part="$(arn_partition "$cluster_role_arn")"

  # Keep this aligned with the EKS Auto Mode tag-propagation policy:
  # https://docs.aws.amazon.com/eks/latest/userguide/auto-learn-iam.html
  # The Shield statement only supports the optional Auto Mode ALB Shield Advanced annotation;
  # granting it does not enable Shield or create a protection by itself.
  policy_document=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "Compute",
      "Effect": "Allow",
      "Action": [
        "ec2:CreateFleet",
        "ec2:RunInstances",
        "ec2:CreateLaunchTemplate"
      ],
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "aws:RequestTag/eks:eks-cluster-name": "\${aws:PrincipalTag/eks:eks-cluster-name}"
        },
        "StringLike": {
          "aws:RequestTag/eks:kubernetes-node-class-name": "*",
          "aws:RequestTag/eks:kubernetes-node-pool-name": "*"
        }
      }
    },
    {
      "Sid": "Storage",
      "Effect": "Allow",
      "Action": [
        "ec2:CreateVolume",
        "ec2:CreateSnapshot"
      ],
      "Resource": [
        "arn:${part}:ec2:*:*:volume/*",
        "arn:${part}:ec2:*:*:snapshot/*"
      ],
      "Condition": {
        "StringEquals": {
          "aws:RequestTag/eks:eks-cluster-name": "\${aws:PrincipalTag/eks:eks-cluster-name}"
        }
      }
    },
    {
      "Sid": "Networking",
      "Effect": "Allow",
      "Action": "ec2:CreateNetworkInterface",
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "aws:RequestTag/eks:eks-cluster-name": "\${aws:PrincipalTag/eks:eks-cluster-name}"
        },
        "StringLike": {
          "aws:RequestTag/eks:kubernetes-cni-node-name": "*"
        }
      }
    },
    {
      "Sid": "LoadBalancer",
      "Effect": "Allow",
      "Action": [
        "elasticloadbalancing:CreateLoadBalancer",
        "elasticloadbalancing:CreateTargetGroup",
        "elasticloadbalancing:CreateListener",
        "elasticloadbalancing:CreateRule",
        "ec2:CreateSecurityGroup"
      ],
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "aws:RequestTag/eks:eks-cluster-name": "\${aws:PrincipalTag/eks:eks-cluster-name}"
        }
      }
    },
    {
      "Sid": "Shield",
      "Effect": "Allow",
      "Action": [
        "shield:CreateProtection",
        "shield:TagResource"
      ],
      "Resource": "arn:${part}:shield::*:protection/*",
      "Condition": {
        "StringEquals": {
          "aws:RequestTag/eks:eks-cluster-name": "\${aws:PrincipalTag/eks:eks-cluster-name}"
        }
      }
    }
  ]
}
EOF
)

  echo "  Ensuring Auto Mode may propagate user tags through cluster role ${cluster_role_name}..."
  aws iam put-role-policy \
    --role-name "$cluster_role_name" \
    --policy-name "$AUTO_MODE_TAG_PROPAGATION_POLICY_NAME" \
    --policy-document "$policy_document" \
    || {
      echo "Error: --tags could not attach ${AUTO_MODE_TAG_PROPAGATION_POLICY_NAME}" >&2
      echo "  to cluster role ${cluster_role_name}. The caller needs iam:PutRolePolicy." >&2
      exit 1
    }
}

# Deny the cluster role from launching instances or provisioning volumes without every --tags key.
#
# The inverse of AutoModeTagPropagationPolicy: that policy *permits* user tags on these actions, this
# one *requires* them.
#
# Resource is scoped, NOT "*", and that distinction is the whole ballgame. ec2:RunInstances authorizes
# against every resource it touches -- instance, volume, network-interface, launch-template, subnet,
# security group, AMI -- but only the first three receive the request's tags. With Resource "*" the
# Null condition is therefore true for launch-template/subnet/etc and the deny fires on every launch,
# even when the instance tags are perfectly correct. A broad Resource is harmless in an Allow and
# fatal in a Deny. Observed as:
#   not authorized to perform: ec2:RunInstances on resource: .../launch-template/lt-0f5992a0cef08107b
#   with an explicit deny in an identity-based policy
#
# Each action is scoped to ONLY the resource types that receive its request tags, and each gets its
# own statement rather than sharing a Resource list, so one action's untaggable resource cannot make
# another action's deny fire.
#
# Deliberately NOT denied, on evidence from CloudTrail:
#   ec2:CreateFleet           carries no TagSpecifications at all; the instances it launches inherit
#                             their tags from the launch template. Denying it blocks the fleet path.
#   ec2:CreateLaunchTemplate  its own TagSpecification is null, for the same reason.
#   ec2:CreateSecurityGroup   MIXED. A load balancer's frontend SG carries the tags, but the shared
#                             "k8s-traffic-*" SG the controller attaches to nodes does not, so a deny
#                             here would block every load balancer.
#
# ec2:CreateNetworkInterface is denied without direct evidence that standalone CNI interface creates
# carry the user tags -- interfaces created as part of RunInstances demonstrably do. If nodes come up
# but pods never get addresses, this statement is the first thing to remove: Auto Mode does not put the user tags
# on those resources at create time, so requiring them would block the cluster rather than test it.
# The CloudTrail sweep in resource_tag_verifier.py reports on them instead, which is the right tool
# for actions we cannot influence.
#
# This reproduces a deployer SCP that requires tags on create: the failure surfaces at the moment of
# the untagged create rather than being discovered afterwards. That also means it will stop the
# cluster scaling if anything Auto Mode creates cannot carry the tags -- which is the point, since
# that is precisely what such a deployer experiences.
#
# Attached out-of-band rather than through CloudFormation so that enabling it cannot change the
# shipped template, and so it can be detached without a stack update when a statement turns out to be
# wrong. Remove with:
#   for p in $(aws iam list-role-policies --role-name <role> \
#       --query "PolicyNames[?starts_with(@, 'MigrationsDenyUntaggedCreates')]" --output text); do
#     aws iam delete-role-policy --role-name <role> --policy-name "$p"; done
DENY_POLICY_PREFIX="MigrationsDenyUntaggedCreates"
DENY_POLICY_NAME="${DENY_POLICY_PREFIX}ForTests"

enforce_tags_on_cluster_role_for_tests() {
  local cluster_role_arn cluster_role_name sid key i j
  cluster_role_arn=$(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --region "${AWS_CFN_REGION}" --query 'cluster.roleArn' --output text)
  if [[ -z "$cluster_role_arn" || "$cluster_role_arn" == "None" ]]; then
    echo "Error: could not resolve the EKS cluster role for tag enforcement." >&2
    exit 1
  fi
  cluster_role_name="${cluster_role_arn##*/}"

  # Parallel arrays rather than an associative array: bash 3.2 (macOS) has none.
  # Each group pairs a set of actions with ONLY the resource types that receive those actions'
  # request tags -- see the note above about RunInstances and launch-template/*.
  local part="${AWS_PARTITION:-$(arn_partition "$cluster_role_arn")}"
  local group_sids=(InstanceLaunch StandaloneStorage NetworkInterface LoadBalancer TargetGroup ListenerAndRule)
  local group_actions=(
    '"ec2:RunInstances"'
    '"ec2:CreateVolume", "ec2:CreateSnapshot"'
    '"ec2:CreateNetworkInterface"'
    '"elasticloadbalancing:CreateLoadBalancer"'
    '"elasticloadbalancing:CreateTargetGroup"'
    '"elasticloadbalancing:CreateListener", "elasticloadbalancing:CreateRule"'
  )
  local group_resources=(
    "\"arn:${part}:ec2:*:*:instance/*\", \"arn:${part}:ec2:*:*:volume/*\", \"arn:${part}:ec2:*:*:network-interface/*\""
    "\"arn:${part}:ec2:*:*:volume/*\", \"arn:${part}:ec2:*:*:snapshot/*\""
    "\"arn:${part}:ec2:*:*:network-interface/*\""
    "\"arn:${part}:elasticloadbalancing:*:*:loadbalancer/*\""
    "\"arn:${part}:elasticloadbalancing:*:*:targetgroup/*\""
    "\"arn:${part}:elasticloadbalancing:*:*:listener/*\", \"arn:${part}:elasticloadbalancing:*:*:listener-rule/*\""
  )

  # ONE statement per (action group x tag key). Multiple context keys inside a single condition
  # operator are AND-ed by IAM -- "All context keys in a condition element block must resolve to true"
  # -- so listing every key in one Null block would only deny a create that omitted ALL of them, and
  # a create carrying one tag but not another would pass. Deny statements are OR-ed instead, so a
  # separate statement per key is what makes each key independently mandatory.
  # https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_multi-value-conditions.html
  local statements=""
  local statement_count=0
  for i in "${!group_sids[@]}"; do
    for j in "${!tag_keys[@]}"; do
      key="${tag_keys[$j]}"
      # Sids permit only alphanumerics, and tag keys may contain dots or slashes.
      sid="Deny${group_sids[$i]}$(printf '%s' "$key" | tr -cd '[:alnum:]')"
      [[ -n "$statements" ]] && statements+=","
      statements+="{\"Sid\":\"${sid}\",\"Effect\":\"Deny\",\"Action\":[${group_actions[$i]}],\"Resource\":[${group_resources[$i]}],\"Condition\":{\"Null\":{\"aws:RequestTag/${key}\":\"true\"}}}"
      statement_count=$((statement_count + 1))
    done
  done

  # This is intentionally one inline policy. IAM's 10,240-character quota is aggregate across every
  # inline policy on a role, so splitting the statements into multiple policies does not create more
  # capacity and can leave a partially attached deny if a later part fails. This test-only option is
  # exercised with two tags in CI. A sufficiently large tag set can exceed the role's remaining
  # inline-policy quota; in that case put-role-policy fails and the caller must use fewer test tags.
  local policy_document
  policy_document="{\"Version\":\"2012-10-17\",\"Statement\":[${statements}]}"
  if [[ ${#policy_document} -gt 10240 ]]; then
    echo "Error: the generated test-only tag-enforcement policy is ${#policy_document} characters," >&2
    echo "  exceeding IAM's 10,240-character aggregate inline-policy quota for a role." >&2
    echo "  Use fewer --tags with --enforce-tags-on-create-for-tests." >&2
    exit 1
  fi

  echo "  Enforcing tags on create for TESTS on cluster role ${cluster_role_name}:"
  echo "    ${statement_count} deny statement(s): each of ${#tag_keys[@]} tag key(s) (${tag_keys[*]})"
  echo "    required independently on 6 action group(s)."

  # Remove legacy split-policy parts, but leave the current policy in place until put-role-policy
  # replaces it so a failed update does not create an avoidable enforcement gap.
  local stale
  for stale in $(aws iam list-role-policies --role-name "$cluster_role_name" \
      --query "PolicyNames[?starts_with(@, '${DENY_POLICY_PREFIX}')]" --output text 2>/dev/null); do
    [[ "$stale" == "$DENY_POLICY_NAME" ]] && continue
    aws iam delete-role-policy --role-name "$cluster_role_name" --policy-name "$stale" >/dev/null \
      2>&1 || true
  done

  aws iam put-role-policy \
    --role-name "$cluster_role_name" \
    --policy-name "$DENY_POLICY_NAME" \
    --policy-document "$policy_document" \
    || {
      echo "Error: failed to attach the test-only tag-enforcement policy." >&2
      echo "  IAM limits all inline policies on a role to 10,240 aggregate characters;" >&2
      echo "  use fewer --tags if the role has insufficient remaining capacity." >&2
      exit 1
    }
  echo "    Attached test-only inline policy ${DENY_POLICY_NAME}."
}

# An EC2-type access entry lets nodes launched by a custom NodeClass join the cluster. EKS creates
# one automatically for the built-in NodeClass; since we are about to disable the built-in
# NodePools, assert it ourselves rather than depend on what EKS leaves behind.
ensure_auto_node_access_entry() {
  local node_role_arn="$1"
  if aws eks describe-access-entry --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
       --principal-arn "$node_role_arn" --region "${AWS_CFN_REGION}" >/dev/null 2>&1; then
    echo "  Access entry for node role already exists."
  else
    echo "  Creating EC2 access entry for node role..."
    aws eks create-access-entry \
      --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
      --principal-arn "$node_role_arn" \
      --type EC2 \
      --region "${AWS_CFN_REGION}" >/dev/null
  fi
  aws eks associate-access-policy \
    --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --principal-arn "$node_role_arn" \
    --policy-arn "arn:$(arn_partition "$node_role_arn"):eks::aws:cluster-access-policy/AmazonEKSAutoNodePolicy" \
    --access-scope type=cluster \
    --region "${AWS_CFN_REGION}" >/dev/null \
    || {
      echo "Error: failed to associate AmazonEKSAutoNodePolicy with ${node_role_arn}." >&2
      exit 1
    }
}

# DescribeCluster can still report ACTIVE before an asynchronous update starts, and becomes ACTIVE
# again even if the update fails. Only the returned update ID identifies the operation we must wait
# for: https://docs.aws.amazon.com/eks/latest/APIReference/API_UpdateClusterConfig.html
wait_for_eks_config_update() {
  local update_id="$1" status attempt
  if [[ -z "$update_id" || "$update_id" == "None" ]]; then
    echo "Error: EKS did not return an update ID." >&2
    return 1
  fi
  echo "  Waiting for EKS configuration update ${update_id}..."
  for ((attempt = 0; attempt < 40; attempt++)); do
    status=$(aws eks describe-update \
      --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --update-id "$update_id" \
      --region "${AWS_CFN_REGION}" --query 'update.status' --output text) || return 1
    case "$status" in
      Successful) return 0 ;;
      InProgress) sleep 30 ;;
      *)
        echo "Error: EKS configuration update ${update_id} ended with status ${status}." >&2
        aws eks describe-update \
          --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --update-id "$update_id" \
          --region "${AWS_CFN_REGION}" --output json >&2 || true
        return 1
        ;;
    esac
  done
  echo "Error: Timed out waiting for EKS configuration update ${update_id} after 20 minutes." >&2
  return 1
}

configure_tagged_auto_mode_compute() {
  echo ""
  echo "Configuring EKS Auto Mode to tag the resources it creates..."

  # One describe call for everything the NodeClass needs. Mirror the built-in NodeClass's placement
  # rather than inventing selectors: the same subnets and the same EKS-managed cluster security
  # group the control plane already uses.
  local described node_role_arn node_role_name subnet_ids_text cluster_sg builtin_pools
  described=$(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}" \
    --query 'join(`|`, [cluster.computeConfig.nodeRoleArn || `NONE`, cluster.resourcesVpcConfig.clusterSecurityGroupId || `NONE`, join(`,`, cluster.resourcesVpcConfig.subnetIds || `[]`), join(`,`, cluster.computeConfig.nodePools || `[]`)])' \
    --output text)
  IFS='|' read -r node_role_arn cluster_sg subnet_ids_text builtin_pools <<< "$described"
  subnet_ids_text="${subnet_ids_text//,/ }"

  if [[ -z "$subnet_ids_text" || -z "$cluster_sg" || "$cluster_sg" == "NONE" ]]; then
    echo "Error: could not read subnets / cluster security group from ${MIGRATIONS_EKS_CLUSTER_NAME}." >&2
    exit 1
  fi
  ensure_auto_mode_tag_propagation_policy

  if [[ -z "$node_role_arn" || "$node_role_arn" == "NONE" || "$node_role_arn" == "None" ]]; then
    # A successful first run created the access entry before disabling the built-in NodePools.
    # That update clears computeConfig.nodeRoleArn, so on a re-run only the role name remains on
    # the custom NodeClass. Reuse it directly; no IAM lookup or access-entry reconciliation is
    # needed for state this script has already established.
    node_role_name=$(kubectl --context="${KUBE_CONTEXT}" \
      get nodeclass "${AUTO_MODE_TAGGED_NODE_CLASS}" \
      -o jsonpath='{.spec.role}' 2>/dev/null || true)
    if [[ -z "$node_role_name" ]]; then
      echo "Error: cluster ${MIGRATIONS_EKS_CLUSTER_NAME} has no Auto Mode node role in computeConfig" >&2
      echo "  and NodeClass ${AUTO_MODE_TAGGED_NODE_CLASS} does not contain one." >&2
      echo "  --tags can only propagate to nodes on an EKS Auto Mode cluster." >&2
      exit 1
    fi
    echo "  Reusing node role from existing NodeClass ${AUTO_MODE_TAGGED_NODE_CLASS}."
  else
    # NodeClass takes the role *name*, not the ARN. Roles may carry a path, so take the last segment.
    node_role_name="${node_role_arn##*/}"
    ensure_auto_node_access_entry "$node_role_arn"
  fi

  echo "  Node role:       $node_role_name"
  echo "  Subnets:         $subnet_ids_text"
  echo "  Security group:  $cluster_sg"

  # The NodeClass carries the tags; Auto Mode stamps them on the instance, its launch template,
  # its ENIs and its root/ephemeral volumes. The NodePool exists so that pods -- including the
  # chart's own pre-install hook Jobs -- have somewhere to land once the built-in pools are gone.
  # It is deliberately weighted below the chart's general-work-pool (weight 100).
  # Only two parts of the manifest need building: one subnet term per cluster subnet, and one line
  # per --tags entry. Both are assembled first so the YAML itself can be a heredoc and stay readable
  # as YAML rather than as a wall of quoted echo lines.
  local manifest subnet_terms tag_lines sid
  subnet_terms=$(for sid in $subnet_ids_text; do printf '    - id: %s\n' "$sid"; done)
  tag_lines=$(emit_resource_tags_yaml)

  # The bootstrap pool is deliberately small: it only has to hold whatever the chart's own pools do
  # not claim (pre-install hook Jobs, add-on pods). The chart's general-work-pool outranks it at
  # weight 100.
  manifest=$(mktemp)
  cat > "$manifest" <<EOF
apiVersion: eks.amazonaws.com/v1
kind: NodeClass
metadata:
  name: ${AUTO_MODE_TAGGED_NODE_CLASS}
spec:
  role: ${node_role_name}
  subnetSelectorTerms:
${subnet_terms}
  securityGroupSelectorTerms:
    - id: ${cluster_sg}
  tags:
${tag_lines}
---
apiVersion: karpenter.sh/v1
kind: NodePool
metadata:
  name: ${AUTO_MODE_BOOTSTRAP_NODE_POOL}
spec:
  weight: 1
  limits:
    cpu: "16"
    memory: 64Gi
  template:
    spec:
      nodeClassRef:
        group: eks.amazonaws.com
        kind: NodeClass
        name: ${AUTO_MODE_TAGGED_NODE_CLASS}
      requirements:
        - key: karpenter.sh/capacity-type
          operator: In
          values: ["on-demand"]
        - key: eks.amazonaws.com/instance-category
          operator: In
          values: ["c", "m", "r", "t"]
        - key: eks.amazonaws.com/instance-generation
          operator: Gt
          values: ["4"]
        - key: eks.amazonaws.com/instance-size
          operator: In
          values: ["medium", "large", "xlarge", "2xlarge"]
        - key: kubernetes.io/os
          operator: In
          values: ["linux"]
  disruption:
    consolidationPolicy: WhenEmpty
    consolidateAfter: 30m
EOF

  echo "  Applying NodeClass ${AUTO_MODE_TAGGED_NODE_CLASS} and NodePool ${AUTO_MODE_BOOTSTRAP_NODE_POOL}..."
  kubectl --context="${KUBE_CONTEXT}" apply -f "$manifest" \
    || { echo "Error: failed to apply the tagged NodeClass/NodePool." >&2; cat "$manifest" >&2; exit 1; }
  rm -f "$manifest"
  echo "  Waiting for NodeClass/${AUTO_MODE_TAGGED_NODE_CLASS} to become Ready..."
  kubectl --context="${KUBE_CONTEXT}" wait --for=condition=Ready \
    "nodeclass/${AUTO_MODE_TAGGED_NODE_CLASS}" --timeout=5m \
    || { echo "Error: NodeClass/${AUTO_MODE_TAGGED_NODE_CLASS} did not become Ready." >&2
         echo "  Refusing to remove the existing compute or install unusable NodePools." >&2
         exit 1; }

  # These are applied outside the Helm release on purpose: the release's own pre-install hook Jobs
  # need a schedulable node before the release exists, and they must outlive `helm uninstall` so a
  # reinstall has somewhere to run.

  # Every node from a built-in NodePool uses the untaggable "default" NodeClass, so leaving either
  # enabled would keep producing untagged instances. Removing them from computeConfig deletes the
  # NodePool objects and drains their nodes, so skip the call when they are already gone -- a re-run
  # should not disturb a cluster that is already in the desired state.
  if [[ -z "$builtin_pools" ]]; then
    echo "  Built-in NodePools are already disabled; leaving compute configuration untouched."
  else
    echo "  Disabling the built-in NodePools (currently: ${builtin_pools//,/, })."
    echo "  Their nodes use the untaggable 'default' NodeClass, so any pods running on them will be"
    echo "  drained and rescheduled onto ${AUTO_MODE_BOOTSTRAP_NODE_POOL}."
    # nodeRoleArn is deliberately omitted: EKS rejects it alongside an empty nodePools list with
    # "When Compute Config nodeRoleArn is not null or empty, nodePool value(s) must be provided."
    # A cluster with no built-in NodePools is expected to carry no compute-level node role -- the
    # role lives on the custom NodeClass applied above instead.
    # https://docs.aws.amazon.com/eks/latest/userguide/create-node-pool.html#_cluster_without_built_in_node_pools
    local update_id
    update_id=$(aws eks update-cluster-config \
      --name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
      --region "${AWS_CFN_REGION}" \
      --compute-config '{"enabled": true, "nodePools": []}' \
      --kubernetes-network-config '{"elasticLoadBalancing":{"enabled": true}}' \
      --storage-config '{"blockStorage":{"enabled": true}}' \
      --query 'update.id' --output text) || return 1
    wait_for_eks_config_update "$update_id" || return 1
  fi

  # The default NodeClass can still issue untagged RunInstances validation requests while EKS
  # removes the built-in compute resources. Wait for Kubernetes deletion too, including on a rerun
  # that already sees nodePools=[] but interrupted the previous handoff. kubectl's delete waiter
  # succeeds immediately for resources that are already absent.
  echo "  Waiting for the built-in NodePools and default NodeClass to be removed..."
  kubectl --context="${KUBE_CONTEXT}" wait --for=delete \
    nodepool/system nodepool/general-purpose nodeclass/default --timeout=20m || return 1
  echo "  Built-in compute removed; all compute now comes from ${AUTO_MODE_TAGGED_NODE_CLASS}."

  # Enforce only after the untaggable default NodeClass is gone. Attaching the test policy earlier
  # races its final validation calls and leaves historical IAM denials in the CloudTrail verdict,
  # even when every subsequent workload uses the tagged NodeClass successfully.
  if [[ "$enforce_tags_on_create_for_tests" == "true" ]]; then
    enforce_tags_on_cluster_role_for_tests
  fi
}

if [[ "$configure_tagged_auto_mode_compute_requested" == "true" ]]; then
  configure_tagged_auto_mode_compute
fi

# Always carry the resolved class into the MA chart. Prepending the generated file preserves the
# precedence of an explicit --helm-values file, including an explicit aws.nodeClassName.
prepend_auto_mode_helm_values

# Check if the general-purpose pool is disabled and re-enable if needed for installation. A custom
# NodeClass means the cluster intentionally uses custom compute, so do not restore an incompatible
# built-in pool merely because its tags were discovered rather than passed again.
if [[ "$auto_mode_node_class" == "default" ]]; then
  CURRENT_NODEPOOLS=$(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}" \
    --query 'cluster.computeConfig.nodePools' --output text 2>/dev/null)
  if [[ "$CURRENT_NODEPOOLS" != *"general-purpose"* ]] && [[ "$build" != "true" ]]; then
    echo "general-purpose nodepool is currently disabled."
    echo "Re-enabling it temporarily to allow pod scheduling during installation..."
    NODE_ROLE_ARN=$(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}" \
      --query 'cluster.computeConfig.nodeRoleArn' --output text)
    # A cluster previously bootstrapped with --tags has no compute-level node role, because EKS
    # requires nodeRoleArn to be empty when nodePools is. Re-enabling a built-in pool needs one back,
    # and EKS reports that as a confusing parameter error, so explain the situation instead.
    if [[ -z "$NODE_ROLE_ARN" || "$NODE_ROLE_ARN" == "None" ]]; then
      echo "Error: cluster ${MIGRATIONS_EKS_CLUSTER_NAME} has no computeConfig.nodeRoleArn, so the" >&2
      echo "  built-in NodePools cannot be re-enabled. This is what --tags leaves behind: its nodes" >&2
      echo "  come from a custom NodeClass instead. Re-run with --tags to keep using that path." >&2
      exit 1
    fi
    aws eks update-cluster-config \
      --name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
      --region "${AWS_CFN_REGION}" \
      --compute-config "{\"enabled\": true, \"nodePools\": [\"system\", \"general-purpose\"], \"nodeRoleArn\": \"${NODE_ROLE_ARN}\"}" \
      --kubernetes-network-config '{"elasticLoadBalancing":{"enabled": true}}' \
      --storage-config '{"blockStorage":{"enabled": true}}'
    echo "Waiting for cluster update to complete..."
    aws eks wait cluster-active --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}"
    echo "general-purpose nodepool re-enabled"
    if [[ "$disable_general_purpose_pool" == "true" ]]; then
      echo "Note: --disable-general-purpose-pool was specified, will disable it again after installation completes"
    fi
  fi
fi

# --- subnet connectivity pre-flight check ---
# Check if the selected subnets can reach the internet (needed for public image pulls).
# If subnets are isolated, check for required VPC endpoints.
if [[ "$ignore_checks" != "true" && -n "${VPC_ID:-}" ]]; then
  echo "Checking subnet connectivity..."
  # Get the subnet IDs from the CFN exports or the command line
  check_subnets="${subnet_ids:-}"
  if [[ -z "$check_subnets" ]]; then
    # For create-vpc, subnets are managed by CFN — skip check
    echo "  Skipping (create-VPC manages its own networking)"
  else
    isolated_subnets=()
    IFS=',' read -ra subnet_arr <<< "$check_subnets"
    for sid in "${subnet_arr[@]}"; do
      # Check explicit route table association first, then fall back to main route table
      rt_filter="Name=association.subnet-id,Values=$sid"
      has_nat=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
        --filters "$rt_filter" \
        --query 'RouteTables[0].Routes[?NatGatewayId!=null] | length(@)' --output text 2>/dev/null || echo 0)
      has_igw=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
        --filters "$rt_filter" \
        --query 'RouteTables[0].Routes[?GatewayId!=null && starts_with(GatewayId, `igw-`)] | length(@)' --output text 2>/dev/null || echo 0)
      # If no explicit association, check the VPC's main route table
      if [[ "${has_nat:-0}" -eq 0 && "${has_igw:-0}" -eq 0 ]]; then
        has_nat=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
          --filters "Name=vpc-id,Values=${VPC_ID}" "Name=association.main,Values=true" \
          --query 'RouteTables[0].Routes[?NatGatewayId!=null] | length(@)' --output text 2>/dev/null || echo 0)
        has_igw=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
          --filters "Name=vpc-id,Values=${VPC_ID}" "Name=association.main,Values=true" \
          --query 'RouteTables[0].Routes[?GatewayId!=null && starts_with(GatewayId, `igw-`)] | length(@)' --output text 2>/dev/null || echo 0)
      fi
      if [[ "${has_nat:-0}" -eq 0 && "${has_igw:-0}" -eq 0 ]]; then
        isolated_subnets+=("$sid")
      fi
    done

    if [[ ${#isolated_subnets[@]} -gt 0 ]]; then
      echo "  WARNING: The following subnets have no internet access (no NAT/IGW route):"
      printf '    %s\n' "${isolated_subnets[@]}"
      echo "  Pods on these subnets cannot pull public images."

      # Isolated subnets require mirroring — public registries are unreachable.
      if [[ "$push_images_to_ecr" != "true" ]]; then
        echo "" >&2
        echo "Error: --use-public-images cannot be used with isolated subnets (no NAT/IGW)." >&2
        echo "  public.ecr.aws has no VPC endpoint — public images cannot be pulled." >&2
        echo "  Remove --use-public-images to mirror public images to private ECR," >&2
        echo "  or --build to build from source and push to private ECR." >&2
        echo "  Or use --ignore-checks to skip this check." >&2
        exit 1
      fi

      echo "  Checking for VPC endpoints that enable private connectivity..."

      missing_endpoints=()
      required_services=("s3" "ecr.api" "ecr.dkr")
      service_labels=("S3 (gateway)" "ECR API" "ECR Docker")
      for i in "${!required_services[@]}"; do
        svc="${required_services[$i]}"
        existing=$(aws ec2 describe-vpc-endpoints ${region:+--region "$region"} \
          --filters "Name=vpc-id,Values=${VPC_ID}" "Name=service-name,Values=com.amazonaws.${AWS_CFN_REGION}.${svc}" \
          --query 'VpcEndpoints[?State==`available`] | length(@)' --output text 2>/dev/null || echo 0)
        if [[ "${existing:-0}" -eq 0 ]]; then
          missing_endpoints+=("${service_labels[$i]} (com.amazonaws.${AWS_CFN_REGION}.${svc})")
        else
          echo "  ✅ ${service_labels[$i]} endpoint exists"
        fi
      done

      # Also check optional but recommended endpoints
      optional_services=("logs" "elasticfilesystem")
      optional_labels=("CloudWatch Logs" "EFS")
      for i in "${!optional_services[@]}"; do
        svc="${optional_services[$i]}"
        existing=$(aws ec2 describe-vpc-endpoints ${region:+--region "$region"} \
          --filters "Name=vpc-id,Values=${VPC_ID}" "Name=service-name,Values=com.amazonaws.${AWS_CFN_REGION}.${svc}" \
          --query 'VpcEndpoints[?State==`available`] | length(@)' --output text 2>/dev/null || echo 0)
        if [[ "${existing:-0}" -eq 0 ]]; then
          echo "  ⚠️  ${optional_labels[$i]} endpoint not found (optional but recommended)"
        else
          echo "  ✅ ${optional_labels[$i]} endpoint exists"
        fi
      done

      if [[ ${#missing_endpoints[@]} -gt 0 ]]; then
        echo "" >&2
        echo "Error: Required VPC endpoints missing for isolated subnets:" >&2
        printf '  ❌ %s\n' "${missing_endpoints[@]}" >&2
        echo "" >&2
        echo "Options:" >&2
        echo "  1. Use --create-vpc-endpoints to have the CFN template create them." >&2
        echo "  2. Use subnets with NAT/IGW routes instead." >&2
        echo "  3. Use --ignore-checks to skip this check." >&2
        exit 1
      fi
    else
      echo "  All subnets have internet access (NAT or IGW routes)."
    fi
  fi
fi



# --- join private ECR mirroring before its artifacts are consumed ---
# CloudFormation and cluster configuration have run concurrently with this work.
if [[ "$push_images_to_ecr" == "true" ]]; then
  wait_for_bootstrap_mirroring
  if [[ "$include_ma_images" != "true" ]]; then
    echo "Skipping MA image mirroring — --build will push locally-built images."
  fi

  # Force private images since we're mirroring everything to ECR
  use_public_images=false
fi

if [[ "$build" == "true" && -z "$ma_images_source" ]]; then
  # Always build for both architectures on EKS
  MULTI_ARCH_NATIVE=true
  BUILD_TARGETS=":buildImages:buildImagesToRegistry"
  # Load-test images are their own aggregate (see buildImages/build.gradle), so
  # buildImagesToRegistry does not build them. Only the k6 load-test cases
  # (Test008x) need migrations/k6_runner and migrations/k6_operator in the registry.
  if [[ "$with_load_test_images" == "true" ]]; then
    BUILD_TARGETS="${BUILD_TARGETS} :buildImages:buildKitLoadTestAll"
  fi
  export MULTI_ARCH_NATIVE

  # When mirroring, buildkit still pulls from public registries — building on
  # isolated clusters is not supported. Use --ma-images-source instead.

  # EKS/bootstrap builds are always Kubernetes-hosted; they cannot assume
  # direct access to a local Docker daemon from the target environment.
  source "${base_dir}/buildImages/backends/eksKubernetesBuildkit.sh"
  BUILDER_NAME="builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}"
  if [[ "$push_images_to_ecr" == "true" ]]; then
    # Use the ECR-mirrored buildkit image so the kubernetes driver doesn't pull
    # from Docker Hub. mirror_images_to_ecr already copied this image above.
    ECR_HOST="${MIGRATIONS_ECR_REGISTRY%%/*}"
    export BUILDKIT_IMAGE="${ECR_HOST}/mirrored/docker.io/moby/buildkit:buildx-stable-1"
  else
    unset BUILDKIT_IMAGE
  fi
  # Reconcile the release on every build, even when an existing buildx builder is healthy. The
  # BuildKit NodePool must use the same resolved NodeClass as the MA NodePools.
  set_buildkit_node_class_helm_arg
  ensure_eks_buildkit_release

  if builder_status=$(docker buildx inspect "$BUILDER_NAME" 2>/dev/null) \
      && buildx_nodes_are_ready <<< "$builder_status"; then
    echo "Buildkit release reconciled and existing builder is healthy"
  else
    echo "Setting up the buildx builder for local builds..."
    # Remove stale builder if it exists but failed health check above (e.g. orphaned
    # from a previous interrupted run). This is safe — the builder is recreated below.
    docker buildx rm "$BUILDER_NAME" 2>/dev/null || true
    ensure_eks_buildx_builder
  fi

  echo "Building images to MIGRATIONS_ECR_REGISTRY=$MIGRATIONS_ECR_REGISTRY"
  ecr_domain="${MIGRATIONS_ECR_REGISTRY%%/*}"
  echo "Logging in to ECR registry: $ecr_domain"
  aws ecr get-login-password --region "${AWS_CFN_REGION}" \
    | docker login --username AWS --password-stdin "$ecr_domain" \
    || { echo "ECR login failed"; exit 1; }

  # Test-only images used by Docker Compose are never needed on EKS: the testClusters
  # source there uses the public elasticsearch-oss image.
  skip_test_arg="-PskipTestImages=true"

  "$base_dir/gradlew" -p "$base_dir" ${BUILD_TARGETS} -PregistryEndpoint="$MIGRATIONS_ECR_REGISTRY" -Pbuilder="$BUILDER_NAME" -PimageVersion="$IMAGE_TAG" $skip_test_arg -x test \
    || { echo "Image build failed, retrying in 10s..."; sleep 10; \
         "$base_dir/gradlew" -p "$base_dir" ${BUILD_TARGETS} -PregistryEndpoint="$MIGRATIONS_ECR_REGISTRY" -Pbuilder="$BUILDER_NAME" -PimageVersion="$IMAGE_TAG" $skip_test_arg -x test; } \
    || { echo "Image build failed on retry, giving up."; exit 1; }

  echo "Cleaning up docker buildx builder to free buildkit pods..."
  docker buildx rm "$BUILDER_NAME" 2>/dev/null || true
  echo "Builder removed. Buildkit pods will be terminated by kubernetes driver."
fi

# --- image source selection ---
# When --build is set (without --ma-images-source), images are built from source
# and pushed to the private ECR registry. Otherwise, public images are pulled from
# public.ecr.aws/opensearchproject, tagged with $BOOTSTRAP_ARTIFACT_VERSION.
# To add a new image, add entries to both branches below.
if [[ "$use_public_images" == "false" ]]; then
  IMAGE_FLAGS="\
    --set images.captureProxy.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.captureProxy.tag=migrations_capture_proxy_${IMAGE_TAG} \
    --set images.trafficReplayer.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.trafficReplayer.tag=migrations_traffic_replayer_${IMAGE_TAG} \
    --set images.reindexFromSnapshot.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.reindexFromSnapshot.tag=migrations_reindex_from_snapshot_${IMAGE_TAG} \
    --set images.migrationConsole.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.migrationConsole.tag=migrations_migration_console_${IMAGE_TAG} \
    --set images.installer.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.installer.tag=migrations_migration_console_${IMAGE_TAG}"
# Use latest public images
else
  echo "Using public images tagged '$BOOTSTRAP_ARTIFACT_VERSION'"
  IMAGE_FLAGS="\
    --set images.captureProxy.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-capture-proxy \
    --set images.captureProxy.tag=$BOOTSTRAP_ARTIFACT_VERSION \
    --set images.trafficReplayer.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-replayer \
    --set images.trafficReplayer.tag=$BOOTSTRAP_ARTIFACT_VERSION \
    --set images.reindexFromSnapshot.repository=public.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot \
    --set images.reindexFromSnapshot.tag=$BOOTSTRAP_ARTIFACT_VERSION \
    --set images.migrationConsole.repository=public.ecr.aws/opensearchproject/opensearch-migrations-console \
    --set images.migrationConsole.tag=$BOOTSTRAP_ARTIFACT_VERSION \
    --set images.installer.repository=public.ecr.aws/opensearchproject/opensearch-migrations-console \
    --set images.installer.tag=$BOOTSTRAP_ARTIFACT_VERSION"
fi

# --- chart source selection ---
# By default, the Helm chart is downloaded from the GitHub release matching
# $BOOTSTRAP_ARTIFACT_VERSION. With --build, it comes from the local repo checkout instead.
# Dashboard JSONs are bundled inside the chart.
resolve_chart_source

# --- helm install ---
# When using packaged chart, valuesEks.yaml is extracted from the tgz since
# helm can't reference files inside an archive. When using the local chart,
# values files are referenced directly. To add new values files, update both paths.
if [[ -f "$ma_chart_dir" ]]; then
  tar xzf "${ma_chart_dir}" migration-assistant/valuesEks.yaml migration-assistant/values.yaml
  HELM_VALUES_FLAGS="-f migration-assistant/values.yaml -f migration-assistant/valuesEks.yaml"
else
  HELM_VALUES_FLAGS="-f ${ma_chart_dir}/values.yaml -f ${ma_chart_dir}/valuesEks.yaml"
fi

[[ "$helm_upgrade" == "true" ]] || check_existing_ma_release "$namespace" "$namespace"

# Build TLS-related helm --set flags and create Pod Identity Associations
TLS_HELM_FLAGS=""

# Override custom nodepool when --use-general-node-pool is set
NODEPOOL_HELM_FLAGS=""
# The zone spread's minDomains comes from the number of zones the cluster's subnets cover, so a
# workload fleet waits for capacity in every zone rather than filling the first zones to get nodes.
cluster_zone_count=$(aws ec2 describe-subnets --region "${AWS_CFN_REGION}" \
  --subnet-ids $(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}" \
    --query 'cluster.resourcesVpcConfig.subnetIds' --output text) \
  --query 'Subnets[].AvailabilityZone' --output text 2>/dev/null | tr '\t' '\n' | sort -u | grep -c . || true)
if [[ "${cluster_zone_count:-0}" -gt 1 ]]; then
  NODEPOOL_HELM_FLAGS="--set workloadsNodePool.zoneCount=${cluster_zone_count}"
fi
if [[ "$use_general_node_pool" == "true" ]]; then
  NODEPOOL_HELM_FLAGS="${NODEPOOL_HELM_FLAGS} --set cluster.useCustomKarpenterNodePool=false"
fi

# For any PCA mode, ensure Pod Identity Association exists
if [[ "$tls_mode" == "pca-existing" || "$tls_mode" == "pca-create" ]]; then
  echo "Ensuring Pod Identity Association for aws-pca-issuer..."
  ARGO_ASSOC_ID=$(aws eks list-pod-identity-associations \
    --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --region "${AWS_CFN_REGION}" \
    --namespace "${namespace}" \
    --service-account argo-workflow-executor \
    --query 'associations[0].associationId' --output text 2>/dev/null)
  PID_ROLE_ARN=""
  if [[ -n "$ARGO_ASSOC_ID" && "$ARGO_ASSOC_ID" != "None" ]]; then
    PID_ROLE_ARN=$(aws eks describe-pod-identity-association \
      --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
      --region "${AWS_CFN_REGION}" \
      --association-id "$ARGO_ASSOC_ID" \
      --query 'association.roleArn' --output text)

    EXISTING=$(aws eks list-pod-identity-associations \
      --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
      --region "${AWS_CFN_REGION}" \
      --namespace "${namespace}" \
      --service-account aws-pca-issuer \
      --query 'associations[0].associationId' --output text 2>/dev/null)
    if [[ -z "$EXISTING" || "$EXISTING" == "None" ]]; then
      aws eks create-pod-identity-association \
        --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
        --region "${AWS_CFN_REGION}" \
        --namespace "${namespace}" \
        --service-account aws-pca-issuer \
        --role-arn "$PID_ROLE_ARN" \
        --query 'association.associationId' --output text
      echo "Created Pod Identity Association for aws-pca-issuer"
    else
      echo "Pod Identity Association for aws-pca-issuer already exists"
    fi
  else
    echo "WARNING: Could not find existing Pod Identity role. aws-pca-issuer may not have AWS credentials." >&2
  fi
fi

case "$tls_mode" in
  pca-existing)
    TLS_HELM_FLAGS="--set conditionalPackageInstalls.aws-privateca-issuer=true"
    TLS_HELM_FLAGS="$TLS_HELM_FLAGS --set awsPrivateCA.arn=$pca_arn"
    TLS_HELM_FLAGS="$TLS_HELM_FLAGS --set awsPrivateCA.region=${AWS_CFN_REGION}"
    ;;
  pca-create)
    TLS_HELM_FLAGS="--set conditionalPackageInstalls.aws-privateca-issuer=true"
    TLS_HELM_FLAGS="$TLS_HELM_FLAGS --set conditionalPackageInstalls.ack-acmpca-controller=true"
    TLS_HELM_FLAGS="$TLS_HELM_FLAGS --set awsPrivateCA.create=true"
    TLS_HELM_FLAGS="$TLS_HELM_FLAGS --set awsPrivateCA.region=${AWS_CFN_REGION}"

    # Also create Pod Identity for ACK controller
    if [[ -n "$PID_ROLE_ARN" ]]; then
      EXISTING_ACK=$(aws eks list-pod-identity-associations \
        --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
        --region "${AWS_CFN_REGION}" \
        --namespace "${namespace}" \
        --service-account ack-acmpca-controller \
        --query 'associations[0].associationId' --output text 2>/dev/null)
      if [[ -z "$EXISTING_ACK" || "$EXISTING_ACK" == "None" ]]; then
        aws eks create-pod-identity-association \
          --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
          --region "${AWS_CFN_REGION}" \
          --namespace "${namespace}" \
          --service-account ack-acmpca-controller \
          --role-arn "$PID_ROLE_ARN" \
          --query 'association.associationId' --output text
        echo "Created Pod Identity Association for ack-acmpca-controller"
      fi
    fi
    ;;
esac

echo "=== Helm install configuration ==="
echo "Chart: ${ma_chart_dir}"
echo "Namespace: ${namespace}"
echo "Values flags: ${HELM_VALUES_FLAGS}"
if [[ -n "$extra_helm_values" ]]; then
  echo "Extra values files: ${extra_helm_values}"
  IFS=',' read -ra EXTRA_FILES <<< "$extra_helm_values"
  for f in "${EXTRA_FILES[@]}"; do
    echo "--- Contents of $f ---"
    cat "$f"
    echo "--- End $f ---"
  done
fi
echo "Set flags:"
echo "  stageName=${STAGE}"
echo "  aws.region=${AWS_CFN_REGION}"
echo "  aws.account=${AWS_ACCOUNT}"
echo "  aws.eksClusterName=${MIGRATIONS_EKS_CLUSTER_NAME}"
echo "  defaultBucketConfiguration.snapshotRoleArn=${SNAPSHOT_ROLE}"
echo "Image flags:"
echo "  ${IMAGE_FLAGS}"
echo "TLS flags:"
echo "  ${TLS_HELM_FLAGS}"
echo "NodePool flags:"
echo "  ${NODEPOOL_HELM_FLAGS}"
echo "=== End helm install configuration ==="

echo "Installing Migration Assistant chart now, this can take a couple minutes..."
# Build extra values flags — split comma-separated list into individual -f args
EXTRA_VALUES_FLAGS=""
if [[ -n "$extra_helm_values" ]]; then
  IFS=',' read -ra _evf <<< "$extra_helm_values"
  for f in "${_evf[@]}"; do
    EXTRA_VALUES_FLAGS="$EXTRA_VALUES_FLAGS -f $f"
  done
fi
# Suppress trace to avoid leaking helm values in logs
set +x
helm $([[ "$helm_upgrade" == "true" ]] && echo "upgrade --install" || echo install) "$namespace" "${ma_chart_dir}" \
  --kube-context="${KUBE_CONTEXT}" \
  --namespace $namespace \
  --create-namespace \
  --timeout 20m \
  $HELM_VALUES_FLAGS \
  $EXTRA_VALUES_FLAGS \
  --set stageName="${STAGE}" \
  --set aws.region="${AWS_CFN_REGION}" \
  --set-string aws.account="${AWS_ACCOUNT}" \
  --set aws.eksClusterName="${MIGRATIONS_EKS_CLUSTER_NAME}" \
  --set defaultBucketConfiguration.snapshotRoleArn="${SNAPSHOT_ROLE}" \
  $IMAGE_FLAGS \
  $TLS_HELM_FLAGS \
  $NODEPOOL_HELM_FLAGS \
  || { echo "Installing Migration Assistant chart failed..."; dump_helm_debug_info "$namespace"; exit 1; }
set -x

kubectl config set-context "${KUBE_CONTEXT}" --namespace="$namespace" >/dev/null 2>&1

# Not reachable with --tags, which forces this to false: that path already reduced nodePools to [],
# and re-enabling "system" here would put untaggable nodes back into the cluster.
if [[ "$disable_general_purpose_pool" == "true" ]]; then
  echo "Disabling EKS Auto Mode general-purpose nodepool..."
  NODE_ROLE_ARN=$(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}" \
    --query 'cluster.computeConfig.nodeRoleArn' --output text)
  aws eks update-cluster-config \
    --name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --region "${AWS_CFN_REGION}" \
    --compute-config "{\"enabled\": true, \"nodePools\": [\"system\"], \"nodeRoleArn\": \"${NODE_ROLE_ARN}\"}" \
    --kubernetes-network-config '{"elasticLoadBalancing":{"enabled": true}}' \
    --storage-config '{"blockStorage":{"enabled": true}}'
  echo "Waiting for cluster update to complete (this may take a few minutes)..."
  aws eks wait cluster-active --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}"
  echo "general-purpose nodepool disabled"
fi

if [[ "$skip_console_exec" == "false" ]]; then
  kubectl --context="${KUBE_CONTEXT}" -n "$namespace" wait --for=condition=ready pod/migration-console-0 --timeout=300s
  cmd="kubectl --context=${KUBE_CONTEXT} -n $namespace exec --stdin --tty migration-console-0 -- /bin/bash"
  echo "Accessing migration console with command: $cmd"
  eval "$cmd"
fi
