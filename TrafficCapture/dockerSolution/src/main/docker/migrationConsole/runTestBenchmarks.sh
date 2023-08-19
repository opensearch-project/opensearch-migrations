#!/bin/bash

# Default values
default_docker_endpoint="https://capture_proxy_es:9200"
default_copilot_endpoint="https://capture-proxy-es:443"
auth_user="admin"
auth_pass="admin"
no_auth=false

# Check for the presence of COPILOT_SERVICE_NAME environment variable
if [ -n "$COPILOT_SERVICE_NAME" ]; then
    ENDPOINT="$default_copilot_endpoint"
else
    ENDPOINT="$default_docker_endpoint"
fi

# Override default values with optional command-line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --endpoint)
            ENDPOINT="$2"
            shift
            shift
            ;;
        --auth_user)
            auth_user="$2"
            shift
            shift
            ;;
        --auth_pass)
            auth_pass="$2"
            shift
            shift
            ;;
        --no-auth)
            no_auth=true
            shift
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

# Construct the final client options string
base_options_string="use_ssl:true,verify_certs:false"
client_options="${base_options_string}${auth_string}"

echo "Running opensearch-benchmark workloads against ${ENDPOINT}"
echo "Running opensearch-benchmark w/ 'geonames' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$ENDPOINT --workload=geonames --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'http_logs' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$ENDPOINT --workload=http_logs --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'nested' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$ENDPOINT --workload=nested --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options &&
echo "Running opensearch-benchmark w/ 'nyc_taxis' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$ENDPOINT --workload=nyc_taxis --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options