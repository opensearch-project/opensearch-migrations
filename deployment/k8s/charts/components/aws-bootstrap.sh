#!/bin/bash
# Execute directly
# curl -s https://raw.githubusercontent.com/lewijacn/opensearch-migrations/build-images-in-k8s/deployment/k8s/charts/components/aws-bootstrap.sh | bash
# Store to file, then execute with ./aws-bootstrap.sh
# curl -s -o aws-bootstrap.sh https://raw.githubusercontent.com/lewijacn/opensearch-migrations/build-images-in-k8s/deployment/k8s/charts/components/aws-bootstrap.sh && chmod +x aws-bootstrap.sh

project_name="lewijacn"
repo_name="opensearch-migrations"
bootstrap_chart_path="deployment/k8s/charts/components/bootstrapK8s"
bootstrap_templates_path="deployment/k8s/charts/components/bootstrapK8s/templates"
local_chart_dir="bootstrapChart"
branch="build-images-in-k8s"
namespace="ma"
skip_image_build=false
keep_job_alive=false

ARCH=$(uname -m)
case "$ARCH" in
  x86_64) ARCH="amd64" ;;
  aarch64) ARCH="arm64" ;;
  *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
HELM_VERSION="3.14.0"

install_helm() {
  echo "Installing Helm ${HELM_VERSION} for ${OS}/${ARCH}..."

  tmp_dir=$(mktemp -d)
  cd "$tmp_dir" || exit 1

  curl -LO "https://get.helm.sh/helm-v${HELM_VERSION}-${OS}-${ARCH}.tar.gz"
  tar -zxvf "helm-v${HELM_VERSION}-${OS}-${ARCH}.tar.gz"
  sudo mv "${OS}-${ARCH}/helm" /usr/local/bin/helm
  cd - >/dev/null || exit 1
  rm -rf "$tmp_dir"

  echo "Helm installed successfully."
}

# Check required tools
missing=0
for cmd in kubectl jq; do
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

key="MigrationsExportString"
output=$(aws cloudformation describe-stacks \
  --query "Stacks[?Outputs[?OutputKey=='$key']].[Outputs[?OutputKey=='$key'].OutputValue | [0], CreationTime]" \
  --output text | sort -k2 | tail -n1 | cut -f1)

if [[ -z "$output" ]]; then
  echo "Error: No stack output found for OutputKey: $key"
  exit 1
fi
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

DEFAULT_SC=$(kubectl get storageclass gp3 --no-headers --ignore-not-found | awk '{print $1}')
if [ -z "$DEFAULT_SC" ]; then
  DEFAULT_SC=$(kubectl get storageclass gp2 --no-headers --ignore-not-found | awk '{print $1}')
fi

if [ -n "$DEFAULT_SC" ]; then
  kubectl patch storageclass "$DEFAULT_SC" -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
else
  echo "Neither gp2 nor gp3 StorageClass found."
  exit 1
fi

# Get list of files in the target directory from GitHub API
chart_file_list=$(curl -s "https://api.github.com/repos/${project_name}/${repo_name}/contents/${bootstrap_chart_path}?ref=${branch}" | jq -r '.[] | select(.type=="file") | .name')
template_file_list=$(curl -s "https://api.github.com/repos/${project_name}/${repo_name}/contents/${bootstrap_templates_path}?ref=${branch}" | jq -r '.[] | select(.type=="file") | .name')
mkdir -p $local_chart_dir/templates

# Download each file using raw.githubusercontent.com
echo "Downloading bootstrap chart..."
for file in $chart_file_list; do
  raw_url="https://raw.githubusercontent.com/${project_name}/${repo_name}/${branch}/${bootstrap_chart_path}/${file}"
  curl -s -o "./${local_chart_dir}/${file}" "$raw_url"
done
for file in $template_file_list; do
  raw_url="https://raw.githubusercontent.com/${project_name}/${repo_name}/${branch}/${bootstrap_templates_path}/${file}"
  curl -s -o "./${local_chart_dir}/templates/${file}" "$raw_url"
done

if helm status bootstrap-ma -n "$namespace" >/dev/null 2>&1; then
  echo "Helm release 'bootstrap-ma' already exists in namespace '$namespace'. This can be uninstalled with 'helm uninstall bootstrap-ma -n $namespace'"
  exit 1
fi
helm install bootstrap-ma "${local_chart_dir}" \
  --namespace "$namespace" \
  --set cloneRepository=true \
  --set efsVolumeHandle="${MIGRATIONS_EFS_ID}" \
  --set registryEndpoint="${MIGRATIONS_ECR_REGISTRY}" \
  --set awsRegion="${AWS_CFN_REGION}" \
  --set skipImageBuild="${skip_image_build}" \
  --set keepJobAlive="${keep_job_alive}"

if [[ "$skip_image_build" == "true" || "$keep_job_alive" == "true" ]]; then
  echo "Either skipImageBuild or keepJobAlive was enabled, no pod monitoring needed"
  exit 0
fi

pod_name=$(kubectl get pods -n "$namespace" --sort-by=.metadata.creationTimestamp --no-headers | awk '/^bootstrap-k8s/ { pod=$1 } END { print pod }')
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

if [[ "$final_status" == "Succeeded" ]]; then
  kubectl -n ma run migration-console --image="${MIGRATIONS_ECR_REGISTRY}:migrations_migration_console_latest" --restart=Never
  kubectl -n ma wait --for=condition=ready pod/migration-console --timeout=300s
  sleep 5
  cmd="kubectl -n $namespace exec --stdin --tty migration-console -- /bin/bash"
  echo "Accessing migration console with command: $cmd"
  eval "$cmd"
else
  echo "Pod $pod_name did not end with 'Succeeded'. Exiting..."
  exit 1
fi