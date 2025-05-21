#!/bin/bash
# curl -s https://raw.githubusercontent.com/lewijacn/opensearch-migrations/build-images-in-k8s/deployment/k8s/charts/components/aws-bootstrap.sh | bash

project_name="lewijacn"
repo_name="opensearch-migrations"
bootstrap_chart_path="deployment/k8s/charts/components/bootstrapK8s"
bootstrap_templates_path="deployment/k8s/charts/components/bootstrapK8s/templates"
local_chart_dir="bootstrapChart"
branch="build-images-in-k8s"
namespace="ma"

# Check required tools are installed
for cmd in kubectl helm jq; do
  command -v $cmd &>/dev/null || { echo "Missing required tool: $cmd"; missing=1; }
done
[ "$missing" ] && exit 1

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

helm repo add aws-efs-csi-driver https://kubernetes-sigs.github.io/aws-efs-csi-driver
helm repo update
helm upgrade --install aws-efs-csi-driver aws-efs-csi-driver/aws-efs-csi-driver --namespace kube-system --set image.repository=602401143452.dkr.ecr.us-west-1.amazonaws.com/eks/aws-efs-csi-driver --set image.tag=v2.1.8

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

helm upgrade --install bootstrap-ma ${local_chart_dir} --namespace "$namespace" --set cloneRepository=true --set efsVolumeHandle="${MIGRATIONS_EFS_ID}" --set registryEndpoint="${MIGRATIONS_ECR_REGISTRY}" --set awsRegion="${AWS_CFN_REGION}"

pod_name=$(kubectl get pods -n "$namespace" --sort-by=.metadata.creationTimestamp --no-headers | awk '/^bootstrap-k8s/ { pod=$1 } END { print pod }')
timeout=1200
elapsed=0
interval=5

echo "Waiting for bootstrap job to complete..."
while (( elapsed < timeout )); do
  status=$(kubectl get pod "$pod_name" -n "$namespace" -o jsonpath='{.status.phase}' 2>/dev/null)

  if [[ -z "$status" ]]; then
    echo "Pod $pod_name not found. Waiting..."
  elif [[ "$status" == "Succeeded" || "$status" == "Failed" ]]; then
    break
  else
    echo "Pod is currently in '$status' status"
  fi

  sleep $interval
  (( elapsed += interval ))
done

if [[ "$status" == "Succeeded" ]]; then
  cmd="kubectl -n $namespace exec --stdin --tty $pod_name -- /bin/bash"
  echo "Pod $pod_name has completed successfully. Entering migration console with command: $cmd"
  eval "$cmd"
elif [[ "$status" == "Failed" ]]; then
  echo "Pod $pod_name has failed. Fetching logs:"
  kubectl -n "$namespace" logs "$pod_name"
  exit 1
else
  echo "Pod $pod_name did not complete within the time limit of $((timeout / 60)) minutes. Fetching logs:"
  kubectl -n "$namespace" logs "$pod_name"
  exit 1
fi