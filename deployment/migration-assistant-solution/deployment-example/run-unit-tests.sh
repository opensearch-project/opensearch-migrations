#!/bin/bash
# This script runs all tests for the root CDK project,

[ "$DEBUG" == 'true' ] && set -x
set -e

usage() {
  cat <<EOF
Usage: ${0##*/} [OPTIONS]

Options:
  --ecr-url <URL>   Specify the ECR repository URL to pull opensearch-migrations base images from, in the format:
                    <account-id>.dkr.ecr.<region>.amazonaws.com/<repository-name>
  -h, --h, --help   Display this help and exit.

Examples:
  ${0##*/} --ecr-url 123456789012.dkr.ecr.us-east-1.amazonaws.com/my-repo

EOF
  exit 1
}

ECR_ACCOUNT=""
ECR_REPO_URL=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --ecr-url)
      ECR_REPO_URL="$2"
      ECR_ACCOUNT="${ECR_REPO_URL%%/*}"
      shift # past argument
      shift # past value
      ;;
    -h|--h|--help)
      usage
      ;;
    -*)
      echo "Unknown option $1"
      usage
      ;;
    *)
      shift # past argument
      ;;
  esac
done

# Check load-images-to-ecr.sh for loading needed images into ECR
pull_from_ecr() {
  local docker_image_tag=$1
  local ecr_image_tag=$2

  echo "Checking for local Docker image: $docker_image_tag"

  # Check if the image exists locally
  if docker image inspect $docker_image_tag >/dev/null 2>&1; then
    echo "Docker image already exists locally."
  else
    echo "Attempting to pull Docker image from ECR: $docker_image_tag"

    docker pull "$ECR_REPO_URL:$ecr_image_tag"
    docker tag "$ECR_REPO_URL:$ecr_image_tag" "$docker_image_tag"
  fi
}

pull_docker_image() {
  local image_name=$1

  echo "Checking for Docker image: $image_name"

  # Check if the image exists locally
  if docker image inspect $image_name >/dev/null 2>&1; then
    echo "Docker image already exists locally."
  else
    echo "Attempting to pull Docker image: $image_name"

    # Try to pull the Docker image
    if docker pull $image_name; then
      echo "Docker image pulled successfully."
    else
      echo "Failed to pull Docker image. Aborting unit tests."
      exit 1
    fi
  fi
}

prepare_jest_coverage_report() {
	local component_name=$1

    if [ ! -d "coverage" ]; then
        echo "ValidationError: Missing required directory coverage after running unit tests"
        exit 129
    fi

	# prepare coverage reports
    rm -fr coverage/lcov-report
    mkdir -p "$coverage_reports_top_path"/jest
    coverage_report_path=$coverage_reports_top_path/jest/$component_name
    rm -fr "$coverage_report_path"
    mv coverage "$coverage_report_path"
}

run_python_tests() {
  local component_path=$1
  local component_name=$2
  shift 2
  local pytest_args=("$@")  # captures all remaining arguments

  echo "------------------------------------------------------------------------------"
  echo "[Test] Run unit test with coverage for $component_name"
  echo "------------------------------------------------------------------------------"
  echo "cd $component_path"
  cd "$component_path"

  echo "python3 -m pip install --upgrade pipenv"
  python3 -m pip install --upgrade pipenv
  echo "pipenv install --deploy --dev"
  pipenv install --deploy --dev
  echo "pipenv run python -m coverage run -m pytest ${pytest_args[*]}"
  pipenv run python -m coverage run -m pytest "${pytest_args[@]}"

  echo "pipenv run python -m coverage xml --omit '*/tests/*'"
  pipenv run python -m coverage xml --omit "*/tests/*"

  # The coverage module uses absolutes paths in its coverage output. To avoid dependencies of tools (such as SonarQube)
  # on different absolute paths for source directories, this substitution is used to convert each absolute source
  # directory path to the corresponding project relative path. The $source_dir holds the absolute path for source
  # directory.
  sed -i -e "s,<source>$source_dir,<source>source,g" coverage.xml
}


