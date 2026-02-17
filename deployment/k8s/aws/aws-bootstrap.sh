#!/bin/bash
# -----------------------------------------------------------------------------
# Bootstrap EKS Environment for OpenSearch Migration Assistant
#
# This script provisions an EKS cluster with Cfn to use the Migration Assistant tooling.
# By default, public images will be used from https://gallery.ecr.aws/opensearchproject.
# Images can also be built from source locally with the --build-images flag.
#
# Usage:
#   Run directly: curl -s https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/main/deployment/k8s/aws/aws-bootstrap.sh | bash
#   Save & run:   curl -s -o aws-bootstrap.sh https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/main/deployment/k8s/aws/aws-bootstrap.sh && chmod +x aws-bootstrap.sh && ./aws-bootstrap.sh
# -----------------------------------------------------------------------------

set -euo pipefail

# --- defaults ---
org_name="opensearch-project"
repo_name="opensearch-migrations"
branch="main"
tag=""
skip_git_pull=false

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

# --- argument parsing ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --org-name) org_name="$2"; shift 2 ;;
    --repo-name) repo_name="$2"; shift 2 ;;
    --branch) branch="$2"; shift 2 ;;
    --tag) tag="$2"; shift 2 ;;
    --skip-git-pull) skip_git_pull=true; shift 1 ;;
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
    -h|--help)
      echo "Usage: $0 [options]"
      echo ""
      echo "Bootstrap an EKS cluster for the OpenSearch Migration Assistant."
      echo "Optionally deploy the Migration Assistant CloudFormation stack first."
      echo ""
      echo "Git options:"
      echo "  --org-name <val>                          GitHub org for git checkout (default: $org_name)"
      echo "  --repo-name <val>                         GitHub repo for git checkout (default: $repo_name)"
      echo "  --branch <val>                            Git branch for git checkout (default: $branch)"
      echo "  --tag <val>                               Git tag for git checkout (default: none)"
      echo "  --skip-git-pull                           Use existing local repo (default: $skip_git_pull)"
      echo ""
      echo "General options:"
      echo "  --base-dir <path>                         Repo directory (default: git repo root, or ./opensearch-migrations if cloning)"
      echo "  --build-images                            Build images from source (default: $build_images)"
      echo ""
      echo "CloudFormation deployment options (exactly one required):"
      echo "  --deploy-create-vpc-cfn                   Deploy the Create-VPC EKS CloudFormation template."
      echo "                                            Creates a new VPC with all required networking."
      echo "  --deploy-import-vpc-cfn                   Deploy the Import-VPC EKS CloudFormation template."
      echo "                                            Uses an existing VPC — requires --vpc-id and --subnet-ids."
      echo "  --skip-cfn-deploy                         Skip CloudFormation deployment. Use when the Migration"
      echo "                                            Assistant stack is already deployed and you only want to"
      echo "                                            bootstrap the EKS cluster (install helm chart, images, etc)."
      echo "  --build-cfn                               Build CFN templates from source (gradle cdkSynthMinified)"
      echo "                                            instead of using the published S3 templates."
      echo "                                            Requires one of the --deploy-*-cfn flags."
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
      echo "  --disable-general-purpose-pool            Disable EKS Auto Mode general-purpose pool (cost control)"
      echo "  --stage <val>                             Stage name for CFN exports filter and CFN Stage parameter (default: dev)"
      echo "  --region <val>                            AWS region"
      echo "  --skip-console-exec                       Don't exec into console pod (default: $skip_console_exec)"
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
  if [[ "$skip_git_pull" == "true" ]]; then
    # Script lives at deployment/k8s/aws/aws-bootstrap.sh — repo root is three levels up
    base_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
  else
    base_dir="./opensearch-migrations"
  fi
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
      aws ec2 describe-subnets ${region:+--region "$region"} \
        --filters "Name=vpc-id,Values=$vpc_id" \
        --query 'Subnets[].{ID:SubnetId,AZ:AvailabilityZone,CIDR:CidrBlock,Public:MapPublicIpOnLaunch}' \
        --output table >&2 || true
      echo "" >&2
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
}
validate_args

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
for cmd in git jq kubectl; do
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

# --- git pull (before CFN deploy, which may need the repo for --build-cfn) ---
if [[ "$skip_git_pull" == "false" ]]; then
  echo "Preparing opensearch-migrations repo..."
  mkdir -p $base_dir
  pushd "$base_dir" > /dev/null || exit
  git init > /dev/null
  git remote | grep -q "^origin$" || git remote add -f origin "https://github.com/${org_name}/${repo_name}.git"
  git fetch > /dev/null
  if [ -n "$branch" ]; then
    git checkout $branch
  elif [ -n "$tag" ]; then
    git checkout tags/"$tag"
  else
    latest_release_tag=$(curl -s https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest | jq -r ".tag_name")
    git checkout tags/"$latest_release_tag"
  fi
  echo "Using opensearch-migrations repo at commit point:"
  echo "-------------------------------"
  git show -s --format="%H%n%an <%ae>%n%ad%n%s" HEAD
  echo "-------------------------------"
  popd > /dev/null || exit
