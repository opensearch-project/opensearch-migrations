#!/bin/bash
# -----------------------------------------------------------------------------
# Bootstrap EKS Environment for OpenSearch Migration Assistant
#
# This script prepares an existing EKS cluster to use the Migration Assistant tooling.
# If desired it has the ability to kick-off a buildImages chart which will build required
# images for the Migration Assistant inside of an EKS pod and push these images to a
# private ECR, otherwise this step can be skipped with the 'skip_image_build' flag and
# public images can be utilized
#
# Usage:
#   Run directly: curl -s https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/main/deployment/k8s/aws/aws-bootstrap.sh | bash
#   Save & run:   curl -s -o aws-bootstrap.sh https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/main/deployment/k8s/aws/aws-bootstrap.sh && chmod +x aws-bootstrap.sh && ./aws-bootstrap.sh
# -----------------------------------------------------------------------------
org_name="opensearch-project"
repo_name="opensearch-migrations"
branch="main"
tag=""
skip_git_pull=false

base_dir="./opensearch-migrations"
build_images_chart_dir="${base_dir}/deployment/k8s/charts/components/buildImages"
ma_chart_dir="${base_dir}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo"
namespace="ma"
skip_image_build=false
keep_build_images_job_alive=false

TOOLS_ARCH=$(uname -m)
case "$TOOLS_ARCH" in
  x86_64 | amd64) TOOLS_ARCH="amd64" ;;
  aarch64 | arm64) TOOLS_ARCH="arm64" ;;
  *) echo "Unsupported architecture: $TOOLS_ARCH"; exit 1 ;;
esac
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
HELM_VERSION="3.14.0"

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
    exit 1
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
      exit 1
    fi
    echo "${values[$choice]}"
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

output=$(get_cfn_export)
echo "Setting ENV variables: $output"
eval "$output"

aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}"

kubectl get namespace ma >/dev/null 2>&1 || kubectl create namespace ma

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

if [[ "$skip_image_build" == "false" ]]; then
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
    || { echo "Installing buildImages chart failed..."; exit 1; }

  if [[ "$keep_build_images_job_alive" == "true" ]]; then
    echo "The keep_build_images_job_alive setting was enabled, will not proceed with installing the Migration Assistant chart"
    exit 0
  fi
  pod_name=$(kubectl get pod -n "$namespace" -o name | grep '^pod/build-images' | cut -d/ -f2)
  echo "Waiting for pod ${pod_name} to be ready..."
  kubectl -n ma wait --for=condition=ready pod/"$pod_name" --timeout=300s
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

echo "Installing Migration Assistant chart now, this can take a couple minutes..."
helm install ma "${ma_chart_dir}" \
  --namespace $namespace \
  -f "${ma_chart_dir}/values.yaml" \
  -f "${ma_chart_dir}/valuesEks.yaml" \
  --set stageName="${STAGE}" \
  --set aws.region="${AWS_CFN_REGION}" \
  --set aws.account="${AWS_ACCOUNT}" \
  --set images.captureProxy.repository="${MIGRATIONS_ECR_REGISTRY}" \
  --set images.captureProxy.tag=migrations_capture_proxy_latest \
  --set images.trafficReplayer.repository="${MIGRATIONS_ECR_REGISTRY}" \
  --set images.trafficReplayer.tag=migrations_traffic_replayer_latest \
  --set images.reindexFromSnapshot.repository="${MIGRATIONS_ECR_REGISTRY}" \
  --set images.reindexFromSnapshot.tag=migrations_reindex_from_snapshot_latest \
  --set images.migrationConsole.repository="${MIGRATIONS_ECR_REGISTRY}" \
  --set images.migrationConsole.tag=migrations_migration_console_latest \
  --set images.installer.repository="${MIGRATIONS_ECR_REGISTRY}" \
  --set images.installer.tag=migrations_migration_console_latest \
  || { echo "Installing Migration Assistant chart failed..."; exit 1; }

kubectl -n ma wait --for=condition=ready pod/migration-console-0 --timeout=300s
cmd="kubectl -n $namespace exec --stdin --tty migration-console-0 -- /bin/bash"
echo "Accessing migration console with command: $cmd"
eval "$cmd"