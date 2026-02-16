#!/bin/bash

# Build Docker images using BuildKit and push directly to an in-cluster registry.
# This mirrors the aws-bootstrap.sh --build-images-locally code path.

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

if [ "$SKIP_BUILD" = "false" ]; then
  # Set up BuildKit builder and local registry in k8s
  # Same code path as aws-bootstrap.sh --build-images-locally and localTesting.sh
  export USE_LOCAL_REGISTRY=true
  ./buildImages/setUpK8sImageBuildServices.sh

  ARCH=$(uname -m)
  case "$ARCH" in
    x86_64) PLATFORM="amd64" ;;
    arm64|aarch64) PLATFORM="arm64" ;;
    *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
  esac

  # Build images directly to registry via BuildKit (no separate push step needed)
  ./gradlew :buildImages:buildImagesToRegistry_$PLATFORM -x test --info --stacktrace

  # Clean up BuildKit to free resources for test workloads
  echo "🧹  Cleaning up BuildKit resources..."
  docker buildx rm local-remote-builder 2>/dev/null || true
  helm uninstall buildkit -n buildkit 2>/dev/null || true
  echo "✅  BuildKit cleaned up, docker-registry preserved for tests"
fi

if [ "$SYNC_ECR" = "true" ]; then
  sync_ecr_repo
fi
