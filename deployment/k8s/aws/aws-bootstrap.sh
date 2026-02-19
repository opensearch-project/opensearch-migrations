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
# By default, all artifacts are downloaded from the latest GitHub release:
#   - CFN templates from the Solutions S3 bucket
#   - Helm chart from GitHub releases
#   - Dashboard JSON files from GitHub releases
#   - Container images for pods will use images from public.ecr.aws/opensearchproject
#
# Developers can override any of these with --build-* flags to use locally
# built artifacts from a repo checkout. When mixing built and downloaded
# artifacts, --version is required to prevent accidental version mismatches.
#
# ARCHITECTURE NOTES FOR FUTURE CHANGES:
#   - Version is resolved ONCE at startup and threaded through all downloads.
#     To add a new downloaded artifact, use $RELEASE_VERSION for its URL.
#   - The --build-* flags (--build-cfn, --build-images, --build-chart-and-dashboards)
#     each gate a section of the script. To add a new buildable component,
#     add a flag, add it to the build_count validation, and add a conditional
#     block that switches between download and local build.
#   - CFN parameters are in the "CFN deployment" section. The parameters come
#     from the CDK stack in deployment/migration-assistant-solution/lib/solutions-stack-eks.ts.
#   - Helm values: when using the packaged chart, valuesEks.yaml is extracted
#     from the tgz. When using the local chart, it's referenced directly.
#     To add new values files, update both the extract and the direct path.
# =============================================================================

set -euo pipefail

# --- timestamp all output ---
exec > >(while IFS= read -r line; do printf '%s | %s\n' "$(date '+%H:%M:%S')" "$line"; done)
exec 2> >(while IFS= read -r line; do printf '%s | %s\n' "$(date '+%H:%M:%S')" "$line"; done >&2)

# --- defaults ---
base_dir=""
namespace="ma"
build_images=false
use_public_images=true
skip_console_exec=false
stage_filter=""
extra_helm_values=""
disable_general_purpose_pool=false
region="${AWS_CFN_REGION:-}"
deploy_create_vpc=false
deploy_import_vpc=false
build_cfn=false
cfn_stack_name=""
vpc_id=""
subnet_ids=""
eks_access_principal_arn=""
skip_cfn_deploy=false
build_chart_and_dashboards=false
version=""