run_gradle_tests() {
  local component_path=$1
  local component_name=$2
  local gradle_arguments=$3

  echo "------------------------------------------------------------------------------"
  echo "[Test] Run unit test with coverage for $component_name"
  echo "------------------------------------------------------------------------------"
  echo "cd $component_path"
  cd "$component_path"

  ./gradlew $gradle_arguments
}

run_npm_tests() {
  local component_path=$1
  local component_name=$2

  echo "------------------------------------------------------------------------------"
  echo "[Test] Run unit test with coverage for $component_name"
  echo "------------------------------------------------------------------------------"
  echo "cd $component_path"
  cd "$component_path"

  # install dependencies
  npm install

  # run unit tests
  npm test

  # prepare coverage reports
  prepare_jest_coverage_report "$component_name"
  rm -rf coverage node_modules package-lock.json
}

check_test_failure() {
  local component_name=$1

  # Check the result of the test and exit if a failure is identified
  if [ $? -eq 0 ]
  then
    echo "Test for $component_name passed"
  else
    echo "******************************************************************************"
    echo "Test FAILED for $component_name"
    echo "******************************************************************************"
    exit 1
  fi
}

# Prep environment for ES/OS Docker Containers
echo "Attempting update for vm.max_map_count"
sudo sysctl -w vm.max_map_count=262144
echo "Successfully updated vm.max_map_count"

