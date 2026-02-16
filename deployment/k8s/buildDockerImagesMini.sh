#!/bin/bash

set -e

sync_ecr_repo() {
  REPO_NAME="migrations-local-repo"
  ACCOUNT_ID=$(aws sts get-caller-identity --query "Account" --output text 2>/dev/null)
  if [[ -z "$ACCOUNT_ID" ]]; then
      echo "Error: Unable to retrieve AWS Account ID. Check your AWS CLI configuration."
      exit 1
  fi

  # Retrieve the AWS Region from environment variable or AWS CLI config
  REGION=${AWS_REGION:-$(aws configure get region)}
  if [[ -z "$REGION" ]]; then
      echo "Error: Unable to determine AWS region. Set AWS_REGION environment variable or configure it in AWS CLI."
      exit 1
  fi

  echo "Using account: $ACCOUNT_ID and region: $REGION"
  ECR_URI="$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"
  ECR_REPO_URI="$ECR_URI/$REPO_NAME"

  # Authenticate Docker with ECR
  aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$ECR_URI"

  # Get migration images that are latest
  local_images=$(docker images --format "{{.Repository}}:{{.Tag}}" | grep "^migrations.*:latest$")

  echo "Found following images to export to ECR: $local_images"

  # Iterate over each image
  for image in $local_images; do
      # Tag the image for ECR
      image_name=$(echo "$image" | cut -d'/' -f2 | cut -d':' -f1)
      tag=$(echo "$image" | cut -d':' -f2)
      ecr_image="$ECR_REPO_URI:$image_name-$tag"
      echo "Tagging $image as $ecr_image"
      docker tag "$image" "$ecr_image"

      # Push the image to ECR
      echo "Pushing $ecr_image to ECR..."
      if docker push "$ecr_image"; then
          echo "Successfully pushed $ecr_image"
      else
          echo "Failed to push $ecr_image"
      fi
  done
}

# Optional helper function to create an ECR repo
create_ecr_repo() {
  stack_name="$REPO_NAME-stack"
  aws cloudformation create-stack --stack-name my-ecr-repo-stack --template-body "{
    \"Resources\": {
      \"ECRRepository\": {
        \"Type\": \"AWS::ECR::Repository\",
        \"Properties\": {
          \"RepositoryName\": \"$REPO_NAME\"
        }
      }
    },
    \"Outputs\": {
      \"RepositoryArn\": {
        \"Description\": \"The ARN of the ECR repository\",
        \"Value\": { \"Fn::GetAtt\": [\"ECRRepository\", \"Arn\"] }
      }
    }
  }" --stack-name "$stack_name"


  # Wait for the stack creation to complete
  aws cloudformation wait stack-create-complete --stack-name "$stack_name"

  # Retrieve and print the output
  aws cloudformation describe-stacks --stack-name "$stack_name" --query "Stacks[0].Outputs[?OutputKey=='RepositoryArn'].OutputValue" --output text
}

# Function to display usage
usage() {
    echo "Usage: $0 [--sync-ecr] [--create-ecr]"
    exit 1
}

SYNC_ECR=false
SKIP_BUILD=false
## Parse command-line arguments
while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --create-ecr)
            create_ecr_repo
            exit 0
            ;;
        --sync-ecr)
            SYNC_ECR=true
            shift 1
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift 1
            ;;
        *)
            echo "Invalid option: $1"
            usage
            ;;
    esac
done

# Allow executing this script from any dir
script_abs_path=$(readlink -f "$0")
script_dir_abs_path=$(dirname "$script_abs_path")
cd "$script_dir_abs_path" || exit

cd ../.. || exit

# Check if registry addon is enabled, enable it if not
if ! minikube addons list | grep -q "registry.*enabled"; then
  echo "⚠️  Minikube registry addon not enabled. Enabling it now..."
  minikube addons enable registry
  echo "✅  Registry addon enabled"
  
  # Wait for registry pod to be ready
  # Note: The 'actual-registry=true' label is set by Minikube's registry addon to distinguish
  # the actual registry pod from the registry proxy/daemonset pods
  echo "⏳  Waiting for registry pod to be ready..."
  kubectl wait --for=condition=ready pod -l actual-registry=true -n kube-system --timeout=120s || \
    kubectl wait --for=condition=ready pod -l kubernetes.io/minikube-addons=registry -n kube-system --timeout=120s || \
    echo "⚠️  Could not verify registry pod readiness, proceeding anyway..."
fi

eval $(minikube docker-env)

if [ "$SKIP_BUILD" = "false" ]; then
  ./gradlew :buildDockerImages -x test --info --stacktrace
fi

# Push images to minikube registry addon in parallel
# Inside minikube's docker context, registry is at localhost:5000
echo "📦  Pushing images to minikube registry"
pids=()
for image in $(docker images --format "{{.Repository}}:{{.Tag}}" | grep "^migrations.*:latest$"); do
  image_name=$(echo "$image" | sed 's/:latest$//')
  docker tag "$image" "localhost:5000/$image_name:latest"
  docker push "localhost:5000/$image_name:latest" &
  pids+=($!)
done

failed=0
for pid in "${pids[@]}"; do
  if ! wait "$pid"; then
    failed=1
  fi
done
if [ "$failed" -ne 0 ]; then
  echo "❌  Some image pushes failed"
  exit 1
fi
echo "✅  All images pushed"

if [ "$SYNC_ECR" = "true" ]; then
  sync_ecr_repo
fi