# --- argument parsing ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-dir) base_dir="$2"; shift 2 ;;
    --ma-chart-dir) ma_chart_dir="$2"; shift 2 ;;
    --namespace) namespace="$2"; shift 2 ;;
    --build-images) build_images=true; shift 1 ;;
    --skip-console-exec) skip_console_exec=true; shift 1 ;;
    --stage) stage_filter="$2"; shift 2 ;;
    --helm-values) extra_helm_values="$2"; shift 2 ;;
    --disable-general-purpose-pool) disable_general_purpose_pool=true; shift 1 ;;
    --region) region="$2"; shift 2 ;;
    --deploy-create-vpc-cfn) deploy_create_vpc=true; shift 1 ;;
    --deploy-import-vpc-cfn) deploy_import_vpc=true; shift 1 ;;
    --build-cfn) build_cfn=true; shift 1 ;;
    --stack-name) cfn_stack_name="$2"; shift 2 ;;
    --vpc-id) vpc_id="$2"; shift 2 ;;
    --subnet-ids) subnet_ids="$2"; shift 2 ;;
    --eks-access-principal-arn) eks_access_principal_arn="$2"; shift 2 ;;
    --skip-cfn-deploy) skip_cfn_deploy=true; shift 1 ;;
    --build-chart-and-dashboards) build_chart_and_dashboards=true; shift 1 ;;
    --version) version="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo ""
      echo "Bootstrap an EKS cluster for the OpenSearch Migration Assistant."
      echo "Optionally deploy the Migration Assistant CloudFormation stack first."
      echo ""
      echo "CloudFormation deployment options (exactly one required):"
      echo "  --deploy-create-vpc-cfn                   Deploy the Create-VPC EKS CloudFormation template."
      echo "                                            Creates a new VPC with all required networking."
      echo "  --deploy-import-vpc-cfn                   Deploy the Import-VPC EKS CloudFormation template."
      echo "                                            Uses an existing VPC — requires --vpc-id and --subnet-ids."
      echo "  --skip-cfn-deploy                         Skip CloudFormation deployment. Use when the Migration"
      echo "                                            Assistant stack is already deployed and you only want to"
      echo "                                            bootstrap the EKS cluster (install helm chart, images, etc)."
      echo "  --stack-name <name>                       CloudFormation stack name (required with --deploy-*-cfn)."
      echo "  --vpc-id <id>                             VPC ID (required with --deploy-import-vpc-cfn)."
      echo "  --subnet-ids <id1,id2>                    Comma-separated subnet IDs, each in a different AZ"
      echo "                                            (required with --deploy-import-vpc-cfn)."
      echo ""
      echo "EKS access options:"
      echo "  --eks-access-principal-arn <arn>          Grant an IAM principal (role/user) cluster-admin access"
      echo "                                            to the EKS cluster. Useful after a fresh CFN deploy when"
      echo "                                            the deploying principal needs kubectl access, or to grant"
      echo "                                            access to a CI role or teammate."
      echo ""
      echo "Deployment options:"
      echo "  --namespace <val>                         K8s namespace (default: $namespace)"
      echo "  --helm-values <path>                      Extra values file for helm install"
      echo "  --disable-general-purpose-pool            Disable EKS Auto Mode general-purpose pool, which will"
      echo "                                            require another nodepool to be configured (such as with"
      echo "                                            'cluster.useCustomKarpenterNodePool: true' in the file"
      echo "                                            passed to --helm-values)"
      echo "  --stage <val>                             Stage name for CFN exports filter and CFN Stage parameter (default: dev)"
      echo "  --region <val>                            AWS region"
      echo "  --skip-console-exec                       Don't exec into console pod (default: $skip_console_exec)"
      echo ""
      echo "Build options:"
      echo "  --base-dir <path>                         opensearch-migrations directory"
      echo "                                            (default: ../../.. from the script location)"
      echo "  --build-cfn                               Build CFN templates from source (gradle cdkSynthMinified)"
      echo "                                            instead of using the published S3 templates."
      echo "                                            Requires one of the --deploy-*-cfn flags."
      echo "  --build-images                            Build images from source (default: $build_images)"
      echo "  --build-chart-and-dashboards              Build Helm chart and dashboards from the local repo"
      echo "                                            instead of downloading from the GitHub release."
      echo "  --version <tag>                           Release version for artifacts to deploy (CFN templates, images,"
      echo "                                            chart, dashboards). Defaults to latest GitHub"
      echo "                                            release. Use 'latest' explicitly to track the newest"
      echo "                                            release artifacts. Required when mixing --build-* flags (e.g."
      echo "                                            --build-cfn without --build-images) to avoid accidental"
      echo "                                            version mismatches between built and downloaded components."
      echo ""
      echo "Examples:"
      echo "  # Deploy new infrastructure with a new VPC and bootstrap:"
      echo "  $0 --deploy-create-vpc-cfn --stack-name MA-Dev --stage dev --region us-east-1"
      echo ""
      echo "  # Deploy into an existing VPC and bootstrap:"
      echo "  $0 --deploy-import-vpc-cfn --stack-name MA-Dev --stage dev \\"
      echo "     --vpc-id vpc-0abc123 --subnet-ids subnet-111,subnet-222 --region us-east-1"
      echo ""
      echo "  # Bootstrap Migration Assistant only (CloudFormation stack already deployed):"
      echo "  $0 --skip-cfn-deploy --stage dev --region us-east-1"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

