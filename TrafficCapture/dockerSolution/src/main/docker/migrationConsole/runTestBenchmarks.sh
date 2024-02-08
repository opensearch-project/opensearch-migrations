#!/bin/bash

# Default values
endpoint="https://capture-proxy-es:9200"
auth_user="admin"
auth_pass="admin"
no_auth=false
no_ssl=false

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
  echo "  --no-ssl                              Disable SSL when making OSB requests."
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
        --no-ssl)
            no_ssl=true
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

# Populate auth string
if [ "$no_auth" = true ]; then
    auth_string=""
else
    auth_string=",basic_auth_user:${auth_user},basic_auth_password:${auth_pass}"
fi

if [ "$no_ssl" = true ]; then
    base_options_string=""
else
    base_options_string="use_ssl:true,verify_certs:false"
fi

# Construct the final client options string
client_options="${base_options_string}${auth_string}"

echo "Running opensearch-benchmark workloads against ${endpoint}"
echo "Running opensearch-benchmark w/ 'geonames' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=geonames --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'http_logs' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=http_logs --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'nested' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=nested --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'nyc_taxis' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=nyc_taxis --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options