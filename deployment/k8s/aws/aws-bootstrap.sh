#!/bin/bash
# -----------------------------------------------------------------------------
# Bootstrap EKS Environment for OpenSearch Migration Assistant
#
# This script prepares an existing EKS cluster to use the Migration Assistant tooling.
# By default, public images will be used from https://gallery.ecr.aws/opensearchproject.
# Images can also be built from source locally with the --build-images-locally flag.
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

base_dir="./opensearch-migrations"
namespace="ma"
build_images_locally=false
use_public_images=true
skip_console_exec=false
stage_filter=""
extra_helm_values=""
disable_general_purpose_pool=false
region="${AWS_CFN_REGION:-}"

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
    --build-images-locally) build_images_locally=true; shift 1 ;;
    --skip-console-exec) skip_console_exec=true; shift 1 ;;
    --stage) stage_filter="$2"; shift 2 ;;
    --helm-values) extra_helm_values="$2"; shift 2 ;;
    --disable-general-purpose-pool) disable_general_purpose_pool=true; shift 1 ;;
    --region) region="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo "Options:"
      echo "  --org-name <val>                          (default: $org_name)"
      echo "  --repo-name <val>                         (default: $repo_name)"
      echo "  --branch <val>                            (default: $branch)"
      echo "  --tag <val>                               (default: $tag)"
      echo "  --skip-git-pull                           (default: $skip_git_pull)"
      echo "  --base-dir <path>                         (default: $base_dir)"
      echo "  --ma-chart-dir <path>                     (default: $ma_chart_dir)"
      echo "  --namespace <val>                         (default: $namespace)"
      echo "  --build-images-locally                    Build images from source locally (default: $build_images_locally)"
      echo "  --skip-console-exec                       (default: $skip_console_exec)"
      echo "  --stage <val>                             Filter CFN exports by stage name"
      echo "  --helm-values <path>                      Extra values file for helm install"
      echo "  --disable-general-purpose-pool            Disable EKS Auto Mode general-purpose pool (cost control)"
      echo "  --region <val>                            AWS region for CloudFormation exports lookup"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

if [[ "$build_images_locally" == "true" ]]; then
  use_public_images=false
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

if ! output=$(get_cfn_export); then
  echo "Unable to find any CloudFormation stacks in the current region which have an output that starts with '$prefix'. \
Has the Migration Assistant CloudFormation template been deployed?" >&2
  echo "If the stack is in a different region, re-run with --region <region>." >&2
  exit 1
fi
echo "Setting ENV variables: $output"
eval "$output"

AWS_ACCOUNT="${AWS_ACCOUNT:-$(aws sts get-caller-identity --query Account --output text)}"
AWS_CFN_REGION="${AWS_CFN_REGION:-$(aws configure get region)}"
STAGE="${STAGE:-${MA_STAGE:-dev}}"
MA_QUALIFIER="${MA_QUALIFIER:-${MIGRATIONS_QUALIFIER:-default}}"

aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}"

# Check if general-purpose pool is disabled and re-enable if needed for installation
# Skip this if building images locally, since we'll pre-create general-work-pool
CURRENT_NODEPOOLS=$(aws eks describe-cluster --name "${MIGRATIONS_EKS_CLUSTER_NAME}" --region "${AWS_CFN_REGION}" \
  --query 'cluster.computeConfig.nodePools' --output text 2>/dev/null)
if [[ "$CURRENT_NODEPOOLS" != *"general-purpose"* ]] && [[ "$build_images_locally" != "true" ]]; then
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


if [[ "$build_images_locally" == "true" ]]; then
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
  pushd "$base_dir" || exit
  ./gradlew :buildImages:${BUILD_TARGET} -PregistryEndpoint="$MIGRATIONS_ECR_REGISTRY" -x test || exit
  popd > /dev/null || exit

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

# Install Mountpoint S3 CSI Driver v2 addon (required for S3-backed PVs)
if aws eks describe-addon --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" --addon-name aws-mountpoint-s3-csi-driver --region "${AWS_CFN_REGION}" >/dev/null 2>&1; then
  echo "S3 CSI Driver addon already installed, skipping"
else
  echo "Installing Mountpoint S3 CSI Driver v2 addon..."
  migrations_role_arn="arn:aws:iam::${AWS_ACCOUNT}:role/${MIGRATIONS_EKS_CLUSTER_NAME}-migrations-role"
  aws eks create-addon \
    --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --addon-name aws-mountpoint-s3-csi-driver \
    --region "${AWS_CFN_REGION}" \
    --resolve-conflicts OVERWRITE \
    --pod-identity-associations "serviceAccount=s3-csi-driver-sa,roleArn=${migrations_role_arn}"
  echo "Waiting for S3 CSI Driver addon to become active..."
  if aws eks wait addon-active \
    --cluster-name "${MIGRATIONS_EKS_CLUSTER_NAME}" \
    --addon-name aws-mountpoint-s3-csi-driver \
    --region "${AWS_CFN_REGION}"; then
    echo "✅  S3 CSI Driver v2 addon installed"
  else
    echo "⚠️  S3 CSI Driver addon did not become active — S3-backed volumes may not work"
  fi
fi

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