if [[ -n "$ECR_ACCOUNT" ]] && \
   aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin "$ECR_ACCOUNT" && \
   pull_from_ecr "amazonlinux:2023" "amazonlinux-2023"; then
  echo "Successful accessed ECR account $ECR_ACCOUNT and pulled amazonlinux-2023, pulling remaining base images from here..."
  for pair in \
    "alpine:3.16 alpine-3.16" \
    "confluentinc/cp-kafka:7.5.0 cp-kafka-7.5.0" \
    "docker.elastic.co/elasticsearch/elasticsearch:5.6.16 elasticsearch-5.6.16" \
    "docker.elastic.co/elasticsearch/elasticsearch:6.3.2 elasticsearch-6.3.2" \
    "docker.elastic.co/elasticsearch/elasticsearch:7.11.2 elasticsearch-7.11.2" \
    "docker.elastic.co/elasticsearch/elasticsearch:7.12.1 elasticsearch-7.12.1" \
    "docker.elastic.co/elasticsearch/elasticsearch:7.13.4 elasticsearch-7.13.4" \
    "docker.elastic.co/elasticsearch/elasticsearch:7.14.2 elasticsearch-7.14.2" \
    "docker.elastic.co/elasticsearch/elasticsearch:7.15.2 elasticsearch-7.15.2" \
    "docker.elastic.co/elasticsearch/elasticsearch:7.16.3 elasticsearch-7.16.3" \
    "docker.elastic.co/elasticsearch/elasticsearch:7.17.22 elasticsearch-7.17.22" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.0.0 elasticsearch-8.0.0" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.1.3 elasticsearch-8.1.3" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.2.3 elasticsearch-8.2.3" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.3.3 elasticsearch-8.3.3" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.4.3 elasticsearch-8.4.3" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.5.3 elasticsearch-8.5.3" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.6.2 elasticsearch-8.6.2" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.7.1 elasticsearch-8.7.1" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.8.2 elasticsearch-8.8.2" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.9.2 elasticsearch-8.9.2" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.10.4 elasticsearch-8.10.4" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.11.4 elasticsearch-8.11.4" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.12.2 elasticsearch-8.12.2" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.13.4 elasticsearch-8.13.4" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.14.3 elasticsearch-8.14.3" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.15.5 elasticsearch-8.15.5" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.16.6 elasticsearch-8.16.6" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.17.5 elasticsearch-8.17.5" \
    "docker.elastic.co/elasticsearch/elasticsearch:8.18.4 elasticsearch-8.18.4" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:6.0.1 elasticsearch-oss-6.0.1" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:6.1.4 elasticsearch-oss-6.1.4" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:6.2.4 elasticsearch-oss-6.2.4" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:6.4.3 elasticsearch-oss-6.4.3" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:6.5.4 elasticsearch-oss-6.5.4" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:6.6.2 elasticsearch-oss-6.6.2" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:6.7.2 elasticsearch-oss-6.7.2" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:6.8.23 elasticsearch-oss-6.8.23" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.0.1 elasticsearch-oss-7.0.1" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.1.1 elasticsearch-oss-7.1.1" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.2.1 elasticsearch-oss-7.2.1" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.3.2 elasticsearch-oss-7.3.2" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.4.2 elasticsearch-oss-7.4.2" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.5.2 elasticsearch-oss-7.5.2" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.6.2 elasticsearch-oss-7.6.2" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.7.1 elasticsearch-oss-7.7.1" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.8.1 elasticsearch-oss-7.8.1" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.9.3 elasticsearch-oss-7.9.3" \
    "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2 elasticsearch-oss-7.10.2" \
    "elasticsearch:1.5.2 elasticsearch-1.5.2" \
    "elasticsearch:1.6.2 elasticsearch-1.6.2" \
    "elasticsearch:1.7.6 elasticsearch-1.7.6" \
    "elasticsearch:2.0.2 elasticsearch-2.0.2" \
    "elasticsearch:2.1.2 elasticsearch-2.1.2" \
    "elasticsearch:2.2.2 elasticsearch-2.2.2" \
    "elasticsearch:2.3.5 elasticsearch-2.3.5" \
    "elasticsearch:2.4.6 elasticsearch-2.4.6" \
    "elasticsearch:5.0.2 elasticsearch-5.0.2" \
    "elasticsearch:5.1.2 elasticsearch-5.1.2" \
    "elasticsearch:5.2.2 elasticsearch-5.2.2" \
    "elasticsearch:5.3.2 elasticsearch-5.3.2" \
    "elasticsearch:5.4.2 elasticsearch-5.4.2" \
    "elasticsearch:5.5.2 elasticsearch-5.5.2" \
    "ghcr.io/shopify/toxiproxy:2.9.0 toxiproxy-2.9.0" \
    "httpd:alpine httpd-alpine" \
    "nginx:stable-alpine3.21-slim nginx-stable-alpine3.21-slim" \
    "opensearchproject/opensearch:1.3.16 opensearch-1.3.16" \
    "opensearchproject/opensearch:2.19.1 opensearch-2.19.1" \
    "opensearchproject/opensearch:3.0.0 opensearch-3.0.0" \
    "python:3.11-slim python-3.11-slim" \
    "testcontainers/ryuk:0.11.0 ryuk-0.11.0"
  do
    set -- $pair
    pull_from_ecr "$1" "$2"
  done
