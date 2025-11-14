#!/bin/bash
# -----------------------------------------------------------------------------
# Bootstrap EKS Environment for OpenSearch Migration Assistant
#
# This script prepares an existing EKS cluster to use the Migration Assistant tooling.
# As a default public images will be used from https://gallery.ecr.aws/opensearchproject.
# However, images can also be built from source with the --build-images=true flag
# which kicks-off a buildImages chart which will build required images for the
# Migration Assistant inside of an EKS pod and push these images to a private ECR for use.
#
# Usage:
#   Run directly: curl -s https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/main/deployment/k8s/aws/aws-bootstrap.sh | bash
#   Save & run:   curl -s -o aws-bootstrap.sh https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/main/deployment/k8s/aws/aws-bootstrap.sh && chmod +x aws-bootstrap.sh && ./aws-bootstrap.sh
# -----------------------------------------------------------------------------
# --- defaults ---
org_name="opensearch-project"
repo_name="opensearch-migrations"
branch="main"
tag=""
skip_git_pull=false

base_dir="./opensearch-migrations"
namespace="ma"
build_images=false
keep_build_images_job_alive=false
use_public_images=true
skip_console_exec=false

# --- argument parsing ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --org-name) org_name="$2"; shift 2 ;;
    --repo-name) repo_name="$2"; shift 2 ;;
    --branch) branch="$2"; shift 2 ;;
    --tag) tag="$2"; shift 2 ;;
    --skip-git-pull) skip_git_pull=true; shift 1 ;;
    --base-dir) base_dir="$2"; shift 2 ;;
    --build-images-chart-dir) build_images_chart_dir="$2"; shift 2 ;;
    --ma-chart-dir) ma_chart_dir="$2"; shift 2 ;;
    --namespace) namespace="$2"; shift 2 ;;
    --build-images) build_images="$2"; shift 2 ;;
    --use-public-images) use_public_images="$2"; shift 2 ;;
    --keep-build-images-job-alive) keep_build_images_job_alive=true; shift 1 ;;
    --skip-console-exec) skip_console_exec=true; shift 1 ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo "Options:"
      echo "  --org-name <val>                          (default: $org_name)"
      echo "  --repo-name <val>                         (default: $repo_name)"
      echo "  --branch <val>                            (default: $branch)"
      echo "  --tag <val>                               (default: $tag)"
      echo "  --skip-git-pull                           (default: $skip_git_pull)"
      echo "  --base-dir <path>                         (default: $base_dir)"
      echo "  --build-images-chart-dir <path>           (default: $build_images_chart_dir)"
      echo "  --ma-chart-dir <path>                     (default: $ma_chart_dir)"
      echo "  --namespace <val>                         (default: $namespace)"
      echo "  --build-images <true|false>               (default: $build_images)"
      echo "  --use-public-images <true|false>          (default: $use_public_images)"
      echo "  --keep-build-images-job-alive             (default: $keep_build_images_job_alive)"
      echo "  --skip-console-exec                       (default: $skip_console_exec)"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# --- validation ---
if [[ "$build_images" == "true" && "$use_public_images" == "true" ]]; then
  echo "Note: --build-images is enabled, so public images will NOT be used."
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

