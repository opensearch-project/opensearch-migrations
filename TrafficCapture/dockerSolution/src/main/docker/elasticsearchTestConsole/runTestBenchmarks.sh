#!/bin/bash

# Default values

# Check for the presence of SOURCE_DOMAIN_ENDPOINT environment variable
if [ -n "$SOURCE_DOMAIN_ENDPOINT" ]; then
    endpoint="${SOURCE_DOMAIN_ENDPOINT}"
else
    endpoint="https://capture-proxy:9200"
fi
auth_user="admin"
auth_pass="admin"
no_auth=false

usage() {
  echo ""
  echo "Script to run a series of OpenSearch Benchmark workloads in test-mode for quick simulation of incoming traffic."
  echo ""
  echo "Usage: "
  echo "  ./runTestBenchmarks.sh <>"
  echo ""
  echo "Options:"
  echo "  --endpoint                            The endpoint to send OSB workloads to."
  echo "  --auth-user                           The basic auth user to use for OSB requests."
  echo "  --auth-pass                           The basic auth password to use for OSB requests."
  echo "  --no-auth                             Use no auth when making OSB requests."
  echo ""
  exit 1
}

# Override default values with optional command-line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --endpoint)
            endpoint="$2"
            shift
            shift
            ;;
        --auth-user)
            auth_user="$2"
            shift
            shift
            ;;
        --auth-pass)
            auth_pass="$2"
            shift
            shift
            ;;
        --no-auth)
            no_auth=true
            shift
            ;;
        -h|--h|--help)
          usage
          ;;
        -*)
          echo "Unknown option $1"
          usage
          ;;
        *)
          shift
          ;;
    esac
done


# Initialize an empty array to hold non-empty values
options=()

if [[ "$endpoint" == https:* ]]; then
    options+=("use_ssl:true,verify_certs:false")
fi


# Populate auth string
if [ "$no_auth" = false ]; then
    options+=("basic_auth_user:${auth_user},basic_auth_password:${auth_pass}")
fi

# Join the non-empty values using a comma
client_options=$(IFS=,; echo "${options[*]}")

set -o xtrace

# Fix OSB commit on latest tested version
workload_revision="fc64258a9b2ed2451423d7758ca1c5880626c520"

echo "Running opensearch-benchmark workloads against ${endpoint}"
echo "Running opensearch-benchmark w/ 'geonames' workload..." &&
pipenv run opensearch-benchmark run --distribution-version=1.0.0 --exclude-tasks=check-cluster-health --workload-revision=$workload_revision --target-host=$endpoint --workload=geonames --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'http_logs' workload..." &&
pipenv run opensearch-benchmark run --distribution-version=1.0.0 --exclude-tasks=check-cluster-health --workload-revision=$workload_revision --target-host=$endpoint --workload=http_logs --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'nested' workload..." &&
pipenv run opensearch-benchmark run --distribution-version=1.0.0 --exclude-tasks=check-cluster-health --workload-revision=$workload_revision --target-host=$endpoint --workload=nested --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'nyc_taxis' workload..." &&
pipenv run opensearch-benchmark run --distribution-version=1.0.0 --exclude-tasks=check-cluster-health --workload-revision=$workload_revision --target-host=$endpoint --workload=nyc_taxis --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options