else
  echo "Falling back to Docker Hub"
  for img in \
    alpine:3.16 \
    amazonlinux:2023 \
    confluentinc/cp-kafka:7.5.0 \
    docker.elastic.co/elasticsearch/elasticsearch:5.6.16 \
    docker.elastic.co/elasticsearch/elasticsearch:6.3.2 \
    docker.elastic.co/elasticsearch/elasticsearch:7.11.2 \
    docker.elastic.co/elasticsearch/elasticsearch:7.12.1 \
    docker.elastic.co/elasticsearch/elasticsearch:7.13.4 \
    docker.elastic.co/elasticsearch/elasticsearch:7.14.2 \
    docker.elastic.co/elasticsearch/elasticsearch:7.15.2 \
    docker.elastic.co/elasticsearch/elasticsearch:7.16.3 \
    docker.elastic.co/elasticsearch/elasticsearch:7.17.22 \
    docker.elastic.co/elasticsearch/elasticsearch:8.0.0 \
    docker.elastic.co/elasticsearch/elasticsearch:8.1.3 \
    docker.elastic.co/elasticsearch/elasticsearch:8.2.3 \
    docker.elastic.co/elasticsearch/elasticsearch:8.3.3 \
    docker.elastic.co/elasticsearch/elasticsearch:8.4.3 \
    docker.elastic.co/elasticsearch/elasticsearch:8.5.3 \
    docker.elastic.co/elasticsearch/elasticsearch:8.6.2 \
    docker.elastic.co/elasticsearch/elasticsearch:8.7.1 \
    docker.elastic.co/elasticsearch/elasticsearch:8.8.2 \
    docker.elastic.co/elasticsearch/elasticsearch:8.9.2 \
    docker.elastic.co/elasticsearch/elasticsearch:8.10.4 \
    docker.elastic.co/elasticsearch/elasticsearch:8.11.4 \
    docker.elastic.co/elasticsearch/elasticsearch:8.12.2 \
    docker.elastic.co/elasticsearch/elasticsearch:8.13.4 \
    docker.elastic.co/elasticsearch/elasticsearch:8.14.3 \
    docker.elastic.co/elasticsearch/elasticsearch:8.15.5 \
    docker.elastic.co/elasticsearch/elasticsearch:8.16.6 \
    docker.elastic.co/elasticsearch/elasticsearch:8.17.5 \
    docker.elastic.co/elasticsearch/elasticsearch:8.18.4 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:6.0.1 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:6.1.4 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:6.2.4 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:6.4.3 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:6.5.4 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:6.6.2 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:6.7.2 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:6.8.23 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.0.1 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.1.1 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.2.1 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.3.2 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.4.2 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.5.2 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.6.2 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.7.1 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.8.1 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.9.3 \
    docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2 \
    elasticsearch:1.5.2 \
    elasticsearch:1.6.2 \
    elasticsearch:1.7.6 \
    elasticsearch:2.0.2 \
    elasticsearch:2.1.2 \
    elasticsearch:2.2.2 \
    elasticsearch:2.3.5 \
    elasticsearch:2.4.6 \
    elasticsearch:5.0.2 \
    elasticsearch:5.1.2 \
    elasticsearch:5.2.2 \
    elasticsearch:5.3.2 \
    elasticsearch:5.4.2 \
    elasticsearch:5.5.2 \
    ghcr.io/shopify/toxiproxy:2.9.0 \
    httpd:alpine \
    nginx:stable-alpine3.21-slim \
    opensearchproject/opensearch:1.3.16 \
    opensearchproject/opensearch:2.19.1 \
    opensearchproject/opensearch:3.0.0 \
    python:3.11-slim \
    testcontainers/ryuk:0.11.0
  do
    pull_docker_image "$img"
  done
fi

echo "Images pulled successfully. Continuing to unit tests."

# Run unit tests
echo "Running unit tests"

# Get reference for source folder
source_dir="$(cd $PWD/../source; pwd -P)"
coverage_reports_top_path=$source_dir/test/coverage-reports

run_gradle_tests "$source_dir/opensearch-migrations" "opensearch-migrations" "build copyDependencies mergeJacocoReports -x isolatedTest -x spotlessCheck"
check_test_failure "opensearch-migrations"

console_lib_args=(-m "not slow")
run_python_tests "$source_dir/opensearch-migrations/TrafficCapture/dockerSolution/src/main/docker/migrationConsole/lib/console_link" "ConsoleLibrary" "${console_lib_args[@]}"
check_test_failure "ConsoleLibrary"

run_python_tests "$source_dir/opensearch-migrations/TrafficCapture/dockerSolution/src/main/docker/migrationConsole/cluster_tools" "ClusterTools"
check_test_failure "ClusterTools"

run_python_tests "$source_dir/opensearch-migrations/TrafficCapture/dockerSolution/src/main/docker/k8sConfigMapUtilScripts" "K8sConfigMapUtilScripts"
check_test_failure "K8sConfigMapUtilScripts"

# Test packages from /source directory
declare -a packages=(
    "opensearch-migrations/deployment/migration-assistant-solution" "opensearch-migrations/deployment/cdk/opensearch-service-migration"
)
for package in "${packages[@]}"; do
  package_name=$(echo "$package" | sed 's/.*\///')
  run_npm_tests "$source_dir"/"$package" "$package_name"
  check_test_failure "$package_name"
done