fi

# --- CFN deployment (optional) ---
if [[ "$deploy_cfn" == "true" ]]; then
  # Determine template source
  cfn_template_arg=""
  if [[ "$build_cfn" == "true" ]]; then
    echo "Building CloudFormation templates from source..."
    "$base_dir/gradlew" -p "$base_dir" :deployment:migration-assistant-solution:cdkSynthMinified
    if [[ "$deploy_create_vpc" == "true" ]]; then
      cfn_template_arg="--template-file $base_dir/deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Create-VPC-eks.template.json"
    else
      cfn_template_arg="--template-file $base_dir/deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Import-VPC-eks.template.json"
    fi
  else
    if [[ "$deploy_create_vpc" == "true" ]]; then
      s3_url="https://solutions-reference.s3.amazonaws.com/migration-assistant-for-amazon-opensearch-service/latest/migration-assistant-for-amazon-opensearch-service-create-vpc-eks.template"
    else
      s3_url="https://solutions-reference.s3.amazonaws.com/migration-assistant-for-amazon-opensearch-service/latest/migration-assistant-for-amazon-opensearch-service-import-vpc-eks.template"
    fi
    cfn_template_arg="--template-url $s3_url"
  fi

  # Build parameter overrides
  cfn_params="ParameterKey=Stage,ParameterValue=${stage_filter}"
  if [[ "$deploy_import_vpc" == "true" ]]; then
    cfn_params="$cfn_params ParameterKey=VPCId,ParameterValue=${vpc_id} ParameterKey=VPCSubnetIds,ParameterValue=${subnet_ids}"
  fi

  echo "Deploying CloudFormation stack: $cfn_stack_name"
  # create-stack/update-stack to support both --template-file and --template-url
  if aws cloudformation describe-stacks --stack-name "$cfn_stack_name" ${region:+--region "$region"} >/dev/null 2>&1; then
    aws cloudformation update-stack \
      $cfn_template_arg \
      --stack-name "$cfn_stack_name" \
      --parameters $cfn_params \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
      ${region:+--region "$region"} 2>&1 \
      | grep -v "No updates are to be performed" \
      || true
    echo "Waiting for stack update to complete..."
    aws cloudformation wait stack-update-complete \
      --stack-name "$cfn_stack_name" ${region:+--region "$region"} 2>/dev/null || true
  else
    aws cloudformation create-stack \
      $cfn_template_arg \
      --stack-name "$cfn_stack_name" \
      --parameters $cfn_params \
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
  "$base_dir/gradlew" -p "$base_dir" :buildImages:${BUILD_TARGET} -PregistryEndpoint="$MIGRATIONS_ECR_REGISTRY" -x test || exit

  echo "Cleaning up docker buildx builder to free buildkit pods..."
  docker buildx rm local-remote-builder 2>/dev/null || true
  echo "Builder removed. Buildkit pods will be terminated by kubernetes driver."
fi

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
  # Get latest release version from GitHub releases API
  RELEASE_VERSION=$(curl -s https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
  if [[ -z "$RELEASE_VERSION" ]]; then
    echo "Warning: Could not fetch latest release from GitHub, falling back to VERSION file"
    RELEASE_VERSION=$(<"$base_dir/VERSION")
  fi
  RELEASE_VERSION=$(echo "$RELEASE_VERSION" | tr -d '[:space:]')
  echo "Using RELEASE_VERSION=$RELEASE_VERSION"

  echo "Using release version tag '$RELEASE_VERSION' for all Migration Assistant images"
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

check_existing_ma_release "$namespace" "$namespace"

echo "Installing Migration Assistant chart now, this can take a couple minutes..."
helm install "$namespace" "${ma_chart_dir}" \
  --namespace $namespace \
  -f "${ma_chart_dir}/values.yaml" \
  -f "${ma_chart_dir}/valuesEks.yaml" \
  ${extra_helm_values:+-f "$extra_helm_values"} \
  --set stageName="${STAGE}" \
  --set aws.region="${AWS_CFN_REGION}" \
  --set aws.account="${AWS_ACCOUNT}" \
  --set defaultBucketConfiguration.snapshotRoleArn="${SNAPSHOT_ROLE}" \
  $IMAGE_FLAGS \
  || { echo "Installing Migration Assistant chart failed..."; exit 1; }

echo "Deploying CloudWatch dashboards..."
deploy_dashboard "CaptureReplay" "${base_dir}/deployment/k8s/dashboards/capture-replay-dashboard.json"
deploy_dashboard "ReindexFromSnapshot" "${base_dir}/deployment/k8s/dashboards/reindex-from-snapshot-dashboard.json"
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