if [[ "$build_images" == "true" ]]; then
  use_public_images=false
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
  if [[ "$deploy_cfn" == "false" && "$skip_cfn_deploy" == "false" ]]; then
    echo "Error: One of --deploy-create-vpc-cfn, --deploy-import-vpc-cfn, or --skip-cfn-deploy is required." >&2
    echo "  Use --deploy-create-vpc-cfn to create a new VPC and EKS cluster." >&2
    echo "  Use --deploy-import-vpc-cfn to deploy into an existing VPC." >&2
    echo "  Use --skip-cfn-deploy if the stack is already deployed." >&2
    exit 1
  fi
  if [[ "$deploy_cfn" == "true" && "$skip_cfn_deploy" == "true" ]]; then
    echo "Error: --skip-cfn-deploy cannot be combined with --deploy-create-vpc-cfn or --deploy-import-vpc-cfn." >&2
    exit 1
  fi
  if [[ "$build_cfn" == "true" && "$deploy_cfn" == "false" ]]; then
    echo "Error: --build-cfn requires --deploy-create-vpc-cfn or --deploy-import-vpc-cfn." >&2
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
          --query 'RouteTables[0].Routes[?NatGatewayId!=null] | length(@)' --output text 2>/dev/null)
        has_igw=$(aws ec2 describe-route-tables ${region:+--region "$region"} \
          --filters "Name=association.subnet-id,Values=$sid" \
          --query 'RouteTables[0].Routes[?GatewayId!=null && starts_with(GatewayId, `igw-`)] | length(@)' --output text 2>/dev/null)
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
  # Validate repo checkout exists when any --build-* flag requires it
  if [[ "$build_cfn" == "true" || "$build_images" == "true" || "$build_chart_and_dashboards" == "true" ]]; then
    if [[ ! -f "$base_dir/gradlew" ]]; then
      echo "Error: --build-cfn, --build-images, and --build-chart-and-dashboards require a repo checkout." >&2
      echo "  Expected repo root at: $base_dir" >&2
      echo "  Use --base-dir to specify the repo location, or run this script from within the repo." >&2
      exit 1
    fi
  fi
  # When mixing build and download, require --version to avoid accidental mismatches
  local build_count=0
  [[ "$build_cfn" == "true" ]] && ((build_count++)) || true
  [[ "$build_images" == "true" ]] && ((build_count++)) || true
  [[ "$build_chart_and_dashboards" == "true" ]] && ((build_count++)) || true
  if [[ $build_count -gt 0 && $build_count -lt 3 && -z "$version" ]]; then
    echo "Error: --version is required when using some but not all --build-* flags." >&2
    echo "  This prevents version mismatches between built and downloaded components." >&2
    echo "  Use --version latest to track the newest release, or specify a tag like --version 2.6.4" >&2
    exit 1
  fi
}
validate_args

# --- resolve version once ---
if [[ -z "$version" || "$version" == "latest" ]]; then
  RELEASE_VERSION=$(curl -s https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
  RELEASE_VERSION=$(echo "$RELEASE_VERSION" | tr -d '[:space:]')
  [[ -n "$RELEASE_VERSION" ]] || { echo "Error: Could not determine latest release version from GitHub."; exit 1; }
  echo "Resolved latest release version: $RELEASE_VERSION"
else
  RELEASE_VERSION="$version"
  echo "Using specified version: $RELEASE_VERSION"
fi

TOOLS_ARCH=$(uname -m)
case "$TOOLS_ARCH" in
  x86_64 | amd64) TOOLS_ARCH="amd64" ;;
  aarch64 | arm64) TOOLS_ARCH="arm64" ;;
  *) echo "Unsupported architecture: $TOOLS_ARCH"; exit 1 ;;
esac
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
HELM_VERSION="3.14.0"

ma_chart_dir="${base_dir}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo"