build_images_chart_dir="${base_dir}/deployment/k8s/charts/components/buildImages"
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
    names+=("$name")
    values+=("$value")
  done < <(aws cloudformation list-exports \
    --query "Exports[?starts_with(Name, \`${prefix}\`)].[Name,Value]" \
    --output text)

  if [ ${#names[@]} -eq 0 ]; then
    echo "Error: No exports found starting with '$prefix'" >&2
    return 1
  elif [ ${#names[@]} -eq 1 ]; then
    echo "${values[0]}"
  else
    echo "Multiple Cloudformation stacks with migration exports found:" >&2
    for i in "${!names[@]}"; do
      echo "[$i] ${names[$i]}" >&2
    done
    read -rp "Select the stack export name to use (0-$((${#names[@]} - 1))): " choice
    if [[ ! "$choice" =~ ^[0-9]+$ || "$choice" -ge ${#names[@]} ]]; then
      echo "Invalid choice." >&2
      return 1
    fi
    echo "${values[$choice]}"
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

# Check required tools
missing=0
for cmd in git jq kubectl; do
  if ! command -v $cmd &>/dev/null; then
    echo "Missing required tool: $cmd"
    missing=1
  fi
done

# Prompt for installing helm
if ! command -v helm &>/dev/null; then
  echo "Helm is not installed."
  read -rp "Would you like to install Helm now? (y/n): " answer
  if [[ "$answer" == [Yy]* ]]; then
    install_helm
  else
    echo "Helm is required. Exiting."
    missing=1
  fi
fi

# Exit if any tool was missing and not resolved
[ "$missing" -ne 0 ] && exit 1

if ! output=$(get_cfn_export); then
  echo "Unable to find any CloudFormation stacks in the current region which have an output that starts with '$prefix'. \
        Has the Migration Assistant CloudFormation template been deployed?" >&2
  exit 1
fi
echo "Setting ENV variables: $output"
eval "$output"

AWS_ACCOUNT="${AWS_ACCOUNT:-$(aws sts get-caller-identity --query Account --output text)}"
AWS_CFN_REGION="${AWS_CFN_REGION:-$(aws configure get region)}"
STAGE="${STAGE:-${MA_STAGE:-dev}}"
MA_QUALIFIER="${MA_QUALIFIER:-${MIGRATIONS_QUALIFIER:-default}}"

aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}"

kubectl get namespace "$namespace" >/dev/null 2>&1 || kubectl create namespace "$namespace"
kubectl config set-context --current --namespace="$namespace" >/dev/null 2>&1

if ! helm status aws-efs-csi-driver -n kube-system >/dev/null 2>&1; then
  echo "Installing aws-efs-csi-driver Helm chart..."
  helm repo add aws-efs-csi-driver https://kubernetes-sigs.github.io/aws-efs-csi-driver
  helm repo update
  helm upgrade --install aws-efs-csi-driver aws-efs-csi-driver/aws-efs-csi-driver \
    --namespace kube-system \
    --set image.repository=602401143452.dkr.ecr.us-west-1.amazonaws.com/eks/aws-efs-csi-driver \
    --set image.tag=v2.1.8
else
  echo "aws-efs-csi-driver Helm release already exists. Skipping install."
fi

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

if [[ "$build_images" == "true" ]]; then
  if helm status build-images -n "$namespace" >/dev/null 2>&1; then
    read -rp "Helm release 'build-images' already exists in namespace '$namespace', would you like to uninstall it? (y/n): " answer
    if [[ "$answer" == [Yy]* ]]; then
      helm uninstall build-images -n "$namespace"
      sleep 2
    else
      echo "The 'build-images' release must be uninstalled before proceeding. This can be uninstalled with 'helm uninstall build-images -n $namespace'"
      exit 1
    fi
  fi
  helm install build-images "${build_images_chart_dir}" \
    --namespace "$namespace" \
    --set registryEndpoint="${MIGRATIONS_ECR_REGISTRY}" \
    --set awsEKSEnabled=true \
    --set keepJobAlive="${keep_build_images_job_alive}" \
    --set repositoryUrl="https://github.com/${org_name}/${repo_name}.git" \
    --set repositoryBranch="${branch}" \
    || { echo "Installing buildImages chart failed..."; exit 1; }

  if [[ "$keep_build_images_job_alive" == "true" ]]; then
    echo "The keep_build_images_job_alive setting was enabled, will not proceed with installing the Migration Assistant chart"
    exit 0
  fi
  pod_name=$(kubectl get pod -n "$namespace" -o name | grep '^pod/build-images' | cut -d/ -f2)
  echo "Waiting for pod ${pod_name} to be ready..."
  kubectl -n "$namespace" wait --for=condition=ready pod/"$pod_name" --timeout=300s
  sleep 5
  echo "Tailing logs for ${pod_name}..."
  echo "-------------------------------"
  kubectl -n "$namespace" logs -f "$pod_name"
  echo "-------------------------------"
  sleep 5
  final_status=$(kubectl get pod "$pod_name" -n "$namespace" -o jsonpath='{.status.phase}')
  echo "Pod ${pod_name} ended with status: ${final_status}"
  if [[ "$final_status" != "Succeeded" ]]; then
    echo "The $pod_name pod did not end with 'Succeeded'. Exiting..."
    exit 1
  else
    echo "Uninstalling buildImages chart after successful setup"
    helm uninstall build-images -n "$namespace"
  fi
fi

RELEASE_VERSION=$(<"$base_dir/VERSION")
RELEASE_VERSION=$(echo "$RELEASE_VERSION" | tr -d '[:space:]')

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

echo "Installing Migration Assistant chart now, this can take a couple minutes..."
helm install "$namespace" "${ma_chart_dir}" \
  --namespace $namespace \
  -f "${ma_chart_dir}/values.yaml" \
  -f "${ma_chart_dir}/valuesEks.yaml" \
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

if [[ "$skip_console_exec" == "false" ]]; then
  kubectl -n "$namespace" wait --for=condition=ready pod/migration-console-0 --timeout=300s
  cmd="kubectl -n $namespace exec --stdin --tty migration-console-0 -- /bin/bash"
  echo "Accessing migration console with command: $cmd"
  eval "$cmd"
fi