install_helm() {
  echo "Installing Helm ${HELM_VERSION} for ${OS}/${TOOLS_ARCH}..."

  tmp_dir=$(mktemp -d)
  cd "$tmp_dir" || exit 1

  curl -LO "https://get.helm.sh/helm-v${HELM_VERSION}-${OS}-${TOOLS_ARCH}.tar.gz"
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
    if [[ -n "$stage_filter" && ! "$name" =~ $stage_filter ]]; then
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

render_dashboard_json() {
  local in_file="$1"
  jq \
    --arg region   "${AWS_CFN_REGION}" \
    --arg account  "${AWS_ACCOUNT}" \
    --arg stage    "${STAGE}" \
    --arg qual     "${MA_QUALIFIER}" \
    '
    # walk() method to traverse and replace tokens (only in strings)
    def walk(f):
      . as $in
      | if type == "object" then
          reduce keys[] as $k (.; .[$k] = ( .[$k] | walk(f) ))
          | f
        elif type == "array" then
          map( walk(f) )
          | f
        else
          f
        end;

    def s:
      if type=="string" then
        .
        | gsub("REGION";        $region)
        | gsub("ACCOUNT_ID";    $account)
        | gsub("MA_STAGE";      $stage)
        | gsub("MA_QUALIFIER";  $qual)
        | gsub("placeholder-region";     $region)
        | gsub("placeholder-account-id"; $account)
        | gsub("placeholder-stage";      $stage)
        | gsub("placeholder-qualifier";  $qual)
      else .
      end;
    walk(s)
    ' "$in_file"
}

deploy_dashboard() {
  local dashboard_name="$1"
  local dashboard_file="$2"

  : "${AWS_CFN_REGION:?AWS_CFN_REGION required}"
  : "${AWS_ACCOUNT:?AWS_ACCOUNT required}"
  : "${STAGE:?STAGE required}"
  : "${MA_QUALIFIER:?MA_QUALIFIER required}"

  echo "Deploying dashboard: ${dashboard_name}"
  [[ -f "$dashboard_file" ]] || { echo "ERROR: dashboard file not found: $dashboard_file"; exit 1; }

  # Render tokens, validate and minify
  local processed_json
  processed_json="$(render_dashboard_json "$dashboard_file")" || { echo "ERROR: failed to render JSON"; exit 1; }

  echo "$processed_json" | jq -e . >/dev/null || {
      echo "ERROR: Invalid JSON for ${dashboard_name} (${dashboard_file})"
      exit 1
  }

  local tmp_json
  tmp_json="$(mktemp)"
  echo "$processed_json" | jq -c . > "$tmp_json"

  # Deterministic dashboard name
  local full_name="MA-${STAGE}-${AWS_CFN_REGION}-${dashboard_name}"
  aws cloudwatch put-dashboard \
    --dashboard-name "$full_name" \
    --dashboard-body "file://${tmp_json}" >/dev/null

  # Validate dashboards on CloudWatch
  if aws cloudwatch get-dashboard --dashboard-name "$full_name" >/dev/null 2>&1; then
    echo "OK: Dashboard available: ${full_name}"
  else
    echo "WARN: Could not read back dashboard: ${full_name}"
  fi

  rm -f "$tmp_json"
}

check_existing_ma_release() {
  local release_name="$1"
  local release_namespace="$2"

  if helm status "$release_name" -n "$release_namespace" >/dev/null 2>&1; then
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

# Check required tools
missing=0
for cmd in jq kubectl; do
  if ! command -v $cmd &>/dev/null; then
    echo "Missing required tool: $cmd"
    missing=1
  fi
done

# Install helm if missing
if ! command -v helm &>/dev/null; then
  echo "Helm is not installed. Installing it now..."
  install_helm
fi

# Exit if any tool was missing and not resolved
[ "$missing" -ne 0 ] && exit 1

# --- CFN deployment (optional) ---
if [[ "$deploy_cfn" == "true" ]]; then
  # Determine template source
  cfn_template_flag=""
  cfn_template_value=""
  if [[ "$build_cfn" == "true" ]]; then
    echo "Building CloudFormation templates from source..."
    # Clear STACK_NAME_SUFFIX so CDK produces predictable template filenames.
    # The stack name is controlled by --stack-name, not by CDK stack IDs.
    # Other CDK env vars (CODE_BUCKET, SOLUTION_NAME, CODE_VERSION) are left
    # intact — they affect template content (AppRegistry names, S3 paths) and
    # the CDK has safe defaults when they're unset.
    STACK_NAME_SUFFIX="" \
      "$base_dir/gradlew" -p "$base_dir" :deployment:migration-assistant-solution:cdkSynthMinified
    cfn_template_flag="--template-body"
    if [[ "$deploy_create_vpc" == "true" ]]; then
      cfn_template_value="file://$base_dir/deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Create-VPC-eks.template.json"
    else
      cfn_template_value="file://$base_dir/deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Import-VPC-eks.template.json"
    fi
  else
    cfn_template_flag="--template-url"
    if [[ "$deploy_create_vpc" == "true" ]]; then
      cfn_template_value="https://solutions-reference.s3.amazonaws.com/migration-assistant-for-amazon-opensearch-service/${RELEASE_VERSION}/migration-assistant-for-amazon-opensearch-service-create-vpc-eks.template"
    else
      cfn_template_value="https://solutions-reference.s3.amazonaws.com/migration-assistant-for-amazon-opensearch-service/${RELEASE_VERSION}/migration-assistant-for-amazon-opensearch-service-import-vpc-eks.template"
    fi
  fi

  # Build parameter overrides (use separate array to handle comma-separated subnet IDs)
  cfn_params=("ParameterKey=Stage,ParameterValue=${stage_filter}")
  if [[ "$deploy_import_vpc" == "true" ]]; then
    cfn_params+=("ParameterKey=VPCId,ParameterValue=${vpc_id}")
    cfn_params+=("ParameterKey=VPCSubnetIds,ParameterValue=\"${subnet_ids}\"")
  fi

  echo "Deploying CloudFormation stack: $cfn_stack_name"
  # create-stack/update-stack to support both --template-file and --template-url
  if aws cloudformation describe-stacks --stack-name "$cfn_stack_name" ${region:+--region "$region"} >/dev/null 2>&1; then
    aws cloudformation update-stack \
      "$cfn_template_flag" "$cfn_template_value" \
      --stack-name "$cfn_stack_name" \
      --parameters "${cfn_params[@]}" \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
      ${region:+--region "$region"} 2>&1 \
      | grep -v "No updates are to be performed" \
      || true
    echo "Waiting for stack update to complete..."
    aws cloudformation wait stack-update-complete \
      --stack-name "$cfn_stack_name" ${region:+--region "$region"} 2>/dev/null || true
  else
    aws cloudformation create-stack \
      "$cfn_template_flag" "$cfn_template_value" \
      --stack-name "$cfn_stack_name" \
      --parameters "${cfn_params[@]}" \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
      ${region:+--region "$region"} \
      || { echo "CloudFormation deployment failed for stack: $cfn_stack_name"; exit 1; }
    echo "Waiting for stack creation to complete..."
    aws cloudformation wait stack-create-complete \
      --stack-name "$cfn_stack_name" ${region:+--region "$region"} \
      || { echo "CloudFormation stack creation failed for: $cfn_stack_name"; exit 1; }
  fi

  echo "CloudFormation stack deployed successfully: $cfn_stack_name"
fi

if ! output=$(get_cfn_export); then
  echo "Unable to find any CloudFormation stacks with a 'MigrationsExportString' export${region:+ in region '$region'}${stage_filter:+ matching stage '$stage_filter'}." >&2
  echo "Has the Migration Assistant CloudFormation template been deployed?" >&2
  if [[ -z "$region" ]]; then
    echo "No --region specified; used default region from AWS CLI config." >&2
  fi
  echo "If the stack is in a different region, re-run with --region <region>." >&2
  echo "To deploy the stack now, re-run with --deploy-create-vpc-cfn or --deploy-import-vpc-cfn." >&2
  exit 1
fi
echo "Setting ENV variables: $output"
eval "$output"

AWS_ACCOUNT="${AWS_ACCOUNT:-$(aws sts get-caller-identity --query Account --output text)}"
AWS_CFN_REGION="${AWS_CFN_REGION:-$(aws configure get region)}"
STAGE="${STAGE:-${MA_STAGE:-dev}}"
MA_QUALIFIER="${MA_QUALIFIER:-${MIGRATIONS_QUALIFIER:-default}}"

# Validate required variables from CFN exports
missing_vars=()
for var in MIGRATIONS_EKS_CLUSTER_NAME MIGRATIONS_ECR_REGISTRY SNAPSHOT_ROLE; do
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

# Show resolved configuration and sources
echo ""
echo "Resolved configuration:"
echo "  Version                = ${RELEASE_VERSION}"
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

aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}"

# --- EKS access entry (optional) ---
if [[ -n "$eks_access_principal_arn" ]]; then
  echo "Configuring EKS access for principal: $eks_access_principal_arn"
  if aws eks describe-access-entry --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
       --principal-arn "$eks_access_principal_arn" ${region:+--region "$region"} >/dev/null 2>&1; then
    echo "Access entry already exists, skipping create."
  else
    aws eks create-access-entry \
      --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
      --principal-arn "$eks_access_principal_arn" \
      --type STANDARD \
      ${region:+--region "$region"}
  fi
  aws eks associate-access-policy \
    --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --principal-arn "$eks_access_principal_arn" \
    --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy \
    --access-scope type=cluster \
    ${region:+--region "$region"}
  echo "EKS access configured for $eks_access_principal_arn"
fi

# Check if general-purpose pool is disabled and re-enable if needed for installation
# Skip this if building images locally, since we'll pre-create general-work-pool
CURRENT_NODEPOOLS=$(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}" \
  --query 'cluster.computeConfig.nodePools' --output text 2>/dev/null)
if [[ "$CURRENT_NODEPOOLS" != *"general-purpose"* ]] && [[ "$build_images" != "true" ]]; then
  echo "general-purpose nodepool is currently disabled."
  echo "Re-enabling it temporarily to allow pod scheduling during installation..."
  NODE_ROLE_ARN=$(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}" \
    --query 'cluster.computeConfig.nodeRoleArn' --output text)
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

kubectl get namespace "$namespace" >/dev/null 2>&1 || kubectl create namespace "$namespace"
kubectl config set-context --current --namespace="$namespace" >/dev/null 2>&1


if [[ "$build_images" == "true" ]]; then
  # Always build for both architectures on EKS
  MULTI_ARCH_NATIVE=true
  BUILD_TARGET="buildImagesToRegistry"
  export MULTI_ARCH_NATIVE

  if docker buildx inspect local-remote-builder --bootstrap &>/dev/null; then
    echo "Buildkit already configured and healthy, skipping setup"
  else
    echo "Setting up buildkit for local builds..."
    "${base_dir}/buildImages/setUpK8sImageBuildServices.sh"
  fi

  echo "Building images to MIGRATIONS_ECR_REGISTRY=$MIGRATIONS_ECR_REGISTRY"
  ecr_domain="${MIGRATIONS_ECR_REGISTRY%%/*}"
  echo "Logging in to ECR registry: $ecr_domain"
  aws ecr get-login-password --region "${AWS_CFN_REGION}" \
    | docker login --username AWS --password-stdin "$ecr_domain" \
    || { echo "ECR login failed"; exit 1; }

  "$base_dir/gradlew" -p "$base_dir" :buildImages:${BUILD_TARGET} -PregistryEndpoint="$MIGRATIONS_ECR_REGISTRY" -x test || exit

  echo "Cleaning up docker buildx builder to free buildkit pods..."
  docker buildx rm local-remote-builder 2>/dev/null || true
  echo "Builder removed. Buildkit pods will be terminated by kubernetes driver."
fi

# --- image source selection ---
# When --build-images is set, images are built from source and pushed to the
# private ECR registry. Otherwise, public images are pulled from
# public.ecr.aws/opensearchproject, tagged with $RELEASE_VERSION.
# To add a new image, add entries to both branches below.
if [[ "$use_public_images" == "false" ]]; then
  IMAGE_FLAGS="\
    --set images.captureProxy.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.captureProxy.tag=migrations_capture_proxy_latest \
    --set images.trafficReplayer.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.trafficReplayer.tag=migrations_traffic_replayer_latest \
    --set images.reindexFromSnapshot.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.reindexFromSnapshot.tag=migrations_reindex_from_snapshot_latest \
    --set images.migrationConsole.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.migrationConsole.tag=migrations_migration_console_latest \
    --set images.installer.repository=${MIGRATIONS_ECR_REGISTRY} \
    --set images.installer.tag=migrations_migration_console_latest"
# Use latest public images
else
  echo "Using public images tagged '$RELEASE_VERSION'"
  IMAGE_FLAGS="\
    --set images.captureProxy.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-capture-proxy \
    --set images.captureProxy.tag=$RELEASE_VERSION \
    --set images.trafficReplayer.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-replayer \
    --set images.trafficReplayer.tag=$RELEASE_VERSION \
    --set images.reindexFromSnapshot.repository=public.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot \
    --set images.reindexFromSnapshot.tag=$RELEASE_VERSION \
    --set images.migrationConsole.repository=public.ecr.aws/opensearchproject/opensearch-migrations-console \
    --set images.migrationConsole.tag=$RELEASE_VERSION \
    --set images.installer.repository=public.ecr.aws/opensearchproject/opensearch-migrations-console \
    --set images.installer.tag=$RELEASE_VERSION"
fi

# --- chart and dashboard source selection ---
# By default, the Helm chart and dashboard JSON files are downloaded from the
# GitHub release matching $RELEASE_VERSION. With --build-chart-and-dashboards,
# they come from the local repo checkout instead.
# To add new release artifacts, add curl downloads here and update
# .github/workflows/release-drafter.yml to publish them.
dashboard_dir="${base_dir}/deployment/k8s/dashboards"
if [[ "$build_chart_and_dashboards" != "true" ]]; then
  RELEASE_BASE_URL="https://github.com/opensearch-project/opensearch-migrations/releases/download/${RELEASE_VERSION}"
  echo "Downloading release artifacts (${RELEASE_VERSION}) from GitHub..."
  curl -fLO "${RELEASE_BASE_URL}/migration-assistant-${RELEASE_VERSION}.tgz" \
    || { echo "Failed to download Helm chart for version ${RELEASE_VERSION}"; exit 1; }
  curl -fLO "${RELEASE_BASE_URL}/capture-replay-dashboard.json" \
    || { echo "Failed to download capture-replay-dashboard.json"; exit 1; }
  curl -fLO "${RELEASE_BASE_URL}/reindex-from-snapshot-dashboard.json" \
    || { echo "Failed to download reindex-from-snapshot-dashboard.json"; exit 1; }
  ma_chart_dir="./migration-assistant-${RELEASE_VERSION}.tgz"
  dashboard_dir="."
fi

# --- helm install ---
# When using packaged chart, valuesEks.yaml is extracted from the tgz since
# helm can't reference files inside an archive. When using the local chart,
# values files are referenced directly. To add new values files, update both paths.
if [[ "$build_chart_and_dashboards" != "true" ]]; then
  tar xzf "${ma_chart_dir}" migration-assistant/valuesEks.yaml migration-assistant/values.yaml
  HELM_VALUES_FLAGS="-f migration-assistant/values.yaml -f migration-assistant/valuesEks.yaml"
else
  HELM_VALUES_FLAGS="-f ${ma_chart_dir}/values.yaml -f ${ma_chart_dir}/valuesEks.yaml"
fi

check_existing_ma_release "$namespace" "$namespace"

echo "Installing Migration Assistant chart now, this can take a couple minutes..."
helm install "$namespace" "${ma_chart_dir}" \
  --namespace $namespace \
  --timeout 10m \
  $HELM_VALUES_FLAGS \
  ${extra_helm_values:+-f "$extra_helm_values"} \
  --set stageName="${STAGE}" \
  --set aws.region="${AWS_CFN_REGION}" \
  --set aws.account="${AWS_ACCOUNT}" \
  --set defaultBucketConfiguration.snapshotRoleArn="${SNAPSHOT_ROLE}" \
  $IMAGE_FLAGS \
  || { echo "Installing Migration Assistant chart failed..."; exit 1; }

echo "Deploying CloudWatch dashboards..."
deploy_dashboard "CaptureReplay" "${dashboard_dir}/capture-replay-dashboard.json"
deploy_dashboard "ReindexFromSnapshot" "${dashboard_dir}/reindex-from-snapshot-dashboard.json"
echo "All dashboards deployed to CloudWatch"

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
  kubectl -n "$namespace" wait --for=condition=ready pod/migration-console-0 --timeout=300s
  cmd="kubectl -n $namespace exec --stdin --tty migration-console-0 -- /bin/bash"
  echo "Accessing migration console with command: $cmd"
  eval "$cmd"
fi
